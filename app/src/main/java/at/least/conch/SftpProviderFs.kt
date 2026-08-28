package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One directory entry as the SAF provider surfaces it (pure data). */
data class SftpDocEntry(
    val displayName: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val modifiedSec: Long,
)

/**
 * SAF document-id codec: `<hostId>:<absolute path>`. Host ids are UUIDs
 * (colon-free), so the split on the FIRST colon is unambiguous no matter
 * what the remote path contains. The root document of a host is the empty
 * path — resolved lazily to the SFTP home directory (one canonicalize per
 * host) so users land where their shell does, not at "/".
 */
object SftpDocIds {
    const val ROOT_PATH = ""

    fun hostOf(documentId: String): String? =
        documentId.substringBefore(':', "").takeIf { it.isNotEmpty() && documentId.contains(':') }

    fun pathOf(documentId: String): String =
        documentId.substringAfter(':', ROOT_PATH)

    fun encode(hostId: String, path: String): String = "$hostId:$path"

    /** Parent path for navigation; the root and "/" are their own parent. */
    fun parentPath(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return ROOT_PATH
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) "/" else trimmed.substring(0, cut)
    }

    fun childPath(parent: String, name: String): String =
        if (parent == "/" || parent.isEmpty()) "/$name" else "$parent/$name"

    fun isRoot(documentId: String): Boolean =
        documentId.isNotEmpty() && documentId.endsWith(':')
}

/**
 * Surface the SAF provider needs from an SFTP world. [SftpProviderFs] is
 * the real implementation; Robolectric tests substitute an in-memory fake
 * (the picker shell is what needs Robolectric — real-SFTP correctness is
 * covered by SftpProviderFsTest against MINA and the Docker OpenSSH matrix,
 * which also keeps Robolectric's sandbox classloader away from sshj's
 * JVM-global crypto provider registry).
 */
interface SftpBackend {
    fun homePath(hostId: String): String

    fun list(hostId: String, path: String): List<SftpDocEntry>

    fun stat(hostId: String, path: String): SftpDocEntry?

    fun mkdir(hostId: String, path: String)

    fun createFile(hostId: String, path: String)

    fun delete(hostId: String, path: String)

    fun rename(hostId: String, from: String, to: String)

    fun openRead(hostId: String, path: String): InputStream

    fun openWrite(hostId: String, path: String): OutputStream
}

/**
 * SFTP backend for the SAF DocumentsProvider (improvement-plan 3.1).
 * Android-bound UI is one thin layer up ([SftpDocumentsProvider]); this
 * class is plain JVM so it is tested against the in-process MINA SFTP
 * server and the Docker real-OpenSSH matrix directly.
 *
 * Connection lifecycle — the part the plan called out as the hard bit: a
 * picker call can arrive with no Activity alive, so this pool OWNS its
 * connections. Refcounted per operation (open streams keep their lease),
 * closed after [idleCloseMs] without use so a finished file-picker session
 * doesn't pin an SSH tunnel forever. Host keys must already be trusted:
 * under a picker call there is no UI to answer a TOFU prompt, so untrusted
 * hosts fail fast instead of hanging — first connect through the app.
 */
