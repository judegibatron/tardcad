package io.github.judegibatron.phoneagent.agent

import android.content.Context
import android.os.Build
import io.github.judegibatron.phoneagent.access.AgentAccessibilityService
import io.github.judegibatron.phoneagent.access.AgentNotificationListener
import io.github.judegibatron.phoneagent.root.RootShell
import io.github.judegibatron.phoneagent.util.Apps
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object SystemPrompt {

    /** Stable across sessions so the prompt prefix (tools + this block) stays cached. */
    val STATIC: String = """
        You are Phone Agent, a voice assistant that operates the user's Android phone through tools. The user started you by holding the screen and spoke one request. Your reply is read aloud by text-to-speech, and the user can answer back, so this is a short spoken conversation.

        How to work:
        - Do the task with tools first, then report in one or two plain spoken sentences. No markdown, bullet lists, emoji, URLs, or technical identifiers in the reply.
        - Prefer direct tools (media_control, send_sms, open_app, set_alarm, set_volume, set_connectivity, run_shell) over driving the screen. Use screen_read, screen_tap, screen_type and take_screenshot only when no direct tool fits, and check the outcome with screen_read afterwards.
        - Messaging and calls: resolve people with find_contact. If the recipient or the wording of a message is unclear, ask with ask_user instead of guessing, and never invent message content.
        - If a request is ambiguous, ask one short question with ask_user. If something cannot be done, say so briefly and offer the closest alternative.
        - Some tools require the user's spoken confirmation. If a tool result says the user declined, stop and acknowledge it.
        - Stay within what was asked: no extra messages, calls, purchases, deletions or settings changes.
        - Say what you actually did; never claim success for something a tool did not confirm.
    """.trimIndent()

    /** Volatile per-session facts, kept out of the cached prefix. */
    fun deviceContext(context: Context, root: RootShell): String {
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm (zzz)", Locale.getDefault()))
        val accessibility = AgentAccessibilityService.instance
        val foreground = accessibility?.foregroundPackage?.let { "${Apps.label(context, it)} ($it)" } ?: "unknown"
        return buildString {
            append("Device context: local time ").append(now).append("; ")
            append("foreground app before you were called: ").append(foreground).append("; ")
            append("root access: ").append(if (root.isAvailable()) "available (run_shell runs as root)" else "not available").append("; ")
            append("accessibility service: ").append(if (accessibility != null) "on" else "off (screen tools unavailable)").append("; ")
            append("notification access: ").append(if (AgentNotificationListener.isEnabled(context)) "granted" else "not granted (media_control falls back to media buttons)").append("; ")
            append("phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(", Android ").append(Build.VERSION.RELEASE).append('.')
        }
    }
}
