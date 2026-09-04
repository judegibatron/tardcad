package io.github.judegibatron.phoneagent.trigger

import android.content.Context
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.core.AgentLog

/** Owns the single [HoldDetector] for the process; safe to call from any component. */
object TriggerManager {

    private var detector: HoldDetector? = null

    /** Starts or stops the hold detector so it matches current settings. Idempotent. */
    @Synchronized
    fun sync(context: Context) {
        val app = PhoneAgentApp.get(context)
        val settings = app.settings
        if (settings.serviceEnabled && settings.holdEnabled) {
            if (detector == null) {
                AgentLog.d("Trigger", "starting hold detector")
                detector = HoldDetector(app, app.root, settings) { app.sessionController.onHoldTrigger() }
                    .also { it.start() }
            }
        } else {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        detector?.stop()
        detector = null
    }

    fun status(): String = detector?.status ?: "hold detector not running"
}
