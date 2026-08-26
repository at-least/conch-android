package at.least.conch

/**
 * Minimal OpenSSH client config parser: understands `Host` blocks and the
 * HostName / User / Port / IdentityFile directives (case-insensitive).
 * Wildcard-only Host entries (e.g. `Host *`) are skipped; entries whose alias
 * contains wildcards use the first non-wildcard token, if any.
 */
object OpenSshConfigParser {

    data class ParsedHost(
        val alias: String,
        var hostname: String = "",
        var user: String = "",
        var port: Int = 22,
        /** IdentityFile path as written (tilde not expanded). No key is
         * loaded — surfaced so the import UI can point the user at the key
         * manager. */
        var identityFile: String = "",
    )

    fun parse(text: String): List<ParsedHost> {
        val hosts = mutableListOf<ParsedHost>()
        var current: ParsedHost? = null

        for (rawLine in text.lines()) {
            val line = rawLine.substringBefore('#').trim()
            val key = line.substringBefore(' ').lowercase()
            val value = line.substringAfter(' ', "").trim()
            // also covers blank/comment-only lines: their value is empty
            if (value.isEmpty()) continue
            when (key) {
                "host" -> {
                    current = null
                    val tokens = value.split(Regex("\\s+"))
                    val alias = tokens.firstOrNull { it.none { c -> c == '*' || c == '?' || c == '!' } }
                    if (alias != null) {
                        current = ParsedHost(alias = alias)
                        hosts.add(current)
                    }
                }
                "hostname" -> current?.hostname = value
                "user" -> current?.user = value
                "port" -> current?.port = value.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
                "identityfile" -> current?.identityFile = value.trim('"')
                else -> { /* ignored directive */ }
            }
        }
        return hosts
    }
}
