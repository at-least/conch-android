package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalFontTest {

    @Test
    fun `persisted ids match iOS raw values and default is the system face`() {
        // The setting is a user-visible choice on both platforms; the ids
        // are pinned so a future shared-settings backup reads identically.
        assertEquals(listOf("system", "jetBrainsMonoNerd"), TerminalFont.ALL.map { it.id })
        assertEquals(TerminalFont.SYSTEM, TerminalFont.DEFAULT)
    }

    @Test
    fun `byId is total — unknown and absent read as the default`() {
        assertEquals(TerminalFont.JETBRAINS_MONO_NERD, TerminalFont.byId("jetBrainsMonoNerd"))
        assertEquals(TerminalFont.SYSTEM, TerminalFont.byId("system"))
        assertEquals(TerminalFont.DEFAULT, TerminalFont.byId(null))
        assertEquals(TerminalFont.DEFAULT, TerminalFont.byId("comic-sans"))
    }
}
