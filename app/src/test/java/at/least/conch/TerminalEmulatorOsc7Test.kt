package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OSC 7 working-directory tracking (iOS `TerminalBridge.remoteDirectory` parity). */
class TerminalEmulatorOsc7Test {

    private fun newEmu(): TerminalEmulator = TerminalEmulator(20, 5)

    private val esc = "\u001b"
    private val bel = "\u0007"
    private val st = "\u001b\\"

    @Test
    fun `cwd is null until the shell reports one`() {
        assertNull(newEmu().cwd)
    }

    @Test
    fun `OSC 7 file url terminated by BEL sets cwd`() {
        val emu = newEmu()
        emu.feed("$esc]7;file://box/home/me$bel")
        assertEquals("/home/me", emu.cwd)
    }

    @Test
    fun `OSC 7 terminated by ST sets cwd and the listener fires`() {
        val emu = newEmu()
        val seen = mutableListOf<String?>()
        emu.cwdListener = { seen.add(it) }
        emu.feed("$esc]7;file://box/srv/www$st")
        assertEquals("/srv/www", emu.cwd)
        assertEquals(listOf("/srv/www"), seen)
    }

    @Test
    fun `a later cd replaces the earlier directory`() {
        val emu = newEmu()
        emu.feed("$esc]7;file://box/home/me$bel")
        emu.feed("$esc]7;file://box/home/me/proj$bel")
        assertEquals("/home/me/proj", emu.cwd)
    }

    @Test
    fun `OSC 7 does not disturb the title or text output`() {
        val emu = newEmu()
        var title: String? = null
        emu.titleListener = { title = it }
        emu.feed("$esc]2;hello$bel$esc]7;file://box/tmp${bel}ok")
        assertEquals("hello", title)
        assertEquals("/tmp", emu.cwd)
    }
}
