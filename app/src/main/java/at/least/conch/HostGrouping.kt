package at.least.conch

/**
 * Pure host-list grouping (iOS `HostGrouping` parity): ungrouped hosts keep
 * their stored order in a leading, untitled section; named groups follow
 * alphabetically, each keeping stored order inside. Extracted from the
 * view for unit tests (same pattern as [HostCardStatus]).
 */
object HostGrouping {
    /** [title] null = the ungrouped leading section. */
    data class Section(val title: String?, val hosts: List<Host>)

    fun sections(hosts: List<Host>): List<Section> {
        val ungrouped = mutableListOf<Host>()
        val byGroup = linkedMapOf<String, MutableList<Host>>()
        for (h in hosts) {
            val name = h.group.trim()
            if (name.isEmpty()) ungrouped.add(h) else byGroup.getOrPut(name) { mutableListOf() }.add(h)
        }
        val out = mutableListOf<Section>()
        if (ungrouped.isNotEmpty()) out.add(Section(null, ungrouped))
        for (name in byGroup.keys.sorted()) out.add(Section(name, byGroup.getValue(name)))
        return out
    }

    /** Distinct existing group names, sorted (for the editor's suggestion picker). */
    fun groupNames(hosts: List<Host>): List<String> =
        hosts.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    /**
     * Search filter (iOS `.searchable` parity): case-insensitive substring
     * over alias, hostname, username and group. Blank query = everything.
     */
    fun filter(hosts: List<Host>, query: String): List<Host> {
        val q = query.trim()
        if (q.isEmpty()) return hosts
        return hosts.filter { h ->
            h.alias.contains(q, ignoreCase = true) ||
                h.hostname.contains(q, ignoreCase = true) ||
                h.username.contains(q, ignoreCase = true) ||
                h.group.contains(q, ignoreCase = true)
        }
    }
}
