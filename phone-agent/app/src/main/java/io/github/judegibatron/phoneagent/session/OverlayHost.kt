package io.github.judegibatron.phoneagent.session

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.judegibatron.phoneagent.access.AgentAccessibilityService
import io.github.judegibatron.phoneagent.core.AgentLog
import kotlin.math.min

/**
 * Puts the session card on screen. Prefers an accessibility overlay (draws above everything,
 * including the lock screen, and never steals input); falls back to a regular "draw over other
 * apps" window when the accessibility service is not enabled.
 */
class OverlayHost(private val appContext: Context) {

    private var windowManager: WindowManager? = null
    private var shown: View? = null

    /** Main thread only. Returns false when no overlay permission is available. */
    fun show(overlay: SessionOverlay): Boolean {
        hide()
        val accessibility = AgentAccessibilityService.instance
        val (context, type) = when {
            accessibility != null -> accessibility to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(appContext) -> appContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return false
        }
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val params = WindowManager.LayoutParams().apply {
            this.type = type
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = min(metrics.widthPixels - (32 * density).toInt(), (560 * density).toInt())
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96 * density).toInt()
        }
        return try {
            wm.addView(overlay.view, params)
            windowManager = wm
            shown = overlay.view
            true
        } catch (e: Exception) {
            AgentLog.e("Overlay", "addView failed", e)
            false
        }
    }

    fun hide() {
        val view = shown ?: return
        runCatching { windowManager?.removeViewImmediate(view) }
        shown = null
        windowManager = null
    }
}
