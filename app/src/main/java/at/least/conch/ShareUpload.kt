package at.least.conch

import java.net.URI

/**
 * Pure decisions behind "share a file to a host" (iOS parity: drag-and-drop
 * onto the terminal, `DropUpload.swift`): where the file lands, what it is
 * called, and what the user is told. Compose- and Android-free so every
 * rule is unit-tested.
 */
object ShareUpload {

    const val FALLBACK_NAME = "upload.bin"

    /**
     * OSC 7 carries a URL (`file://host/path`), not a path (iOS C94: used raw,
     * the upload landed under "file://host/home/me"). A bare absolute path is
     * accepted too; anything else reads as "unknown".
     */
    fun remotePathFromOsc7(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (v.startsWith("/")) return v
        val uri = runCatching { URI(v) }.getOrNull() ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        return uri.path?.takeIf { it.isNotEmpty() }
    }

    /**
     * The shell's tracked cwd wins; an unknown cwd uploads under the remote
     * home (SFTP starts there), never the filesystem root; with neither,
     * `/tmp` — the one directory every host lets us write.
     */
    fun destinationDir(cwd: String?, home: String?): String =
        cwd?.takeIf { it.isNotBlank() }
            ?: home?.takeIf { it.isNotBlank() }
            ?: "/tmp"

    /** `join("/etc", "passwd")` → `/etc/passwd`; a root or trailing-slash dir never doubles the separator. */
    fun remotePath(directory: String, fileName: String): String =
        directory.trimEnd('/') + "/" + fileName

    /**
     * One safe path component from whatever the sharing app called the file:
     * no separators, never `.`/`..`, never blank.
     */
    fun safeName(displayName: String?): String {
        val name = displayName?.trim()?.replace('/', '_').orEmpty()
        return if (name.isEmpty() || name == "." || name == "..") FALLBACK_NAME else name
    }

    /**
     * Collision-safe name: `report.pdf` → `report (2).pdf` → `report (3).pdf`
     * while a same-named file exists. A share must never silently overwrite
     * what is already in the shell's cwd.
     */
    fun uniqueName(name: String, existing: Set<String>): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while ("$stem ($n)$ext" in existing) n++
        return "$stem ($n)$ext"
    }

    /** Snackbar text after a batch: names one file, counts several. */
    fun summary(uploaded: List<String>, directory: String): String = when (uploaded.size) {
        0 -> "Nothing uploaded"
        1 -> "Uploaded ${uploaded[0]} to $directory"
        else -> "Uploaded ${uploaded.size} files to $directory"
    }
}
