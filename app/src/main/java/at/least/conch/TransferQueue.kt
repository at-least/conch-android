package at.least.conch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracked SFTP transfers with live per-item progress and resume-from-offset
 * (iOS parity: `TransferQueue` + `TransfersSheet`). Compose-free: the UI
 * observes [items]; the sshj side is behind [TransferTransport] so the
 * queue's decisions run on the JVM against a fake.
 *
 * Downloads land in the app's Downloads dir, staged as `<name>.part` and
 * renamed on completion, so a drop mid-transfer never leaves a truncated
 * file under the final name — and the `.part` is what a retry resumes
 * from. One transfer at a time, in order, each on its own SFTP channel so
 * a transfer outlives the Files tab that started it.
 */
class TransferQueue(
    private val downloadsDir: File,
    /** Opens a fresh SFTP channel on the live connection; null when disconnected. */
    private val sftpProvider: () -> SFTPClient?,
    private val transportFactory: (SFTPClient) -> TransferTransport = { SshjTransport(it) },
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conch-transfer").apply { isDaemon = true }
    },
) {

    enum class Direction { DOWNLOAD, UPLOAD }

    sealed class State {
        data object Queued : State()
        data object Running : State()
        data object Done : State()
        data class Failed(val reason: String) : State()
        data object Cancelled : State()

        val isActive: Boolean get() = this is Queued || this is Running
        val isRetryable: Boolean get() = this is Failed || this is Cancelled
    }

    data class Item(
        val id: String,
        val direction: Direction,
        val name: String,
        val remotePath: String,
        /** Download: the final file. Upload: the staged local source. */
        val localFile: File,
        val totalBytes: Long?,
        val transferred: Long = 0,
        val state: State = State.Queued,
        /** Where the next run starts; the partial's length after a drop. */
        val resumeOffset: Long = 0,
        /** Upload only: the staged copy is ours to delete when finished. */
        val deleteSourceWhenDone: Boolean = false,
    )

    private val store = Store()
    val items: StateFlow<List<Item>> get() = store.flow

    /** Fires (off the main thread) when an item reaches [State.Done]. */
    @Volatile
    var onCompleted: ((Item) -> Unit)? = null

    private val cancelFlags = HashMap<String, AtomicBoolean>()

    // ---------------------------------------------------------------- enqueue

    /**
     * Same remote path already active → no duplicate (the button's disabled
     * state can lag a frame). A prior finished/failed item for the path is
     * replaced.
     */
    fun enqueueDownload(name: String, remotePath: String, remoteSize: Long?): Item? {
        require(TransferPlan.isSafeName(name)) { "unsafe remote name" }
        val item = Item(
            id = UUID.randomUUID().toString(),
            direction = Direction.DOWNLOAD,
            name = name,
            remotePath = remotePath,
            localFile = File(downloadsDir, name),
            totalBytes = remoteSize,
        )
        if (!store.admit(item)) return null
        submit(item.id)
        return item
    }

    fun enqueueUpload(source: File, remotePath: String, deleteSourceWhenDone: Boolean = false): Item? {
        val item = Item(
            id = UUID.randomUUID().toString(),
            direction = Direction.UPLOAD,
            name = remotePath.substringAfterLast('/'),
            remotePath = remotePath,
            localFile = source,
            totalBytes = source.length(),
            deleteSourceWhenDone = deleteSourceWhenDone,
        )
        if (!store.admit(item)) {
            if (deleteSourceWhenDone) source.delete()
            return null
        }
        submit(item.id)
        return item
    }

    // ---------------------------------------------------------------- control

    fun cancel(id: String) {
        val item = store.find(id) ?: return
        if (!item.state.isActive) return
        synchronized(cancelFlags) { cancelFlags[id]?.set(true) }
        // queued: the worker will see the flag and skip; mark now so the row updates
        if (item.state is State.Queued) store.update(id) { it.copy(state = State.Cancelled) }
    }

    fun cancelAll() {
        items.value.filter { it.state.isActive }.forEach { cancel(it.id) }
    }

    /** Re-queues a failed/cancelled item; a download resumes from its partial. */
    fun retry(id: String) {
        val item = store.find(id) ?: return
        if (!item.state.isRetryable) return
        store.update(id) { it.copy(state = State.Queued, transferred = it.resumeOffset) }
        submit(id)
    }

    fun clearFinished() = store.clearFinished()

    val activeCount: Int get() = items.value.count { it.state.isActive }

    fun close() {
        cancelAll()
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------- worker

    private fun submit(id: String) {
        val flag = AtomicBoolean(false)
        synchronized(cancelFlags) { cancelFlags[id] = flag }
        executor.execute(Runner(id, flag))
    }

    /** One transfer end to end; separate so the queue's own surface stays small. */
    private inner class Runner(private val id: String, private val cancelled: AtomicBoolean) : Runnable {

        override fun run() {
            val item = store.find(id) ?: return
            if (item.state !is State.Queued || cancelled.get()) return
            store.update(id) { it.copy(state = State.Running) }
            val client = sftpProvider()
            if (client == null) {
                store.update(id) { it.copy(state = State.Failed("Not connected")) }
                return
            }
            try {
                val transport = transportFactory(client)
                when (item.direction) {
                    Direction.DOWNLOAD -> download(item, transport)
                    Direction.UPLOAD -> upload(item, transport)
                }
            } catch (e: TransferCancelled) {
                store.update(id) { it.copy(state = State.Cancelled, resumeOffset = e.at, transferred = e.at) }
            } catch (e: Exception) {
                CrashReporting.report(e)
                val at = store.find(id)?.transferred ?: 0
                val reason = e.message ?: e.javaClass.simpleName
                store.update(id) { it.copy(state = State.Failed(reason), resumeOffset = at) }
            } finally {
                runCatching { client.close() }
                synchronized(cancelFlags) { cancelFlags.remove(id) }
            }
        }

        private fun download(item: Item, transport: TransferTransport) {
            val part = File(item.localFile.parentFile, item.name + ".part")
            val remoteSize = transport.remoteSize(item.remotePath) ?: item.totalBytes
            val plan = TransferPlan.download(
                finalSize = item.localFile.takeIf { it.exists() }?.length(),
                partSize = part.takeIf { it.exists() }?.length(),
                remoteSize = remoteSize,
            )
            val offset = when (plan) {
                TransferPlan.Action.AlreadyDone -> {
                    store.update(item.id) {
                        it.copy(state = State.Done, transferred = remoteSize ?: it.transferred, totalBytes = remoteSize)
                    }
                    store.find(item.id)?.let { onCompleted?.invoke(it) }
                    return
                }
                is TransferPlan.Action.Resume -> plan.offset
                TransferPlan.Action.FromScratch -> {
                    part.delete()
                    0L
                }
            }
            store.update(item.id) { it.copy(transferred = offset, totalBytes = remoteSize, resumeOffset = offset) }
            part.parentFile?.mkdirs()
            var done = offset
            java.io.FileOutputStream(part, offset > 0).use { out ->
                transport.download(item.remotePath, offset, out, cancelled) { n ->
                    done = n
                    store.update(item.id) { it.copy(transferred = n) }
                }
            }
            if (remoteSize != null && done != remoteSize) {
                error("short download: $done of $remoteSize bytes")
            }
            item.localFile.delete()
            if (!part.renameTo(item.localFile)) error("cannot rename ${part.name}")
            store.update(item.id) { it.copy(state = State.Done, transferred = done, resumeOffset = 0) }
            store.find(item.id)?.let { onCompleted?.invoke(it) }
        }

        private fun upload(item: Item, transport: TransferTransport) {
            val sourceSize = item.localFile.length()
            val plan = TransferPlan.upload(
                remoteSize = transport.remoteSize(item.remotePath),
                sourceSize = sourceSize,
            )
            val offset = (plan as? TransferPlan.Action.Resume)?.offset ?: 0L
            store.update(item.id) { it.copy(transferred = offset, totalBytes = sourceSize, resumeOffset = offset) }
            var done = offset
            item.localFile.inputStream().use { input ->
                var toSkip = offset
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) error("cannot seek local file to $offset")
                    toSkip -= skipped
                }
                transport.upload(input, item.remotePath, offset, cancelled) { n ->
                    done = n
                    store.update(item.id) { it.copy(transferred = n) }
                }
            }
            if (done != sourceSize) error("short upload: $done of $sourceSize bytes")
            if (item.deleteSourceWhenDone) item.localFile.delete()
            store.update(item.id) { it.copy(state = State.Done, transferred = done, resumeOffset = 0) }
            store.find(item.id)?.let { onCompleted?.invoke(it) }
        }
    }

    // ---------------------------------------------------------------- state

    /** The item list behind [items]; every mutation is a whole-list swap on the flow. */
    private class Store {
        val flow = MutableStateFlow<List<Item>>(emptyList())

        fun find(id: String): Item? = flow.value.firstOrNull { it.id == id }

        fun update(id: String, f: (Item) -> Item) {
            flow.update { list -> list.map { if (it.id == id) f(it) else it } }
        }

        /**
         * Adds [item] unless a transfer for the same direction + remote path
         * is still active (the button's disabled state can lag a frame); a
         * prior finished / failed one for that path is replaced.
         */
        fun admit(item: Item): Boolean {
            val same = { other: Item -> other.direction == item.direction && other.remotePath == item.remotePath }
            if (flow.value.any { same(it) && it.state.isActive }) return false
            flow.update { list -> list.filterNot(same) + item }
            return true
        }

        fun clearFinished() {
            flow.update { list -> list.filter { it.state !is State.Done } }
        }
    }
}

