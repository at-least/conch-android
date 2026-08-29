package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Share-to-host decisions (iOS drag-and-drop parity: `DropUpload.swift`). */
class ShareUploadTest {

    @Test
    fun `OSC 7 url or bare path becomes a remote path`() {
        assertEquals("/home/me", ShareUpload.remotePathFromOsc7("file://box/home/me"))
        assertEquals("/home/me", ShareUpload.remotePathFromOsc7("file:///home/me"))
        assertEquals("/srv/www", ShareUpload.remotePathFromOsc7("/srv/www"))
        assertEquals("/a b/c", ShareUpload.remotePathFromOsc7("file://h/a%20b/c"))
        assertNull(ShareUpload.remotePathFromOsc7(null))
        assertNull(ShareUpload.remotePathFromOsc7(""))
        assertNull(ShareUpload.remotePathFromOsc7("https://example.com/x"))
        assertNull(ShareUpload.remotePathFromOsc7("file://host"))
        assertNull(ShareUpload.remotePathFromOsc7("not a url"))
    }

    @Test
    fun `cwd wins then home then tmp`() {
        assertEquals("/srv", ShareUpload.destinationDir("/srv", "/home/me"))
        assertEquals("/home/me", ShareUpload.destinationDir(null, "/home/me"))
        assertEquals("/home/me", ShareUpload.destinationDir("  ", "/home/me"))
        assertEquals("/tmp", ShareUpload.destinationDir(null, null))
    }

    @Test
    fun `remote path never doubles the separator`() {
        assertEquals("/etc/passwd", ShareUpload.remotePath("/etc", "passwd"))
        assertEquals("/etc/passwd", ShareUpload.remotePath("/etc/", "passwd"))
        assertEquals("/passwd", ShareUpload.remotePath("/", "passwd"))
    }

    @Test
    fun `display name is reduced to one safe path component`() {
        assertEquals("report.pdf", ShareUpload.safeName("report.pdf"))
        assertEquals("a_b c.txt", ShareUpload.safeName("a/b c.txt")) // spaces stay; separators do not
        assertEquals(ShareUpload.FALLBACK_NAME, ShareUpload.safeName(null))
        assertEquals(ShareUpload.FALLBACK_NAME, ShareUpload.safeName("  "))
        assertEquals(ShareUpload.FALLBACK_NAME, ShareUpload.safeName(".."))
    }

    @Test
    fun `collisions get a numbered suffix before the extension`() {
        assertEquals("a.txt", ShareUpload.uniqueName("a.txt", emptySet()))
        assertEquals("a (2).txt", ShareUpload.uniqueName("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", ShareUpload.uniqueName("a.txt", setOf("a.txt", "a (2).txt")))
        assertEquals("Makefile (2)", ShareUpload.uniqueName("Makefile", setOf("Makefile")))
        assertEquals(".env (2)", ShareUpload.uniqueName(".env", setOf(".env")))
    }

    @Test
    fun `summary names one file and counts several`() {
        assertEquals("Nothing uploaded", ShareUpload.summary(emptyList(), "/x"))
        assertEquals("Uploaded a.txt to /home/me", ShareUpload.summary(listOf("a.txt"), "/home/me"))
        assertEquals("Uploaded 3 files to /srv", ShareUpload.summary(listOf("a", "b", "c"), "/srv"))
    }
}
