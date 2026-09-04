package io.github.judegibatron.phoneagent.access

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.judegibatron.phoneagent.core.AgentLog

/**
 * Notification access unlocks two things: MediaSessionManager (play/pause whatever is playing)
 * and reading the notification shade. No notification is ever modified or dismissed.
 */
class AgentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        AgentLog.d("Notif", "notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun snapshot(): List<StatusBarNotification> = try {
        activeNotifications?.toList() ?: emptyList()
    } catch (e: Exception) {
        AgentLog.w("Notif", "activeNotifications unavailable: ${e.message}")
        emptyList()
    }

    companion object {
        @Volatile
        var instance: AgentNotificationListener? = null
            private set

        fun isEnabled(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, AgentNotificationListener::class.java))

        fun componentName(context: Context): ComponentName =
            ComponentName(context, AgentNotificationListener::class.java)
    }
}
