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
        /** ProxyJump target (first hop; multi-hop unsupported). Matched
         * against imported aliases to auto-link jumpHostId. */
        var proxyJump: String = "",
        /** ForwardAgent yes — offered to the user as the host's agent setting. */
        var forwardAgent: Boolean = false,
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
                    current = hostBlockOrNull(value)?.also {
                        hosts.add(it)
                    }
                }
                "hostname" -> current?.hostname = value
                "user" -> current?.user = value
                "port" -> current?.port = value.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
                "identityfile" -> current?.identityFile = value.trim('"')
                "proxyjump" -> current?.proxyJump = firstProxyHop(value)
                "forwardagent" -> current?.forwardAgent = value.equals("yes", ignoreCase = true)
                else -> { /* ignored directive */ }
            }
        }
        return hosts
    }

    /** First non-wildcard alias token, or null for wildcard-only blocks. */
    private fun hostBlockOrNull(value: String): ParsedHost? {
        val tokens = value.split(Regex("\\s+"))
        val alias = tokens.firstOrNull { it.none { c -> c == '*' || c == '?' || c == '!' } }
        return alias?.let { ParsedHost(alias = it) }
    }

    /** Multi-hop ProxyJump lists are unsupported: take the first hop. */
    private fun firstProxyHop(value: String): String =
        value.split(Regex("[\\s,]+")).firstOrNull { it.isNotBlank() } ?: ""
}
