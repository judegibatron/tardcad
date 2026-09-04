package io.github.judegibatron.phoneagent.tools

import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import io.github.judegibatron.phoneagent.access.AgentNotificationListener
import io.github.judegibatron.phoneagent.util.Apps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

class GetDeviceStateTool : AgentTool(
    ToolSpec(
        name = "get_device_state",
        description = "Snapshot of the phone: time, battery, connectivity, volumes, do-not-disturb, brightness, " +
            "foreground app, lock state, active media, and which capabilities are available (root, notification access, accessibility).",
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val c = ctx.context
        val sb = StringBuilder()
        sb.appendLine("time: " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm zzz", Locale.getDefault())))
        val battery = c.getSystemService(BatteryManager::class.java)
        sb.appendLine(
            "battery: ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" +
                if (battery.isCharging) " (charging)" else "",
        )
        val audio = c.getSystemService(AudioManager::class.java)
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        sb.appendLine(
            "ringer: $ringer; volumes: media ${percent(audio, AudioManager.STREAM_MUSIC)}%, " +
                "ring ${percent(audio, AudioManager.STREAM_RING)}%, alarm ${percent(audio, AudioManager.STREAM_ALARM)}%, " +
                "notification ${percent(audio, AudioManager.STREAM_NOTIFICATION)}%",
        )
        val notifications = c.getSystemService(NotificationManager::class.java)
        val filter = notifications.currentInterruptionFilter
        val dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        sb.appendLine("do not disturb: ${if (dnd) "on" else "off"}")
        val wifi = runCatching { c.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrNull()
        val bluetooth = runCatching { c.getSystemService(BluetoothManager::class.java).adapter?.isEnabled }.getOrNull()
        sb.appendLine("wifi: ${onOff(wifi)}; bluetooth: ${onOff(bluetooth)}")
        val brightness = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        val adaptive = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        sb.appendLine(
            "brightness: ${if (brightness >= 0) "${brightness * 100 / 255}%" else "unknown"}" +
                if (adaptive) " (adaptive)" else "",
        )
        sb.appendLine("screen locked: ${c.getSystemService(KeyguardManager::class.java).isKeyguardLocked}")
        val foreground = ctx.accessibility?.foregroundPackage
        sb.appendLine(
            "foreground app: " + (foreground?.let { "${Apps.label(c, it)} ($it)" } ?: "unknown (accessibility service off)"),
        )
        sb.appendLine("media: " + MediaSessions.summary(c))
        sb.appendLine(
            "capabilities: root=${ctx.root.isAvailable()}, notification_access=${AgentNotificationListener.isEnabled(c)}, " +
                "accessibility=${ctx.accessibility != null}",
        )
        sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        ToolOutput.text(sb.toString().trim())
    }

    private fun percent(audio: AudioManager, stream: Int): Int {
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return audio.getStreamVolume(stream) * 100 / max
    }

    private fun onOff(value: Boolean?): String = when (value) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }
}

class SetVolumeTool : AgentTool(
    ToolSpec(
        name = "set_volume",
        description = "Set a volume stream to a percentage 0-100. Streams: media (default: music, videos, audiobooks), ring, alarm, notification, call.",
        properties = mapOf(
            "percent" to prop("integer", "Target volume from 0 to 100"),
            "stream" to prop("string", "Which stream to change; defaults to media", listOf("media", "ring", "alarm", "notification", "call")),
        ),
        required = listOf("percent"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val percent = (args.int("percent") ?: return ToolOutput.error("percent is required")).coerceIn(0, 100)
        val streamName = args.str("stream") ?: "media"
        val stream = when (streamName) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val audio = ctx.context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(stream)
        val index = (percent * max / 100.0).roundToInt().coerceIn(0, max)
        return try {
            audio.setStreamVolume(stream, index, AudioManager.FLAG_SHOW_UI)
            ToolOutput.text("Set $streamName volume to $percent% ($index of $max).")
        } catch (e: SecurityException) {
            if (ctx.root.isAvailable() && ctx.root.run("cmd media_session volume --stream $stream --set $index").ok) {
                ToolOutput.text("Set $streamName volume to $percent% (via root).")
            } else {
                ToolOutput.error("Volume change was blocked by the do-not-disturb policy: ${e.message}")
            }
        }
    }
}

class SetBrightnessTool : AgentTool(
    ToolSpec(
        name = "set_brightness",
        description = "Set screen brightness to a percentage 0-100, or turn adaptive (automatic) brightness on.",
        properties = mapOf(
            "percent" to prop("integer", "Brightness 0-100"),
            "adaptive" to prop("boolean", "true to enable automatic brightness instead of a fixed level"),
        ),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val c = ctx.context
        val adaptive = args.bool("adaptive") == true
        val percent = args.int("percent")?.coerceIn(0, 100)
        if (!adaptive && percent == null) return@withContext ToolOutput.error("Give percent or adaptive=true.")
        val value = ((percent ?: 0) * 255 / 100.0).roundToInt()
        val done = if (adaptive) "Adaptive brightness is on." else "Brightness set to $percent%."
        when {
            Settings.System.canWrite(c) -> {
                Settings.System.putInt(
                    c.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (adaptive) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                if (!adaptive) Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
                ToolOutput.text(done)
            }
            ctx.root.isAvailable() -> {
                val cmd = if (adaptive) {
                    "settings put system screen_brightness_mode 1"
                } else {
                    "settings put system screen_brightness_mode 0 && settings put system screen_brightness $value"
                }
                val r = ctx.root.run(cmd)
                if (r.ok) ToolOutput.text(done) else ToolOutput.error("Root brightness command failed: ${r.combined()}")
            }
            else -> ToolOutput.error("Missing the 'Modify system settings' permission; the user can grant it in Phone Agent setup.")
        }
    }
}

class FlashlightTool : AgentTool(
    ToolSpec(
        name = "flashlight",
        description = "Turn the flashlight (camera torch) on or off.",
        properties = mapOf("on" to prop("boolean", "true for on, false for off")),
        required = listOf("on"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val on = args.bool("on") ?: return ToolOutput.error("on is required")
        val cameras = ctx.context.getSystemService(CameraManager::class.java)
        val withFlash = cameras.cameraIdList.filter { id ->
            cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        val id = withFlash.firstOrNull { id ->
            cameras.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull() ?: return ToolOutput.error("This phone has no flash unit.")
        cameras.setTorchMode(id, on)
        return ToolOutput.text("Flashlight ${if (on) "on" else "off"}.")
    }
}

class DoNotDisturbTool : AgentTool(
    ToolSpec(
        name = "set_do_not_disturb",
        description = "Turn Do Not Disturb on (priority interruptions only) or off.",
        properties = mapOf("on" to prop("boolean", "true for on, false for off")),
        required = listOf("on"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val on = args.bool("on") ?: return@withContext ToolOutput.error("on is required")
        val nm = ctx.context.getSystemService(NotificationManager::class.java)
        when {
            nm.isNotificationPolicyAccessGranted -> {
                nm.setInterruptionFilter(
                    if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
                )
                ToolOutput.text("Do Not Disturb ${if (on) "on" else "off"}.")
            }
            ctx.root.isAvailable() -> {
                val r = ctx.root.run("cmd notification set_dnd ${if (on) "priority" else "off"}")
                if (r.ok) ToolOutput.text("Do Not Disturb ${if (on) "on" else "off"} (via root).")
                else ToolOutput.error("Root DND command failed: ${r.combined()}")
            }
            else -> ToolOutput.error("Do Not Disturb access is not granted; the user can grant it in Phone Agent setup.")
        }
    }
}

class ConnectivityTool : AgentTool(
    ToolSpec(
        name = "set_connectivity",
        description = "Turn Wi-Fi, Bluetooth, mobile data or airplane mode on or off. Needs root; without root it opens the matching settings panel so the user can flip the switch.",
        properties = mapOf(
            "feature" to prop("string", "Which radio to change", listOf("wifi", "bluetooth", "mobile_data", "airplane_mode")),
            "on" to prop("boolean", "true for on, false for off"),
        ),
        required = listOf("feature", "on"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val feature = args.str("feature") ?: return@withContext ToolOutput.error("feature is required")
        val on = args.bool("on") ?: return@withContext ToolOutput.error("on is required")
        val verb = if (on) "enable" else "disable"
        val pretty = feature.replace('_', ' ')
        if (ctx.root.isAvailable()) {
            val commands = when (feature) {
                "wifi" -> listOf("svc wifi $verb")
                "bluetooth" -> listOf("svc bluetooth $verb", "cmd bluetooth_manager $verb")
                "mobile_data" -> listOf("svc data $verb")
                "airplane_mode" -> listOf(
                    "cmd connectivity airplane-mode $verb",
                    "settings put global airplane_mode_on ${if (on) 1 else 0} && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on",
                )
                else -> return@withContext ToolOutput.error("Unknown feature '$feature'.")
            }
            var lastError = ""
            for (cmd in commands) {
                val r = ctx.root.run(cmd)
                if (r.ok) return@withContext ToolOutput.text("$pretty turned ${if (on) "on" else "off"}.")
                lastError = r.combined()
            }
            return@withContext ToolOutput.error("Root command for $pretty failed: $lastError")
        }
        val intent = when (feature) {
            "wifi" -> Intent(Settings.Panel.ACTION_WIFI)
            "mobile_data", "airplane_mode" -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            else -> return@withContext ToolOutput.error("Unknown feature '$feature'.")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.context.startActivity(intent)
            ToolOutput.text("No root access, so I opened the $pretty settings panel; the user needs to flip the switch themselves.")
        } catch (e: ActivityNotFoundException) {
            ToolOutput.error("No settings panel available for $pretty and no root access.")
        }
    }
}

class SetAlarmTool : AgentTool(
    ToolSpec(
        name = "set_alarm",
        description = "Create an alarm in the clock app.",
        properties = mapOf(
            "hour" to prop("integer", "Hour 0-23"),
            "minute" to prop("integer", "Minute 0-59"),
            "label" to prop("string", "Optional alarm label"),
            "days" to mapOf(
                "type" to "array",
                "description" to "Optional weekdays to repeat on",
                "items" to mapOf("type" to "string", "enum" to listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")),
            ),
        ),
        required = listOf("hour", "minute"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val hour = args.int("hour")?.takeIf { it in 0..23 } ?: return ToolOutput.error("hour must be 0-23")
        val minute = args.int("minute")?.takeIf { it in 0..59 } ?: return ToolOutput.error("minute must be 0-59")
        val label = args.str("label")
        val days = args.strList("days").mapNotNull { DAY_MAP[it.lowercase(Locale.ROOT).take(3)] }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.context.startActivity(intent)
            val time = String.format(Locale.US, "%02d:%02d", hour, minute)
            ToolOutput.text("Alarm set for $time${if (label != null) " labelled '$label'" else ""}${if (days.isNotEmpty()) " repeating on ${args.strList("days").joinToString()}" else ""}.")
        } catch (e: ActivityNotFoundException) {
            ToolOutput.error("No clock app on this phone handles alarms.")
        }
    }

    private companion object {
        val DAY_MAP = mapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
        )
    }
}

class SetTimerTool : AgentTool(
    ToolSpec(
        name = "set_timer",
        description = "Start a countdown timer in the clock app.",
        properties = mapOf(
            "seconds" to prop("integer", "Duration in seconds"),
            "label" to prop("string", "Optional label"),
        ),
        required = listOf("seconds"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val seconds = args.int("seconds")?.takeIf { it > 0 } ?: return ToolOutput.error("seconds must be positive")
        val label = args.str("label")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.context.startActivity(intent)
            ToolOutput.text("Timer started for ${describe(seconds)}${if (label != null) " ('$label')" else ""}.")
        } catch (e: ActivityNotFoundException) {
            ToolOutput.error("No clock app on this phone handles timers.")
        }
    }

    private fun describe(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h h" else null,
            if (m > 0) "$m min" else null,
            if (s > 0 || (h == 0 && m == 0)) "$s s" else null,
        ).joinToString(" ")
    }
}

class SetClipboardTool : AgentTool(
    ToolSpec(
        name = "set_clipboard",
        description = "Put text on the clipboard so the user (or screen_type) can paste it.",
        properties = mapOf("text" to prop("string", "Text to copy")),
        required = listOf("text"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val text = args.str("text") ?: return ToolOutput.error("text is required")
        ctx.context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Phone Agent", text))
        return ToolOutput.text("Copied ${text.length} characters to the clipboard.")
    }
}

class WaitTool : AgentTool(
    ToolSpec(
        name = "wait",
        description = "Pause for up to 10 seconds, e.g. to let an app finish loading before screen_read.",
        properties = mapOf("seconds" to prop("number", "Seconds to wait (0.5-10)")),
        required = listOf("seconds"),
        timeoutMs = 15_000,
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val seconds = (args.dbl("seconds") ?: 1.0).coerceIn(0.5, 10.0)
        delay((seconds * 1000).toLong())
        return ToolOutput.text("Waited $seconds seconds.")
    }
}
