package io.github.judegibatron.phoneagent.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.judegibatron.phoneagent.core.AgentLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Text-to-speech that suspends until the utterance has finished playing. Create on the main thread. */
class SpeechOutput(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var languageSet = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        if (!ok) AgentLog.w(TAG, "TTS engine failed to initialise (status $status)")
        ready.complete(ok)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { waiters.remove(it)?.complete(true) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { waiters.remove(it)?.complete(false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { waiters.remove(it)?.complete(false) }
            }
        })
    }

    suspend fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val isReady = withTimeoutOrNull(4_000) { ready.await() } ?: false
        if (!isReady) return
        if (!languageSet) {
            languageSet = true
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            val result = tts.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                AgentLog.w(TAG, "TTS language ${Locale.getDefault()} unavailable; using engine default")
            }
        }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        waiters[id] = done
        if (tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            waiters.remove(id)
            AgentLog.w(TAG, "TTS speak() rejected the utterance")
            return
        }
        try {
            withTimeoutOrNull(8_000L + clean.length * 90L) { done.await() }
        } catch (e: CancellationException) {
            runCatching { tts.stop() }
            throw e
        } finally {
            waiters.remove(id)
        }
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching { tts.shutdown() }
    }

    private companion object {
        const val TAG = "TTS"
    }
}
