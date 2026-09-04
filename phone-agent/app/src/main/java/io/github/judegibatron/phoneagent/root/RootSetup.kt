package io.github.judegibatron.phoneagent.root

import android.content.ComponentName
import android.content.Context
import android.os.Build
import io.github.judegibatron.phoneagent.access.AgentAccessibilityService
import io.github.judegibatron.phoneagent.access.AgentNotificationListener
import io.github.judegibatron.phoneagent.assist.AgentVoiceInteractionService
import io.github.judegibatron.phoneagent.core.AgentLog

/** One-tap privilege setup for rooted phones: grants everything the app needs without touching Settings. */
object RootSetup {

    fun grantEverything(context: Context, root: RootShell): String {
        val pkg = context.packageName
        val accessibility = ComponentName(context, AgentAccessibilityService::class.java).flattenToString()
        val listener = ComponentName(context, AgentNotificationListener::class.java).flattenToString()

        val commands = mutableListOf(
            "pm grant $pkg android.permission.RECORD_AUDIO",
            "pm grant $pkg android.permission.SEND_SMS",
            "pm grant $pkg android.permission.READ_CONTACTS",
            "pm grant $pkg android.permission.CALL_PHONE",
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg WRITE_SETTINGS allow",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd notification allow_listener $listener",
            "cmd notification allow_dnd $pkg",
            "dumpsys deviceidle whitelist +$pkg",
            enableAccessibilityScript(accessibility),
        )
        if (Build.VERSION.SDK_INT >= 31) commands.add(0, "pm grant $pkg android.permission.BLUETOOTH_CONNECT")
        if (Build.VERSION.SDK_INT >= 33) commands.add(0, "pm grant $pkg android.permission.POST_NOTIFICATIONS")

        return runAll(root, commands)
    }

    /** Makes this app the digital assistant so the side-key / corner-swipe gesture opens a session. */
    fun makeDefaultAssistant(context: Context, root: RootShell): String {
        val vis = ComponentName(context, AgentVoiceInteractionService::class.java).flattenToString()
        return runAll(
            root,
            listOf(
                "settings put secure voice_interaction_service $vis",
                "settings put secure assistant $vis",
            ),
        )
    }

    private fun enableAccessibilityScript(component: String): String =
        "cur=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$cur\" in *\"$component\"*) ;; null|\"\") settings put secure enabled_accessibility_services \"$component\";; " +
            "*) settings put secure enabled_accessibility_services \"\$cur:$component\";; esac; " +
            "settings put secure accessibility_enabled 1"

    private fun runAll(root: RootShell, commands: List<String>): String {
        val report = StringBuilder()
        for (cmd in commands) {
            val r = root.run(cmd, 20_000)
            val line = (if (r.ok) "ok   " else "FAIL ") + cmd.take(90) +
                (if (!r.ok && r.combined().isNotBlank()) "\n     ${r.combined().take(160)}" else "")
            report.appendLine(line)
            AgentLog.d("RootSetup", line)
        }
        return report.toString().trimEnd()
    }
}
