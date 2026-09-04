package io.github.judegibatron.phoneagent.tools

import android.app.Notification
import io.github.judegibatron.phoneagent.access.AgentNotificationListener
import io.github.judegibatron.phoneagent.util.Apps
import io.github.judegibatron.phoneagent.util.Fuzzy
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReadNotificationsTool : AgentTool(
    ToolSpec(
        name = "read_notifications",
        description = "List the notifications currently in the shade (app, title, text, time), newest first. Requires notification access.",
        properties = mapOf(
            "limit" to prop("integer", "Maximum items, default 12"),
            "app" to prop("string", "Optional app name to filter by"),
        ),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val c = ctx.context
        val listener = AgentNotificationListener.instance
            ?: return ToolOutput.error(
                if (AgentNotificationListener.isEnabled(c)) "The notification listener is not connected yet; try again in a moment."
                else "Notification access is not granted; the user can enable it in Phone Agent setup.",
            )
        var items = listener.snapshot()
            .filter { it.packageName != c.packageName && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .sortedByDescending { it.postTime }
        args.str("app")?.let { filter ->
            items = items.filter {
                it.packageName.contains(filter, ignoreCase = true) || Fuzzy.score(filter, Apps.label(c, it.packageName)) >= 0.6
            }
        }
        if (items.isEmpty()) return ToolOutput.text("No notifications.")
        val limit = (args.int("limit") ?: 12).coerceIn(1, 40)
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
        val lines = items.take(limit).map { sbn ->
            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            val body = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim()?.take(240)
            val time = Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault()).format(timeFormat)
            buildString {
                append("- [").append(time).append("] ").append(Apps.label(c, sbn.packageName)).append(": ")
                if (!title.isNullOrEmpty()) append(title)
                if (!title.isNullOrEmpty() && !body.isNullOrEmpty()) append(" - ")
                if (!body.isNullOrEmpty()) append(body.replace('\n', ' '))
            }
        }
        return ToolOutput.text(lines.joinToString("\n") + if (items.size > limit) "\n(${items.size - limit} more not shown)" else "")
    }
}
