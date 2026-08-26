package at.least.conch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

    /** Docker's capitalized NDJSON keys; every field optional (optString "" semantics). */
    @Serializable
    private data class ContainerWire(
        @SerialName("ID") val id: String = "",
        @SerialName("Names") val names: String = "",
        @SerialName("Image") val image: String = "",
        @SerialName("State") val state: String = "",
        @SerialName("Status") val status: String = "",
    )

    fun parse(output: String): List<Container> =
        output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.startsWith("{") }
            .mapNotNull { line ->
                runCatching { ConchJson.decodeFromString(ContainerWire.serializer(), line) }.getOrNull()
            }
            .map { Container(it.id, it.names, it.image, it.state, it.status) }
}
