package at.least.conch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    var label: String = "",
    var command: String = "",
)

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
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Snippet(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            label = o.optString("label"),
                            command = o.optString("command"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun save(snippets: List<Snippet>) {
        val arr = JSONArray()
        for (s in snippets) {
            arr.put(JSONObject().put("id", s.id).put("label", s.label).put("command", s.command))
        }
        file.writeText(arr.toString())
    }
}
