package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ExtraKeysConfig (iOS ExtraKeysAndThemeTests parity, adapted): default
 * row pin, pure parse/serialize round-trip, unknown-id filtering, xterm
 * byte contracts for the control keys, CTRL-as-toggle.
 *
 * Known divergence pinned deliberately: iOS's default row is
 * CTRL,ESC,TAB,LEFT,UP,DOWN,RIGHT,CTRLC,PGUP,PGDN and its pool holds NO
 * printable symbols (C48); Android still ships SLASH/PIPE/DASH in the
 * default row and printable keys in the pool — a recorded design
 * candidate (PLAN Notes), not an accident. When that decision lands,
 * these pins move with it.
 */
class ExtraKeysConfigTest {

    @Test
    fun `default row is pinned`() {
        assertEquals(
            listOf("CTRL", "ESC", "TAB", "LEFT", "UP", "DOWN", "RIGHT", "SLASH", "PIPE", "DASH"),
            ExtraKeysConfig.DEFAULT,
        )
    }

    @Test
    fun `serialize parse round-trips a custom row in order`() {
        val ids = listOf("ESC", "PGUP", "TAB")
        assertEquals(ids, ExtraKeysConfig.parse(ExtraKeysConfig.serialize(ids)))
    }

    @Test
    fun `parse drops unknown ids and keeps order`() {
        assertEquals(
            listOf("ESC", "TAB"),
            ExtraKeysConfig.parse("""["ESC","NOPE","TAB","LEGACY_SLASH"]"""),
        )
    }

    @Test
    fun `parse falls back to default on null, corrupt, empty, or all-unknown input`() {
        assertEquals(ExtraKeysConfig.DEFAULT, ExtraKeysConfig.parse(null))
        assertEquals(ExtraKeysConfig.DEFAULT, ExtraKeysConfig.parse("not json"))
        assertEquals(ExtraKeysConfig.DEFAULT, ExtraKeysConfig.parse("[]"))
        assertEquals(ExtraKeysConfig.DEFAULT, ExtraKeysConfig.parse("""["XX","YY"]"""))
    }

    @Test
    fun `control keys emit xterm standard bytes`() {
        // same contracts iOS InteractionStringTests pins
        assertEquals("\u001b[A", String(ExtraKeysConfig.bytesFor("UP")!!))
        assertEquals("\u001b[B", String(ExtraKeysConfig.bytesFor("DOWN")!!))
        assertEquals("\u001b[C", String(ExtraKeysConfig.bytesFor("RIGHT")!!))
        assertEquals("\u001b[D", String(ExtraKeysConfig.bytesFor("LEFT")!!))
        assertEquals("\u001b[5~", String(ExtraKeysConfig.bytesFor("PGUP")!!))
        assertEquals("\u001b[6~", String(ExtraKeysConfig.bytesFor("PGDN")!!))
        assertEquals("\u001b[H", String(ExtraKeysConfig.bytesFor("HOME")!!))
        assertEquals("\u001b[F", String(ExtraKeysConfig.bytesFor("END")!!))
        assertEquals("\u001b[3~", String(ExtraKeysConfig.bytesFor("DEL")!!))
        assertEquals("\u001b", String(ExtraKeysConfig.bytesFor("ESC")!!))
        assertEquals("\t", String(ExtraKeysConfig.bytesFor("TAB")!!))
    }

    @Test
    fun `CTRL emits no bytes because it is a latch toggle`() {
        assertNull(ExtraKeysConfig.bytesFor("CTRL"))
    }

    @Test
    fun `printable symbol keys emit exactly their character (pool pending C48 decision)`() {
        assertEquals("/", String(ExtraKeysConfig.bytesFor("SLASH")!!))
        assertEquals("|", String(ExtraKeysConfig.bytesFor("PIPE")!!))
        assertEquals("-", String(ExtraKeysConfig.bytesFor("DASH")!!))
        assertEquals("~", String(ExtraKeysConfig.bytesFor("TILDE")!!))
    }

    @Test
    fun `labelFor maps known ids and falls back to the id itself`() {
        assertEquals("↑", ExtraKeysConfig.labelFor("UP"))
        assertEquals("NOPE", ExtraKeysConfig.labelFor("NOPE"))
    }
}
