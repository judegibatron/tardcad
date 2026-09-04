package io.github.judegibatron.phoneagent.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.core.AgentLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PhoneAgentApp.get(context).settings.serviceEnabled) return
        AgentLog.d("Boot", "boot completed; starting trigger service")
        TriggerService.start(context)
    }
}
