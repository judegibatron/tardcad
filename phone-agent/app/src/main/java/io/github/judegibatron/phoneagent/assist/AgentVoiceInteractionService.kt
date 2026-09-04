package io.github.judegibatron.phoneagent.assist

import android.service.voice.VoiceInteractionService
import io.github.judegibatron.phoneagent.core.AgentLog

/**
 * Makes the app selectable as the phone's digital assistant. The system then routes the assistant
 * gesture (side-key hold, corner swipe, home long-press) to [AgentVoiceSessionService].
 */
class AgentVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        AgentLog.d("Assist", "voice interaction service ready (app is the active assistant)")
    }
}
