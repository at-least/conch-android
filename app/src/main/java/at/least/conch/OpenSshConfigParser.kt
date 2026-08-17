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
    )

    fun parse(text: String): List<ParsedHost> {
        val hosts = mutableListOf<ParsedHost>()
        var current: ParsedHost? = null

        for (rawLine in text.lines()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore(' ').lowercase()
            val value = line.substringAfter(' ', "").trim()
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
                else -> { /* ignored directive */ }
            }
        }
        return hosts
    }
}
