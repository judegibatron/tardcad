package io.github.judegibatron.phoneagent.assist

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.session.SessionController

class AgentVoiceSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AgentVoiceSession(this)

    /** The system shows this when the assistant gesture fires; we hand off to our own overlay UI. */
    private class AgentVoiceSession(context: Context) : VoiceInteractionSession(context) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            PhoneAgentApp.get(context).sessionController.start(SessionController.Source.ASSIST)
            hide()
        }
    }
}
