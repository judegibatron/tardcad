package io.github.judegibatron.phoneagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.core.Settings
import io.github.judegibatron.phoneagent.root.RootShell
import io.github.judegibatron.phoneagent.session.SessionController

/** Process-wide wiring. Every component (services, receivers, activities) runs in this one process. */
class PhoneAgentApp : Application() {

    lateinit var settings: Settings
        private set

    val root: RootShell = RootShell()

    lateinit var sessionController: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        sessionController = SessionController(this)
        createChannels()
        AgentLog.d("App", "Phone Agent process started")
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_ALERTS = "alerts"

        lateinit var instance: PhoneAgentApp
            private set

        fun get(context: Context): PhoneAgentApp = context.applicationContext as PhoneAgentApp
    }
}
