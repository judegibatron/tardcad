package io.github.judegibatron.phoneagent.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.speech.SpeechInput

/**
 * A VoiceInteractionService must declare a RecognitionService, and Android makes that service the
 * system-wide default recognizer once the app becomes the assistant. To keep other apps' speech
 * input working, this service simply forwards every request to the real recognizer on the phone.
 */
class ProxyRecognitionService : RecognitionService() {

    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val target = SpeechInput.pickRecognizer(this, PhoneAgentApp.get(this).settings)
        if (target == null) {
            AgentLog.w(TAG, "no other recognition service installed")
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        delegate?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this, target)
        delegate = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runCatching { listener.readyForSpeech(params ?: Bundle()) }
            }

            override fun onBeginningOfSpeech() {
                runCatching { listener.beginningOfSpeech() }
            }

            override fun onRmsChanged(rmsdB: Float) {
                runCatching { listener.rmsChanged(rmsdB) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (buffer != null) runCatching { listener.bufferReceived(buffer) }
            }

            override fun onEndOfSpeech() {
                runCatching { listener.endOfSpeech() }
            }

            override fun onError(error: Int) {
                runCatching { listener.error(error) }
            }

            override fun onResults(results: Bundle?) {
                runCatching { listener.results(results ?: Bundle()) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                runCatching { listener.partialResults(partialResults ?: Bundle()) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) {
        delegate?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        delegate?.cancel()
    }

    override fun onDestroy() {
        delegate?.destroy()
        delegate = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ProxySTT"
    }
}
