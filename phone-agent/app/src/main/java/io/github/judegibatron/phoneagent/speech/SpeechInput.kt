package io.github.judegibatron.phoneagent.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Speech-to-text through the platform SpeechRecognizer. Prefers the on-device recognizer
 * (Android 12+), otherwise picks an installed recognition service explicitly so it never depends
 * on the system default (which points at this app's own proxy when it is the digital assistant).
 */
class SpeechInput(private val context: Context, private val settings: Settings) {

    sealed class Outcome {
        data class Heard(val text: String) : Outcome()
        object NoSpeech : Outcome()
        data class Failed(val code: Int, val message: String) : Outcome()
    }

    private var recognizer: SpeechRecognizer? = null

    /** Listens for one utterance. Must be called from a coroutine; internally hops to the main thread. */
    suspend fun listen(followUp: Boolean, onPartial: (String) -> Unit = {}): Outcome {
        val first = listenOnce(followUp, onPartial)
        if (first is Outcome.Failed &&
            (first.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || first.code == SpeechRecognizer.ERROR_CLIENT)
        ) {
            AgentLog.w(TAG, "recognizer error ${first.code}; recreating and retrying once")
            release()
            return listenOnce(followUp, onPartial)
        }
        return first
    }

    private suspend fun listenOnce(followUp: Boolean, onPartial: (String) -> Unit): Outcome =
        withContext(Dispatchers.Main.immediate) {
            val rec = obtain() ?: return@withContext Outcome.Failed(-1, "No speech recognition service is installed")
            val outcome = withTimeoutOrNull(if (followUp) FOLLOW_UP_TIMEOUT_MS else FIRST_TIMEOUT_MS) {
                suspendCancellableCoroutine<Outcome> { cont ->
                    rec.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit

                        override fun onError(error: Int) {
                            if (!cont.isActive) return
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    cont.resume(Outcome.NoSpeech)
                                else -> cont.resume(Outcome.Failed(error, describe(error)))
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            if (!cont.isActive) return
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()?.trim()
                            cont.resume(if (text.isNullOrEmpty()) Outcome.NoSpeech else Outcome.Heard(text))
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                        }
                    })
                    try {
                        rec.startListening(buildIntent(followUp))
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(Outcome.Failed(-2, e.message ?: "startListening failed"))
                    }
                    cont.invokeOnCancellation { runCatching { rec.cancel() } }
                }
            }
            if (outcome == null) {
                runCatching { rec.cancel() }
                Outcome.NoSpeech
            } else {
                outcome
            }
        }

    /** Destroys the underlying recognizer. Main thread only. */
    fun release() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private fun obtain(): SpeechRecognizer? {
        recognizer?.let { return it }
        val created: SpeechRecognizer? = when {
            Build.VERSION.SDK_INT >= 31 && settings.preferOnDeviceStt &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> {
                AgentLog.d(TAG, "using on-device recognizer")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }
            else -> {
                val component = pickRecognizer(context, settings)
                when {
                    component != null -> {
                        AgentLog.d(TAG, "using recognizer ${component.flattenToShortString()}")
                        SpeechRecognizer.createSpeechRecognizer(context, component)
                    }
                    SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
                    else -> null
                }
            }
        }
        recognizer = created
        return created
    }

    private fun buildIntent(followUp: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.sttLanguage.ifBlank { Locale.getDefault().toLanguageTag() })
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, if (followUp) 2000L else 4000L)
        }

    companion object {
        private const val TAG = "STT"
        private const val FIRST_TIMEOUT_MS = 20_000L
        private const val FOLLOW_UP_TIMEOUT_MS = 12_000L

        private val PREFERRED_PACKAGES = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
            "com.google.android.as",
            "com.samsung.android.bixby.agent",
        )

        /** Chooses a RecognitionService other than this app's own proxy. */
        fun pickRecognizer(context: Context, settings: Settings): ComponentName? {
            settings.sttRecognizerComponent.takeIf { it.isNotBlank() }
                ?.let { ComponentName.unflattenFromString(it) }
                ?.let { return it }
            val services = context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.MATCH_ALL)
                .mapNotNull { it.serviceInfo }
                .filter { it.packageName != context.packageName }
            if (services.isEmpty()) return null
            val best = services.minByOrNull { info ->
                PREFERRED_PACKAGES.indexOf(info.packageName).let { if (it < 0) PREFERRED_PACKAGES.size else it }
            } ?: return null
            return ComponentName(best.packageName, best.name)
        }

        fun describe(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "speech recognition needs a network connection"
            SpeechRecognizer.ERROR_AUDIO -> "microphone audio error"
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "speech server error"
            SpeechRecognizer.ERROR_CLIENT -> "recognizer client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many recognition requests"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language not supported by the recognizer"
            else -> "speech recognition error $code"
        }
    }
}
