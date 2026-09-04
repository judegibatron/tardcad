package io.github.judegibatron.phoneagent.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.judegibatron.phoneagent.PhoneAgentApp
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.trigger.TriggerService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The control engine. Once the user enables it in Settings > Accessibility it can read any app's
 * UI tree, tap/swipe/type, press system buttons, take screenshots and draw the session card above
 * everything (including the lock screen). It also hosts the no-root volume-key trigger.
 */
class AgentAccessibilityService : AccessibilityService() {

    val screenReader: ScreenReader by lazy { ScreenReader(this) }

    /** Package of the last non-system window that came to the front. */
    @Volatile
    var foregroundPackage: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var keyHoldPending: Runnable? = null
    private var keyFired = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AgentLog.d(TAG, "accessibility service connected")
        // The engine is up, so make sure the trigger side is too.
        TriggerService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        foregroundPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ volume-key trigger

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val app = PhoneAgentApp.get(this)
        val settings = app.settings
        val wanted = when (settings.volumeKeyTrigger) {
            "up" -> KeyEvent.KEYCODE_VOLUME_UP
            "down" -> KeyEvent.KEYCODE_VOLUME_DOWN
            else -> return false
        }
        if (event.keyCode != wanted) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                keyFired = false
                keyHoldPending?.let { handler.removeCallbacks(it) }
                val fire = Runnable {
                    keyFired = true
                    AgentLog.d(TAG, "volume key held ${settings.volumeKeyHoldMillis} ms")
                    app.sessionController.onKeyTrigger()
                }
                keyHoldPending = fire
                handler.postDelayed(fire, settings.volumeKeyHoldMillis)
            }
            KeyEvent.ACTION_UP -> {
                keyHoldPending?.let { handler.removeCallbacks(it) }
                keyHoldPending = null
                if (!keyFired) {
                    // Short press: behave like a normal volume key so the button stays useful.
                    val direction = if (wanted == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    runCatching { getSystemService(AudioManager::class.java).adjustVolume(direction, AudioManager.FLAG_SHOW_UI) }
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ actions used by tools

    fun displaySize(): Point {
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return Point(bounds.width(), bounds.height())
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean =
        gesture(Path().apply { moveTo(x, y) }, durationMs)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean =
        gesture(
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            },
            durationMs,
        )

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000))
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }
        val dispatched = dispatchGesture(description, callback, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    /** Captures the default display. Returns a software bitmap the caller must recycle. */
    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        if (cont.isActive) cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        AgentLog.w(TAG, "takeScreenshot failed with code $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } catch (e: Exception) {
            AgentLog.e(TAG, "takeScreenshot threw", e)
            if (cont.isActive) cont.resume(null)
        }
    }

    companion object {
        private const val TAG = "Access"

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }
}
