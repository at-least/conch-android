package at.least.conch

import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe file replacement shared by the JSON stores: write to a sibling
 * temp file, fsync, then rename over the target. A process death mid-write
 * can no longer leave a truncated store — truncated stores load as empty and
 * are then permanently overwritten by the next save, which is how apps lose
 * user data on a bad power cut.
 */
object AtomicFile {

    fun write(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // Some OEM filesystems fail rename-over-existing; a direct
                // write still beats losing the save entirely.
                file.writeBytes(bytes)
            }
        } finally {
            tmp.delete()
        }
    }

    fun write(file: File, text: String) {
        write(file, text.toByteArray(Charsets.UTF_8))
    }
}
