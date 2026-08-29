package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProxyJump chain rules — outermost-first order, cycle and depth guards,
 * candidate filtering. Same cases as iOS `ProxyJumpResolverTests`.
 */
class ProxyJumpResolverTest {

    private val all = mutableListOf<Host>()

    private fun host(alias: String, jump: String? = null): Host =
        Host(alias = alias, hostname = "$alias.example.com", jumpHostId = jump).also { all.add(it) }

    private fun chainAliases(h: Host) = ProxyJumpResolver.chain(h, all)?.map { it.alias }

    @Test
    fun `direct connection yields an empty chain`() {
        val a = host("a")
        assertEquals(emptyList<String>(), chainAliases(a))
    }

    @Test
    fun `single jump chain is outermost first`() {
        val bastion = host("bastion")
        val web = host("web", jump = bastion.id)
        assertEquals(listOf("bastion"), chainAliases(web))
    }

    @Test
    fun `multi hop chain orders outermost first`() {
        val hop1 = host("bastion1")
        val hop2 = host("bastion2", jump = hop1.id)
        val web = host("web", jump = hop2.id)
        assertEquals(listOf("bastion1", "bastion2"), chainAliases(web))
    }

    @Test
    fun `cycle is rejected`() {
        val a = host("a")
        val b = host("b", jump = a.id)
        a.jumpHostId = b.id
        assertNull(chainAliases(a))
        assertNull(chainAliases(b))
        val r = ProxyJumpResolver.resolve(a, all) as ProxyJumpResolver.Resolution.Broken
        assertEquals(ProxyJumpResolver.Failure.CYCLE, r.failure)
    }

    @Test
    fun `self jump is rejected`() {
        val a = host("a")
        a.jumpHostId = a.id
        assertNull(chainAliases(a))
    }

    @Test
    fun `depth cap rejects long chains`() {
        var previous: Host? = null
        // MAX_JUMPS + 1 hops total → one past the cap
        for (i in 0..ProxyJumpResolver.MAX_JUMPS + 1) previous = host("hop$i", jump = previous?.id)
        assertNull("chains deeper than ${ProxyJumpResolver.MAX_JUMPS} jumps must not resolve", chainAliases(previous!!))
        assertEquals(
            ProxyJumpResolver.Failure.TOO_DEEP,
            (ProxyJumpResolver.resolve(previous, all) as ProxyJumpResolver.Resolution.Broken).failure,
        )
        // exactly MAX_JUMPS resolves
        all.clear()
        previous = null
        for (i in 0..ProxyJumpResolver.MAX_JUMPS) previous = host("hop$i", jump = previous?.id)
        assertEquals(ProxyJumpResolver.MAX_JUMPS, chainAliases(previous!!)!!.size)
    }

    @Test
    fun `dangling reference is rejected`() {
        val a = host("a", jump = "missing-id")
        assertNull(chainAliases(a))
        val r = ProxyJumpResolver.resolve(a, all) as ProxyJumpResolver.Resolution.Broken
        assertEquals(ProxyJumpResolver.Failure.DANGLING, r.failure)
        assertEquals("missing-id", r.atHostId)
    }

    @Test
    fun `candidates exclude self and cycle closers`() {
        val a = host("a")
        host("b", jump = a.id) // b → a: choosing b as a's jump would close a→b→a
        host("c")
        assertEquals(setOf("c"), ProxyJumpResolver.candidates(a, all).map { it.alias }.toSet())
    }

    @Test
    fun `candidates exclude hosts whose own chain is broken`() {
        val a = host("a")
        host("dangling", jump = "missing-id")
        host("ok")
        assertEquals(listOf("ok"), ProxyJumpResolver.candidates(a, all).map { it.alias })
    }

    @Test
    fun `duplicate host ids do not trap — first occurrence wins`() {
        val jump = host("jump")
        val target = host("target", jump = jump.id)
        all.add(jump.copy(alias = "jump-dup"))
        assertEquals(listOf("jump"), chainAliases(target))
    }

    @Test
    fun `route description names every hop for the picker`() {
        val hop1 = host("bastion1")
        val hop2 = host("bastion2", jump = hop1.id)
        val direct = host("direct")
        assertEquals("bastion1 → bastion2", ProxyJumpResolver.describeChain(hop2, all))
        assertNull(ProxyJumpResolver.describeChain(direct, all))
        assertTrue(ProxyJumpResolver.BROKEN_MESSAGE.contains("more than ${ProxyJumpResolver.MAX_JUMPS} hops"))
    }
}
