package io.github.judegibatron.phoneagent.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Holds transient audio focus for the length of a session so music ducks while the user talks. */
class AudioFocusHelper(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    fun request() {
        if (request != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        request = req
        runCatching { audioManager.requestAudioFocus(req) }
    }

    fun abandon() {
        request?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        request = null
    }
}
