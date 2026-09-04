package io.github.judegibatron.phoneagent.tools

import android.content.Context
import io.github.judegibatron.phoneagent.access.AgentAccessibilityService
import io.github.judegibatron.phoneagent.core.Settings
import io.github.judegibatron.phoneagent.root.RootShell
import org.json.JSONObject

/** Static description of a tool: what Claude sees in the `tools` array plus local policy flags. */
data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, Any?> = emptyMap(),
    val required: List<String> = emptyList(),
    /** Ask the user to confirm out loud before running (subject to the per-tool setting). */
    val dangerous: Boolean = false,
    val timeoutMs: Long = 30_000,
)

/** What a tool hands back to the model. Either plain text, or text plus a JPEG (screenshots). */
class ToolOutput private constructor(
    val text: String,
    val isError: Boolean,
    val imageJpegBase64: String?,
    /** True when the tool started audio playback, so the session should not keep listening. */
    val suppressFollowUp: Boolean,
) {
    companion object {
        fun text(text: String, suppressFollowUp: Boolean = false) = ToolOutput(text, false, null, suppressFollowUp)
        fun error(text: String) = ToolOutput(text, true, null, false)
        fun image(text: String, jpegBase64: String) = ToolOutput(text, false, jpegBase64, false)
    }
}

/** Everything a tool may touch while running. Implemented by the live voice session. */
interface ToolContext {
    val context: Context
    val settings: Settings
    val root: RootShell
    val accessibility: AgentAccessibilityService?

    /** Speaks [question], listens for the answer, returns it (null when the user said nothing). */
    suspend fun askUser(question: String): String?

    /** Speaks [prompt] and returns true only for a clear yes. */
    suspend fun confirm(prompt: String): Boolean

    /** Short progress line shown on the session card. */
    fun status(text: String)
}

abstract class AgentTool(val spec: ToolSpec) {
    abstract suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput

    /** Spoken prompt used when this tool needs confirmation. */
    open fun confirmPrompt(args: JSONObject): String = "Run ${spec.name.replace('_', ' ')}?"
}

// ---- JSON-schema and argument helpers ------------------------------------------------------

fun prop(type: String, description: String, enum: List<String>? = null): Map<String, Any?> =
    buildMap<String, Any?> {
        put("type", type)
        put("description", description)
        if (enum != null) put("enum", enum)
    }

fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null

fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null

fun JSONObject.strList(key: String): List<String> {
    if (!has(key) || isNull(key)) return emptyList()
    val arr = optJSONArray(key) ?: return listOfNotNull(str(key))
    return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
}
