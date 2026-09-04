package io.github.judegibatron.phoneagent.core

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All user-tunable configuration. Plain values live in SharedPreferences; the API key is encrypted. */
class Settings(context: Context) {

    internal val prefs: SharedPreferences =
        context.getSharedPreferences("phone_agent", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    /** Claude API key (sk-ant-...). Stored encrypted; null when unset. */
    var apiKey: String?
        get() = secrets.get("api_key")
        set(value) = secrets.put("api_key", value?.trim())

    // --- Claude -------------------------------------------------------------------------------
    var model: String by stringPref("model", DEFAULT_MODEL)
    /** low | medium | high. Ignored for models without the effort parameter. */
    var effort: String by stringPref("effort", "medium")
    var maxTokens: Int by intPref("max_tokens", 12000)
    /** Safety valve on the tool loop: max API round-trips per user utterance. */
    var maxToolRounds: Int by intPref("max_tool_rounds", 24)
    var webSearch: Boolean by boolPref("web_search", false)

    // --- Triggers -----------------------------------------------------------------------------
    /** Master switch for the background trigger service (hold detector + notification). */
    var serviceEnabled: Boolean by boolPref("service_enabled", true)
    var holdEnabled: Boolean by boolPref("hold_enabled", true)
    var holdMillis: Long by longPref("hold_millis", 5000L)
    var holdFingers: Int by intPref("hold_fingers", 1)
    /** Manual touchscreen selection: a /dev/input path or a substring of the device name. */
    var touchDeviceOverride: String by stringPref("touch_device_override", "")
    /** off | up | down: which volume key, held for [volumeKeyHoldMillis], starts a session (no root needed). */
    var volumeKeyTrigger: String by stringPref("volume_key_trigger", "off")
    var volumeKeyHoldMillis: Long by longPref("volume_key_hold_millis", 1500L)

    // --- Voice --------------------------------------------------------------------------------
    var followUp: Boolean by boolPref("follow_up", true)
    var preferOnDeviceStt: Boolean by boolPref("prefer_on_device_stt", true)
    /** BCP-47 tag such as en-US; empty means the device default. */
    var sttLanguage: String by stringPref("stt_language", "")
    /** Flattened ComponentName of a RecognitionService to use instead of the auto-picked one. */
    var sttRecognizerComponent: String by stringPref("stt_recognizer", "")
    var ttsEnabled: Boolean by boolPref("tts_enabled", true)

    // --- Safety -------------------------------------------------------------------------------
    var confirmSms: Boolean by boolPref("confirm_sms", true)
    var confirmCalls: Boolean by boolPref("confirm_calls", true)
    var confirmShell: Boolean by boolPref("confirm_shell", true)

    /** Whether a spoken yes/no confirmation is required before running the named dangerous tool. */
    fun confirmFor(toolName: String): Boolean = when (toolName) {
        "send_sms" -> confirmSms
        "make_call" -> confirmCalls
        "run_shell" -> confirmShell
        else -> true
    }

    private fun stringPref(key: String, default: String) = object : ReadWriteProperty<Settings, String> {
        override fun getValue(thisRef: Settings, property: KProperty<*>): String =
            thisRef.prefs.getString(key, default) ?: default

        override fun setValue(thisRef: Settings, property: KProperty<*>, value: String) =
            thisRef.prefs.edit().putString(key, value).apply()
    }

    private fun boolPref(key: String, default: Boolean) = object : ReadWriteProperty<Settings, Boolean> {
        override fun getValue(thisRef: Settings, property: KProperty<*>): Boolean =
            thisRef.prefs.getBoolean(key, default)

        override fun setValue(thisRef: Settings, property: KProperty<*>, value: Boolean) =
            thisRef.prefs.edit().putBoolean(key, value).apply()
    }

    private fun intPref(key: String, default: Int) = object : ReadWriteProperty<Settings, Int> {
        override fun getValue(thisRef: Settings, property: KProperty<*>): Int =
            thisRef.prefs.getInt(key, default)

        override fun setValue(thisRef: Settings, property: KProperty<*>, value: Int) =
            thisRef.prefs.edit().putInt(key, value).apply()
    }

    private fun longPref(key: String, default: Long) = object : ReadWriteProperty<Settings, Long> {
        override fun getValue(thisRef: Settings, property: KProperty<*>): Long =
            thisRef.prefs.getLong(key, default)

        override fun setValue(thisRef: Settings, property: KProperty<*>, value: Long) =
            thisRef.prefs.edit().putLong(key, value).apply()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
        val MODELS = listOf(
            "claude-opus-5",
            "claude-sonnet-5",
            "claude-haiku-4-5",
            "claude-fable-5-1",
            "claude-opus-4-8",
        )
        val EFFORTS = listOf("low", "medium", "high")
        val VOLUME_TRIGGERS = listOf("off", "down", "up")
    }
}
