package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MonitorTab poll-loop policy (iOS LogAndContinue "parse failure leaves
 * prior snapshot usable" parity): pure MonitorPoll.reduce pins.
 */
class MonitorPollTest {

    private val goodOut = """
        ---CPU
        cpu  100 0 100 800 0 0 0 0 0 0
        cpu  200 0 100 800 0 0 0 0 0 0
        ---MEM
        Mem:        100 50 0 0 0 0
        ---DISK
        /dev/root 100 50 0 50% /
        ---LOAD
        0.10 0.20 0.30 1/100 1
        ---UP
        100.00 200.00
    """.trimIndent()

    @Test
    fun `good output refreshes the snapshot and clears the error`() {
        val next = MonitorPoll.reduce(MonitorPoll.State(null, "Failed to read metrics"), goodOut)
        assertNotNull(next.snapshot)
        assertNull(next.error)
    }

    @Test
    fun `parse failure preserves the prior snapshot`() {
        val prior = MonitorParser.parse(goodOut)!!
        val next = MonitorPoll.reduce(MonitorPoll.State(prior, null), "garbage from a dying shell")
        assertEquals(prior, next.snapshot)
        assertNull(next.error)
    }

    @Test
    fun `parse failure with no prior snapshot surfaces the error`() {
        val next = MonitorPoll.reduce(MonitorPoll.State(null, null), "garbage")
        assertNull(next.snapshot)
        assertEquals("Failed to read metrics", next.error)
    }

    @Test
    fun `dead exec preserves the prior snapshot`() {
        val prior = MonitorParser.parse(goodOut)!!
        val next = MonitorPoll.reduce(MonitorPoll.State(prior, null), null)
        assertEquals(prior, next.snapshot)
        assertEquals(null, next.error)
    }

    @Test
    fun `dead exec with no prior snapshot surfaces the error`() {
        val next = MonitorPoll.reduce(MonitorPoll.State(null, null), null)
        assertEquals("Failed to read metrics", next.error)
    }
}
