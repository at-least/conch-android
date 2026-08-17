package at.least.conch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses `docker ps -a --format {{json .}}` output lines. Pure Kotlin.
 */
object DockerParser {

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
