package io.github.judegibatron.phoneagent.tools

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import io.github.judegibatron.phoneagent.util.Apps
import io.github.judegibatron.phoneagent.util.Fuzzy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

class OpenAppTool : AgentTool(
    ToolSpec(
        name = "open_app",
        description = "Open an installed app by name (fuzzy matched against installed app labels).",
        properties = mapOf("name" to prop("string", "App name as the user said it, e.g. Spotify, Messages, camera")),
        required = listOf("name"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val name = args.str("name") ?: return ToolOutput.error("name is required")
        val matches = withContext(Dispatchers.IO) { Apps.match(ctx.context, name) }
        if (matches.isEmpty()) {
            return ToolOutput.error("No installed app matches '$name'. Use list_apps to see what is installed.")
        }
        val (best, score) = matches[0]
        val ambiguous = matches.size > 1 && score < 0.9 && score - matches[1].second < 0.1
        if (ambiguous) {
            return ToolOutput.text(
                "Several apps match '$name': ${matches.take(5).joinToString { it.first.label }}. Ask the user which one, or call open_app with the exact label.",
            )
        }
        val launched = withContext(Dispatchers.IO) { Apps.launch(ctx.context, ctx.root, best.packageName) }
        if (!launched) return ToolOutput.error("Could not launch ${best.label} (${best.packageName}).")
        delay(700)
        return ToolOutput.text("Opened ${best.label} (${best.packageName}).")
    }
}

class ListAppsTool : AgentTool(
    ToolSpec(
        name = "list_apps",
        description = "List installed apps that have a launcher icon, optionally filtered by a substring.",
        properties = mapOf("filter" to prop("string", "Optional text to filter app names by")),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val filter = args.str("filter")
        var apps = Apps.launchable(ctx.context)
        if (filter != null) {
            apps = apps.filter {
                it.label.contains(filter, ignoreCase = true) || it.packageName.contains(filter, ignoreCase = true) ||
                    Fuzzy.score(filter, it.label) >= 0.7
            }
        }
        if (apps.isEmpty()) ToolOutput.text("No apps match '$filter'.")
        else ToolOutput.text(
            apps.take(80).joinToString("\n") { "${it.label} (${it.packageName})" } +
                if (apps.size > 80) "\n... and ${apps.size - 80} more" else "",
        )
    }
}

class OpenUrlTool : AgentTool(
    ToolSpec(
        name = "open_url",
        description = "Open a web URL (or any deep link such as spotify:..., tel:, mailto:) in the default handler.",
        properties = mapOf("url" to prop("string", "The URL or URI to open")),
        required = listOf("url"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        var url = args.str("url") ?: return ToolOutput.error("url is required")
        if (!url.contains(":")) url = "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.context.startActivity(intent)
            delay(500)
            ToolOutput.text("Opened $url.")
        } catch (e: ActivityNotFoundException) {
            ToolOutput.error("Nothing on the phone can open $url.")
        }
    }
}

class NavigateTool : AgentTool(
    ToolSpec(
        name = "navigate",
        description = "Start turn-by-turn navigation to a place or address in the maps app.",
        properties = mapOf(
            "destination" to prop("string", "Place name or address"),
            "mode" to prop("string", "Travel mode; defaults to driving", listOf("driving", "walking", "bicycling", "transit")),
        ),
        required = listOf("destination"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val destination = args.str("destination") ?: return ToolOutput.error("destination is required")
        val mode = when (args.str("mode")) {
            "walking" -> "w"
            "bicycling" -> "b"
            "transit" -> "t"
            else -> "d"
        }
        val encoded = Uri.encode(destination)
        val attempts = listOf("google.navigation:q=$encoded&mode=$mode", "geo:0,0?q=$encoded")
        for (uri in attempts) {
            try {
                ctx.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(500)
                return ToolOutput.text("Started navigation to $destination.", suppressFollowUp = true)
            } catch (e: ActivityNotFoundException) {
                // try the next URI form
            }
        }
        return ToolOutput.error("No maps app is installed.")
    }
}
