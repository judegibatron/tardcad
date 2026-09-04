package io.github.judegibatron.phoneagent.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.access.AgentAccessibilityService
import io.github.judegibatron.phoneagent.access.AgentNotificationListener
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.root.RootSetup
import io.github.judegibatron.phoneagent.session.SessionController
import io.github.judegibatron.phoneagent.trigger.TriggerManager
import io.github.judegibatron.phoneagent.trigger.TriggerService
import java.util.concurrent.Executors

/**
 * Setup dashboard. Everything is built in code (no layout XML) so the whole app type-checks
 * against the platform without resource tooling: a checklist of permissions with one-tap fixes,
 * Claude settings, trigger tuning, safety switches, a manual test entry and the live log.
 */
class MainActivity : Activity() {

    private lateinit var app: PhoneAgentApp
    private val settings get() = app.settings
    private val background = Executors.newSingleThreadExecutor()

    private lateinit var content: LinearLayout
    private lateinit var logView: TextView
    private val statusViews = HashMap<String, TextView>()
    private val logListener: () -> Unit = { runOnUiThread { refreshLog() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = PhoneAgentApp.get(this)
        title = "Phone Agent"
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(32))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        buildStatusSection()
        buildClaudeSection()
        buildTriggerSection()
        buildVoiceSection()
        buildSafetySection()
        buildTrySection()
        buildLogSection()
        if (settings.serviceEnabled) TriggerService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
        AgentLog.addListener(logListener)
        refreshLog()
    }

