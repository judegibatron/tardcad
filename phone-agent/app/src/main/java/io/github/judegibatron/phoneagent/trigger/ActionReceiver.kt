package io.github.judegibatron.phoneagent.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.session.SessionController

/** Handles the notification's "Talk" action. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TALK) {
            PhoneAgentApp.get(context).sessionController.start(SessionController.Source.NOTIFICATION)
        }
    }

    companion object {
        const val ACTION_TALK = "io.github.judegibatron.phoneagent.action.TALK"
    }
}
