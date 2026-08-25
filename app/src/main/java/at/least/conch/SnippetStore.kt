package at.least.conch

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    var label: String = "",
    var command: String = "",
)

/** Wire shape shared by snippets.json and the TILDBAK1 backup payload. */
@Serializable
data class SnippetWire(
    val id: String? = null,
    val label: String = "",
    val command: String = "",
) {
    fun toSnippet(): Snippet = Snippet(
        id = id ?: UUID.randomUUID().toString(),
        label = label,
        command = command,
    )

    companion object {
        fun from(s: Snippet): SnippetWire = SnippetWire(s.id, s.label, s.command)
    }
}

/**
 * Snippet persistence. Primary constructor takes the backing [file] so the
 * store is JVM-testable; the Android constructor resolves filesDir.
 */
class SnippetStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "snippets.json"))

    fun load(): MutableList<Snippet> {
        val list = mutableListOf<Snippet>()
        try {
            if (file.exists()) {
                val wires = ConchJson.decodeFromString(ListSerializer(SnippetWire.serializer()), file.readText())
                list.addAll(wires.map { it.toSnippet() })
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun save(snippets: List<Snippet>) {
        val arr = snippets.map { SnippetWire.from(it) }
        file.writeText(ConchJson.encodeToString(ListSerializer(SnippetWire.serializer()), arr))
    }
}