    override fun onPause() {
        AgentLog.removeListener(logListener)
        super.onPause()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sections

    private fun buildStatusSection() {
        val box = section("Setup checklist")
        box.note(
            "Root gives the 5-second hold trigger and the run_shell tool. Without root the volume-key, " +
                "quick-settings tile and assistant-gesture triggers still work.",
        )
        box.statusRow("root", "Root access (Magisk / KernelSU / APatch)", "Request") {
            background.execute {
                val ok = app.root.isAvailable(refresh = true)
                runOnUiThread {
                    toast(if (ok) "Root granted" else "Root not available; grant Phone Agent in your root manager")
                    refreshStatuses()
                    TriggerManager.sync(this)
                }
            }
        }
        box.statusRow("accessibility", "Accessibility service (screen control, overlay)", "Open") {
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        box.statusRow("notifications", "Notification access (media control, read notifications)", "Open") {
            launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        box.statusRow("overlay", "Draw over other apps (session card fallback)", "Open") {
            launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        box.statusRow("permissions", "Microphone, SMS, contacts, phone, notifications", "Grant") {
            requestPermissions(runtimePermissions(), 1)
        }
        box.statusRow("writeSettings", "Modify system settings (brightness)", "Open") {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        box.statusRow("dnd", "Do Not Disturb access", "Open") {
            launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        box.statusRow("battery", "Ignore battery optimisation (keeps the trigger alive)", "Open") {
            launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        box.statusRow("assistant", "Default digital assistant (side key / corner swipe)", "Open") {
            launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }
        box.statusRow("service", "Hold-to-talk service", "Restart") {
            settings.serviceEnabled = true
            TriggerService.start(this)
            toast("Service (re)started")
            refreshStatuses()
        }
        val rootButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rootButtons.addView(Button(this).apply {
            text = "Root: grant everything"
            setOnClickListener { runRootTask("Granting permissions via root") { RootSetup.grantEverything(this@MainActivity, app.root) } }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rootButtons.addView(Button(this).apply {
            text = "Root: make default assistant"
            setOnClickListener { runRootTask("Setting default assistant") { RootSetup.makeDefaultAssistant(this@MainActivity, app.root) } }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(rootButtons)
    }

    private fun buildClaudeSection() {
        val box = section("Claude")
        val keyField = EditText(this).apply {
            hint = "Claude API key (sk-ant-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val keyStatus = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        fun refreshKeyStatus() {
            val key = settings.apiKey
            keyStatus.text = if (key.isNullOrBlank()) "No API key saved" else "Saved key ending in ...${key.takeLast(4)}"
        }
        refreshKeyStatus()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(keyField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                val value = keyField.text.toString().trim()
                if (value.isEmpty()) {
                    toast("Enter a key first")
                } else {
                    settings.apiKey = value
                    keyField.setText("")
                    refreshKeyStatus()
                    toast("API key saved")
                }
            }
        })
        box.addView(row)
        box.addView(keyStatus)
        box.spinnerRow("Model", io.github.judegibatron.phoneagent.core.Settings.MODELS, settings.model) { settings.model = it }
        box.spinnerRow("Effort (thinking depth)", io.github.judegibatron.phoneagent.core.Settings.EFFORTS, settings.effort) { settings.effort = it }
        box.switchRow("Allow web search (server tool, extra cost)", settings.webSearch) { settings.webSearch = it }
    }

    private fun buildTriggerSection() {
        val box = section("Trigger")
        box.switchRow("Hold anywhere on the screen (needs root)", settings.holdEnabled) {
            settings.holdEnabled = it
            TriggerManager.sync(this)
            refreshStatuses()
        }
        box.numberRow("Hold duration in seconds", (settings.holdMillis / 1000.0).toString()) { text ->
            text.toDoubleOrNull()?.takeIf { it in 1.0..15.0 }?.let { settings.holdMillis = (it * 1000).toLong(); toast("Hold set to $it s") }
                ?: toast("Enter 1 to 15")
        }
        box.spinnerRow("Fingers required", listOf("1", "2", "3"), settings.holdFingers.toString()) { settings.holdFingers = it.toInt() }
        box.numberRow("Touchscreen override (event path or name substring, blank = auto)", settings.touchDeviceOverride) {
            settings.touchDeviceOverride = it.trim()
            TriggerManager.stop()
            TriggerManager.sync(this)
            toast("Touch device updated")
        }
        box.spinnerRow("Volume key hold trigger (no root)", io.github.judegibatron.phoneagent.core.Settings.VOLUME_TRIGGERS, settings.volumeKeyTrigger) {
            settings.volumeKeyTrigger = it
        }
        box.numberRow("Volume key hold in milliseconds", settings.volumeKeyHoldMillis.toString()) { text ->
            text.toLongOrNull()?.takeIf { it in 300..5000 }?.let { settings.volumeKeyHoldMillis = it; toast("Saved") }
                ?: toast("Enter 300 to 5000")
        }
        box.note("Other triggers: the Quick Settings tile, the notification's Talk button, and the assistant gesture once Phone Agent is the default assistant.")
    }

    private fun buildVoiceSection() {
        val box = section("Voice")
        box.switchRow("Listen for a follow-up after each reply", settings.followUp) { settings.followUp = it }
        box.switchRow("Prefer on-device speech recognition", settings.preferOnDeviceStt) { settings.preferOnDeviceStt = it }
        box.switchRow("Speak replies aloud", settings.ttsEnabled) { settings.ttsEnabled = it }
        box.numberRow("Speech language (BCP-47, blank = device default)", settings.sttLanguage) {
            settings.sttLanguage = it.trim()
            toast("Saved")
        }
    }

    private fun buildSafetySection() {
        val box = section("Safety")
        box.note("Dangerous tools ask for a spoken yes before running.")
        box.switchRow("Confirm before sending texts", settings.confirmSms) { settings.confirmSms = it }
        box.switchRow("Confirm before placing calls", settings.confirmCalls) { settings.confirmCalls = it }
        box.switchRow("Confirm before root shell commands", settings.confirmShell) { settings.confirmShell = it }
    }

    private fun buildTrySection() {
        val box = section("Try it")
        box.addView(Button(this).apply {
            text = "Start a voice session now"
            setOnClickListener { app.sessionController.start(SessionController.Source.MANUAL) }
        })
        val field = EditText(this).apply { hint = "Or type a command, e.g. pause the music"; isSingleLine = true }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Send"
            setOnClickListener {
                val text = field.text.toString().trim()
                if (text.isEmpty()) toast("Type something first") else {
                    app.sessionController.start(SessionController.Source.MANUAL, text)
                    field.setText("")
                }
            }
        })
        box.addView(row)
    }

    private fun buildLogSection() {
        val box = section("Log")
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Copy log"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Phone Agent log", AgentLog.snapshot()))
                toast("Log copied")
            }
        })
        buttons.addView(Button(this).apply {
            text = "Refresh status"
            setOnClickListener { refreshStatuses() }
        })
        box.addView(buttons)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
        }
        box.addView(logView)
    }

    // ---------------------------------------------------------------- status

    private fun refreshStatuses() {
        // Never call isAvailable() here: it may block on the root manager's prompt (ANR on the UI thread).
        when (app.root.knownAvailability) {
            true -> setStatus("root", "granted", true)
            false -> setStatus("root", "not granted (tap Request; approve in your root app)", false)
            null -> setStatus("root", "not checked yet (tap Request)", false)
        }
        val accessibilityOn = isAccessibilityEnabled()
        setStatus("accessibility", if (accessibilityOn) "enabled" else "disabled", accessibilityOn)
        val notifOn = AgentNotificationListener.isEnabled(this)
        setStatus("notifications", if (notifOn) "granted" else "not granted", notifOn)
        val overlayOn = Settings.canDrawOverlays(this)
        setStatus("overlay", if (overlayOn) "granted" else "not granted", overlayOn)
        val missing = runtimePermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        setStatus(
            "permissions",
            if (missing.isEmpty()) "all granted" else "missing: " + missing.joinToString { it.substringAfterLast('.') },
            missing.isEmpty(),
        )
        val writeOn = Settings.System.canWrite(this)
        setStatus("writeSettings", if (writeOn) "granted" else "not granted", writeOn)
        val dndOn = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        setStatus("dnd", if (dndOn) "granted" else "not granted", dndOn)
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        setStatus("battery", if (batteryOk) "exempt" else "not exempt", batteryOk)
        val assistant = Settings.Secure.getString(contentResolver, "assistant") ?: ""
        val isAssistant = assistant.contains(packageName)
        setStatus("assistant", if (isAssistant) "Phone Agent is the assistant" else "another assistant is selected", isAssistant)
        val serviceStatus = TriggerManager.status()
        setStatus("service", serviceStatus, serviceStatus.startsWith("watching"))
    }

    private fun setStatus(key: String, text: String, ok: Boolean) {
        statusViews[key]?.apply {
            this.text = text
            setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (AgentAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, AgentAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun runtimePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
        )
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        return list.toTypedArray()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatuses()
    }

    private fun refreshLog() {
        if (::logView.isInitialized) logView.text = AgentLog.snapshot()
    }

    private fun runRootTask(title: String, task: () -> String) {
        toast("$title...")
        background.execute {
            val report = try {
                if (!app.root.isAvailable(refresh = true)) "Root is not available. Grant Phone Agent root in Magisk/KernelSU first." else task()
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle(title).setMessage(report).setPositiveButton("OK", null).show()
                refreshStatuses()
                TriggerService.start(this)
            }
        }
    }

    // ---------------------------------------------------------------- widgets

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun section(title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        box.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        content.addView(box)
        return box
    }

    private fun LinearLayout.note(text: String) {
        addView(TextView(this@MainActivity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, dp(8))
        })
    }

    private fun LinearLayout.statusRow(key: String, label: String, buttonText: String, onClick: () -> Unit) {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this@MainActivity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        val status = TextView(this@MainActivity).apply {
            text = "checking..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        texts.addView(status)
        statusViews[key] = status
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this@MainActivity).apply {
            text = buttonText
            setOnClickListener { onClick() }
        })
        addView(row)
    }

    private fun LinearLayout.switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        addView(Switch(this@MainActivity).apply {
            text = label
            isChecked = checked
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
    }

    private fun LinearLayout.spinnerRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this@MainActivity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val spinner = Spinner(this@MainActivity)
        spinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(options.indexOf(selected).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelect(options[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        row.addView(spinner)
        addView(row)
    }

    private fun LinearLayout.numberRow(label: String, value: String, onSave: (String) -> Unit) {
        addView(TextView(this@MainActivity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(6), 0, 0)
        })
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = EditText(this@MainActivity).apply {
            setText(value)
            isSingleLine = true
        }
        row.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this@MainActivity).apply {
            text = "Save"
            setOnClickListener { onSave(field.text.toString()) }
        })
        addView(row)
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("That settings screen is not available on this phone")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
