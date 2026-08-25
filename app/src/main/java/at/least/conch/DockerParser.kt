package at.least.conch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses `docker ps -a --format {{json .}}` output lines. Pure Kotlin.
 */
object DockerParser {

    /**
     * Wire contract: the container-list command every Docker UI runs.
     * iOS prefixes `export PATH=/opt/homebrew/bin:/usr/local/bin:$PATH;`
     * (C33: non-login SSH PATH omits brew/local bins) — Android does not
     * yet; pending a product decision. Pinned by InteractionStringContractTest.
     */
    const val LIST_COMMAND = "docker ps -a --format '{{json .}}'"

    data class Container(
        val id: String,
        val names: String,
        val image: String,
        val state: String,
        val status: String,
    )

    fun parse(output: String): List<Container> {
        val out = mutableListOf<Container>()
        for (line in output.lines()) {
            val l = line.trim()
            if (l.isEmpty() || !l.startsWith("{")) continue
            val o = runCatching { JSONObject(l) }.getOrNull() ?: continue
            out.add(
                Container(
                    id = o.optString("ID"),
                    names = o.optString("Names"),
                    image = o.optString("Image"),
                    state = o.optString("State"),
                    status = o.optString("Status"),
                )
            )
        }
        return out
    }
}