/** Labels shared by the transfers sheet and the Files tab. */
object TransferFormat {
    /** "12.3 / 45.0 MB" — bare count when the total is unknown. */
    fun progressLabel(done: Long, total: Long?): String =
        if (total != null) "${bytesLabel(done)} / ${bytesLabel(total)}" else bytesLabel(done)

    /** 0..1 for a determinate bar; null when the total is unknown. */
    fun progressFraction(done: Long, total: Long?): Float? =
        if (total == null || total <= 0) null else (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat()

    fun bytesLabel(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/** Thrown by a transport when the cancel flag flips; [at] = bytes safely on disk. */
class TransferCancelled(val at: Long) : Exception("Cancelled")

/**
 * The pure decisions of the queue (unit-tested): where a run starts and
 * whether it needs to run at all. Mirrors iOS: a local copy with the same
 * name AND size as the remote is fresh (zero round-trips); a `.part`
 * shorter than the remote is resumed at its length; anything else starts
 * over.
 */
object TransferPlan {
    sealed class Action {
        data object AlreadyDone : Action()
        data class Resume(val offset: Long) : Action()
        data object FromScratch : Action()
    }

    fun download(finalSize: Long?, partSize: Long?, remoteSize: Long?): Action = when {
        finalSize != null && remoteSize != null && finalSize == remoteSize -> Action.AlreadyDone
        partSize != null && partSize > 0 && (remoteSize == null || partSize < remoteSize) -> Action.Resume(partSize)
        else -> Action.FromScratch
    }

    /** A remote file shorter than the source is an interrupted upload of it: append from its end. */
    fun upload(remoteSize: Long?, sourceSize: Long): Action = when {
        remoteSize != null && remoteSize > 0 && remoteSize < sourceSize -> Action.Resume(remoteSize)
        else -> Action.FromScratch
    }

    /** The name is whatever the server put in SSH_FXP_NAME; it must stay one path component. */
    fun isSafeName(name: String): Boolean = name.isNotBlank() && !name.contains('/') && name != "." && name != ".."
}

/** The wire side of a transfer, so the queue is testable without sshj. */
interface TransferTransport {
    /** Size of the remote file, or null when it does not exist. */
    fun remoteSize(path: String): Long?

    /** Streams [path] from [offset] into [sink]; reports the absolute byte count reached. */
    fun download(
        path: String,
        offset: Long,
        sink: OutputStream,
        cancelled: AtomicBoolean,
        onProgress: (Long) -> Unit,
    )

    /** Streams [source] (already positioned at [offset]) to [path], writing from [offset]. */
    fun upload(
        source: InputStream,
        path: String,
        offset: Long,
        cancelled: AtomicBoolean,
        onProgress: (Long) -> Unit,
    )
}

/** sshj implementation: offset reads via read-ahead, offset writes via WRITE|CREAT without TRUNC. */
class SshjTransport(private val sftp: SFTPClient, private val chunk: Int = CHUNK) : TransferTransport {

    override fun remoteSize(path: String): Long? = sftp.statExistence(path)?.size

    override fun download(
        path: String,
        offset: Long,
        sink: OutputStream,
        cancelled: AtomicBoolean,
        onProgress: (Long) -> Unit,
    ) {
        sftp.open(path, EnumSet.of(OpenMode.READ)).use { rf ->
            rf.ReadAheadRemoteFileInputStream(READ_AHEAD, offset).use { input ->
                pump(input, sink, offset, cancelled, onProgress)
            }
        }
    }

    override fun upload(
        source: InputStream,
        path: String,
        offset: Long,
        cancelled: AtomicBoolean,
        onProgress: (Long) -> Unit,
    ) {
        val modes = if (offset > 0) {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
        } else {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }
        sftp.open(path, modes).use { rf ->
            rf.RemoteFileOutputStream(offset, WRITE_AHEAD).use { out ->
                pump(source, out, offset, cancelled, onProgress)
            }
        }
    }

    private fun pump(
        input: InputStream,
        out: OutputStream,
        start: Long,
        cancelled: AtomicBoolean,
        onProgress: (Long) -> Unit,
    ) {
        val buf = ByteArray(chunk)
        var done = start
        while (true) {
            if (cancelled.get()) {
                out.flush()
                throw TransferCancelled(done)
            }
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            onProgress(done)
        }
        out.flush()
    }

    companion object {
        const val CHUNK = 256 * 1024
        const val READ_AHEAD = 16
        const val WRITE_AHEAD = 16
    }
}
