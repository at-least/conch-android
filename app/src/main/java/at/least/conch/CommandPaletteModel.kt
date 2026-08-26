package at.least.conch

/**
 * C50/C52 command palette — pure filter/rank logic, Android port of the iOS
 * `CommandPaletteModel`. No UI, no IO: unit-testable on the JVM. The palette
 * searches history + snippets, ranks prefix > substring, snippets win ties
 * (curated). Empty query: recent history first (newest wins), capped.
 */
object CommandPaletteModel {

    enum class Origin { HISTORY, SNIPPET }

    data class Entry(
        val origin: Origin,
        val text: String,
        val label: String? = null,
        // Position within the result list. History only dedups consecutive
        // repeats, so the same text can appear twice; LazyColumn keys must
        // stay unique or Compose throws at composition time.
        val ordinal: Int = 0,
    ) {
        val id: String get() = "${if (origin == Origin.HISTORY) "h" else "s"}:$ordinal"
    }

    fun filter(
        query: String,
        history: List<String>,
        snippets: List<Pair<String, String>>, // (label, command)
        limit: Int = 50,
    ): List<Entry> {
        val q = query.trim().lowercase()
        val snippetEntries = snippets.map { Entry(Origin.SNIPPET, it.second, it.first) }

        if (q.isEmpty()) {
            return history.takeLast(limit).reversed()
                .mapIndexed { i, text -> Entry(Origin.HISTORY, text, ordinal = i) }
        }

        fun score(text: String): Int? {
            val t = text.lowercase()
            return when {
                t.startsWith(q) -> 0
                t.contains(q) -> 1
                else -> null
            }
        }

        data class Hit(val score: Int, val tie: Int, val entry: Entry)
        val hits = mutableListOf<Hit>()
        for (e in snippetEntries) {
            val s = score(e.text) ?: e.label?.let { score(it) }
            if (s != null) hits.add(Hit(s, 0, e))
        }
        for ((i, text) in history.withIndex()) {
            val s = score(text)
            if (s != null) hits.add(Hit(s, i, Entry(Origin.HISTORY, text)))
        }
        return hits
            .sortedWith(compareBy({ it.score }, { it.tie }))
            .take(limit)
            .mapIndexed { i, hit -> hit.entry.copy(ordinal = i) }
    }
}