class SftpProviderFs(
    private val loadHost: (String) -> Host?,
    private val connectHost: (Host) -> SSHClient,
    idleCloseMs: Long = 60_000,
) : SftpBackend, AutoCloseable {

    private class Lease(val ssh: SSHClient, val sftp: SFTPClient) {
        val inUse = AtomicInteger(0)

        @Volatile
        var lastUsed: Long = System.currentTimeMillis()
    }

    private val pool = ConcurrentHashMap<String, Lease>()
    private val homePaths = ConcurrentHashMap<String, String>()

    @Volatile
    private var closed = false

    private val sweeper = Thread {
        while (!closed) {
            runCatching { Thread.sleep(SWEEP_MS) }
            if (closed) break
            val now = System.currentTimeMillis()
            val doomed = mutableListOf<Lease>()
            for (key in pool.keys) {
                // The idle test and the removal happen under the same bin
                // lock acquire() uses, so a lease that acquire() just
                // re-leased cannot be pulled out from under that operation
                // (remove(key, value) only checked identity, not inUse).
                pool.computeIfPresent(key) { _, lease ->
                    if (lease.inUse.get() == 0 && now - lease.lastUsed > idleCloseMs) {
                        doomed.add(lease)
                        null
                    } else {
                        lease
                    }
                }
            }
            doomed.forEach { closeLease(it) }
        }
    }.apply {
        name = "conch-saf-idle-close"
        isDaemon = true
        start()
    }

    /** Resolved absolute home path of a host (the root document's target). */
    override fun homePath(hostId: String): String =
        homePaths.getOrPut(hostId) {
            withSftp(hostId) { it.canonicalize(".") }
        }

    override fun list(hostId: String, path: String): List<SftpDocEntry> {
        val target = resolve(hostId, path)
        return withSftp(hostId) { sftp ->
            sftp.ls(target)
                .filter { it.name != "." && it.name != ".." }
                .map { info ->
                    SftpDocEntry(
                        displayName = info.name,
                        isDir = info.attributes.type == FileMode.Type.DIRECTORY,
                        sizeBytes = info.attributes.size,
                        modifiedSec = info.attributes.mtime,
                    )
                }
                .sortedWith(compareByDescending<SftpDocEntry> { it.isDir }.thenBy { it.displayName.lowercase() })
        }
    }

    override fun stat(hostId: String, path: String): SftpDocEntry? {
        val target = resolve(hostId, path)
        return withSftp(hostId) { sftp ->
            val a = sftp.statExistence(target) ?: return@withSftp null
            SftpDocEntry(
                displayName = target.substringAfterLast('/').ifEmpty { hostId },
                isDir = a.type == FileMode.Type.DIRECTORY,
                sizeBytes = a.size,
                modifiedSec = a.mtime,
            )
        }
    }

    override fun mkdir(hostId: String, path: String) {
        withSftp(hostId) { it.mkdir(resolve(hostId, path)) }
    }

    override fun createFile(hostId: String, path: String) {
        withSftp(hostId) {
            it.open(resolve(hostId, path), setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).close()
        }
    }

    override fun delete(hostId: String, path: String) {
        val target = resolve(hostId, path)
        withSftp(hostId) { sftp ->
            val attrs = sftp.statExistence(target)
                ?: throw IOException("No such file: $target")
            if (attrs.type == FileMode.Type.DIRECTORY) sftp.rmdir(target) else sftp.rm(target)
        }
    }

    override fun rename(hostId: String, from: String, to: String) {
        withSftp(hostId) { it.rename(resolve(hostId, from), resolve(hostId, to)) }
    }

    /** Read stream; keeps the connection leased until the stream closes. */
    override fun openRead(hostId: String, path: String): InputStream {
        val lease = acquire(hostId)
        try {
            val file: RemoteFile = lease.sftp.open(resolve(hostId, path))
            return LeasedInputStream(file.RemoteFileInputStream(), lease, file)
        } catch (e: Exception) {
            lease.inUse.decrementAndGet()
            throw e
        }
    }

    /** Write stream (create/truncate); leased like [openRead]. */
    override fun openWrite(hostId: String, path: String): OutputStream {
        val lease = acquire(hostId)
        try {
            val file = lease.sftp.open(
                resolve(hostId, path),
                setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
            )
            return LeasedOutputStream(file.RemoteFileOutputStream(), lease, file)
        } catch (e: Exception) {
            lease.inUse.decrementAndGet()
            throw e
        }
    }

    /** "" resolves to the host home; other paths pass through untouched. */
    private fun resolve(hostId: String, path: String): String =
        if (path.isEmpty()) homePath(hostId) else path

    private fun <T> withSftp(hostId: String, block: (SFTPClient) -> T): T {
        val lease = acquire(hostId)
        try {
            return block(lease.sftp)
        } finally {
            release(lease)
        }
    }

    private fun acquire(hostId: String): Lease {
        check(!closed) { "provider backend shut down" }
        // compute() is atomic per key: a first connect runs under the bin
        // lock, so concurrent picker calls for the same host wait for that
        // one connection instead of racing to open duplicates
        var dead: Lease? = null
        val lease = pool.compute(hostId) { _, existing ->
            if (existing != null && existing.ssh.isConnected) {
                existing.inUse.incrementAndGet()
                existing
            } else {
                // A dropped transport is replaced, not retried against:
                // every failing op used to refresh lastUsed, so as long as
                // the user kept trying the dead lease never aged out.
                dead = existing
                val host = loadHost(hostId) ?: throw SFTPException("Unknown host")
                val ssh = connectHost(host)
                try {
                    Lease(ssh, ssh.newSFTPClient()).also { it.inUse.incrementAndGet() }
                } catch (e: Exception) {
                    runCatching { ssh.disconnect() }
                    throw e
                }
            }
        }
        // the remapping function above never maps to null, but compute()'s
        // signature allows it — checkNotNull keeps the null-safety honest
        checkNotNull(lease)
        dead?.let { closeLease(it) }
        lease.lastUsed = System.currentTimeMillis()
        return lease
    }

    private fun closeLease(lease: Lease) {
        runCatching { lease.sftp.close() }
        runCatching { lease.ssh.disconnect() }
    }

    private fun release(lease: Lease) {
        lease.inUse.decrementAndGet()
        lease.lastUsed = System.currentTimeMillis()
    }

    override fun close() {
        closed = true
        sweeper.interrupt()
        pool.values.forEach { closeLease(it) }
        pool.clear()
    }

    /** Releases the lease (and closes the remote file) when the stream ends. */
    private class LeasedInputStream(
        private val inner: InputStream,
        private val lease: Lease,
        private val file: RemoteFile,
    ) : InputStream() {
        override fun read(): Int = inner.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)

        override fun close() {
            runCatching { inner.close() }
            runCatching { file.close() }
            lease.inUse.decrementAndGet()
            lease.lastUsed = System.currentTimeMillis()
        }
    }

    private class LeasedOutputStream(
        private val inner: OutputStream,
        private val lease: Lease,
        private val file: RemoteFile,
    ) : OutputStream() {
        override fun write(b: Int) {
            inner.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
        }

        override fun flush() {
            inner.flush()
        }

        override fun close() {
            runCatching { inner.close() }
            runCatching { file.close() }
            lease.inUse.decrementAndGet()
            lease.lastUsed = System.currentTimeMillis()
        }
    }

    companion object {
        private const val SWEEP_MS = 5_000L
    }
}
