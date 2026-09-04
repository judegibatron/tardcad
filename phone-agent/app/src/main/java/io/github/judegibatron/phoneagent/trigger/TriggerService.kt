package io.github.judegibatron.phoneagent.trigger

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.R
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.ui.MainActivity

/**
 * Foreground service that keeps the process (and the hold detector) alive and gives the app the
 * microphone foreground-service type so speech recognition works from the background.
 */
class TriggerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PhoneAgentApp.get(this).settings.serviceEnabled = false
            TriggerManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        TriggerManager.sync(this)
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification()
        val attempts: List<Int?> = if (Build.VERSION.SDK_INT >= 34) {
            listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, null)
        }
        for (type in attempts) {
            try {
                if (type == null) startForeground(NOTIFICATION_ID, notification)
                else startForeground(NOTIFICATION_ID, notification, type)
                return
            } catch (e: Exception) {
                AgentLog.w(TAG, "startForeground(type=$type) rejected: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // Stopping before the system's deadline avoids the "did not call startForeground" crash;
        // the hold detector keeps running in-process (TriggerManager) as long as the process lives.
        AgentLog.e(TAG, "could not enter foreground; running the hold detector without a foreground service")
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val talk = PendingIntent.getBroadcast(
            this, 1, Intent(this, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, TriggerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_mic)
        return Notification.Builder(this, PhoneAgentApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Phone Agent is ready")
            .setContentText(TriggerManager.status())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(icon, "Talk", talk).build())
            .addAction(Notification.Action.Builder(icon, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "TriggerService"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "io.github.judegibatron.phoneagent.action.STOP_SERVICE"

        fun start(context: Context) {
            // The detector does not depend on the service; make sure it runs even if the FGS start is refused.
            TriggerManager.sync(context)
            try {
                context.startForegroundService(Intent(context, TriggerService::class.java))
            } catch (e: Exception) {
                AgentLog.w(TAG, "startForegroundService refused (${e.message}); detector keeps running in-process")
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TriggerService::class.java).setAction(ACTION_STOP))
        }
    }
}
