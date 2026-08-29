package at.least.conch

/**
 * Resolves a host's ProxyJump chain, OUTERmost-first — the order
 * [SshConnectionFactory] dials them (phone → jump1 → jump2 → target).
 * Pure, mirrors iOS `ProxyJumpResolver` rule for rule: same depth cap,
 * same cycle/dangling handling, same picker candidates.
 */
object ProxyJumpResolver {

    /**
     * Max hops between phone and target (3 jumps + target is generous for
     * real bastion setups while keeping failure modes bounded).
     */
    const val MAX_JUMPS = 3

    /** Why a chain does not resolve; the user sees one message for all of them (iOS wording). */
    enum class Failure { DANGLING, CYCLE, TOO_DEEP }

    sealed class Resolution {
        /** Jumps to dial before the target, outermost first; empty = direct. */
        data class Chain(val jumps: List<Host>) : Resolution()
        data class Broken(val failure: Failure, val atHostId: String) : Resolution()
    }

    /** What the user sees for every broken chain — same text as iOS. */
    const val BROKEN_MESSAGE =
        "ProxyJump chain is broken (missing jump host, cycle, or more than $MAX_JUMPS hops)"

    fun resolve(host: Host, allHosts: List<Host>): Resolution {
        // Never trap on a duplicate id (a backup listing a host twice):
        // first occurrence wins.
        val byId = HashMap<String, Host>()
        for (h in allHosts) byId.putIfAbsent(h.id, h)
        val chain = ArrayDeque<Host>()
        val seen = hashSetOf(host.id)
        var cursor = host.jumpHostId
        while (cursor != null) {
            val hop = byId[cursor] ?: return Resolution.Broken(Failure.DANGLING, cursor)
            if (!seen.add(hop.id)) return Resolution.Broken(Failure.CYCLE, hop.id)
            chain.addFirst(hop) // prepend → outermost-first
            if (chain.size > MAX_JUMPS) return Resolution.Broken(Failure.TOO_DEEP, hop.id)
            cursor = hop.jumpHostId
        }
        return Resolution.Chain(chain.toList())
    }

    /** Outermost-first jumps; empty = direct; null = unresolvable (see [resolve]). */
    fun chain(host: Host, allHosts: List<Host>): List<Host>? =
        (resolve(host, allHosts) as? Resolution.Chain)?.jumps

    /**
     * Editor picker candidates: every other host with a resolvable chain
     * that does not pass THROUGH [host] (choosing such a candidate would
     * close a cycle).
     */
    fun candidates(host: Host, allHosts: List<Host>): List<Host> =
        allHosts.filter { candidate ->
            candidate.id != host.id &&
                chain(candidate, allHosts)?.none { it.id == host.id } == true
        }

    /** "bastion → dmz" for the picker's supporting text; null when there is no extra hop. */
    fun describeChain(jump: Host, allHosts: List<Host>): String? {
        val upstream = chain(jump, allHosts) ?: return null
        if (upstream.isEmpty()) return null
        return (upstream + jump).joinToString(" → ") { it.alias.ifBlank { it.hostname } }
    }
}
