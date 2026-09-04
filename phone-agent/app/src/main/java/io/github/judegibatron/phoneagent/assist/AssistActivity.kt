package io.github.judegibatron.phoneagent.assist

import android.app.Activity
import android.os.Bundle
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.session.SessionController

/** Invisible entry point for ACTION_ASSIST / ACTION_VOICE_COMMAND: start a session and get out of the way. */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneAgentApp.get(this).sessionController.start(SessionController.Source.ASSIST)
        finish()
    }
}
