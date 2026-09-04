package io.github.judegibatron.phoneagent.tools

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import io.github.judegibatron.phoneagent.access.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max

private const val NEED_ACCESSIBILITY =
    "The accessibility service is off, so the screen cannot be read or controlled. " +
        "Ask the user to enable Phone Agent under Settings > Accessibility (or run the root setup)."

class ScreenReadTool : AgentTool(
    ToolSpec(
        name = "screen_read",
        description = "Read the current screen as a numbered list of visible elements (type, text, id, tap position, flags). " +
            "Use the [n] numbers with screen_tap, screen_type and screen_scroll. Call it again after acting to verify.",
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val acc = ctx.accessibility ?: return ToolOutput.error(NEED_ACCESSIBILITY)
        return withContext(Dispatchers.Default) { ToolOutput.text(acc.screenReader.dump()) }
    }
}

class ScreenTapTool : AgentTool(
    ToolSpec(
        name = "screen_tap",
        description = "Tap (or long-press) an element from the last screen_read by its [n] number, or a raw x,y pixel coordinate.",
        properties = mapOf(
            "element_id" to prop("integer", "[n] from the last screen_read"),
            "x" to prop("integer", "Pixel x, used when no element_id is given"),
            "y" to prop("integer", "Pixel y"),
            "long_press" to prop("boolean", "Hold instead of tapping"),
        ),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val acc = ctx.accessibility ?: return ToolOutput.error(NEED_ACCESSIBILITY)
        val longPress = args.bool("long_press") == true
        val verb = if (longPress) "Long-pressed" else "Tapped"
        val elementId = args.int("element_id")
        if (elementId != null) {
            val element = acc.screenReader.element(elementId)
                ?: return ToolOutput.error("Element $elementId is not in the last screen_read; call screen_read again.")
            if (performClick(element.node, longPress)) {
                delay(600)
                return ToolOutput.text("$verb element $elementId (${element.line.take(70)}). Use screen_read to see the result.")
            }
            val ok = acc.tap(element.bounds.exactCenterX(), element.bounds.exactCenterY(), if (longPress) 700 else 60)
            delay(600)
            return if (ok) ToolOutput.text("$verb at (${element.bounds.centerX()},${element.bounds.centerY()}) for element $elementId.")
            else ToolOutput.error("The tap gesture was rejected.")
        }
        val x = args.int("x")
        val y = args.int("y")
        if (x == null || y == null) return ToolOutput.error("Give element_id, or both x and y.")
        val ok = acc.tap(x.toFloat(), y.toFloat(), if (longPress) 700 else 60)
        delay(600)
        return if (ok) ToolOutput.text("$verb at ($x,$y).") else ToolOutput.error("The tap gesture was rejected.")
    }

    private fun performClick(node: AccessibilityNodeInfo, longPress: Boolean): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            val can = if (longPress) current.isLongClickable else current.isClickable
            val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            if (can && current.performAction(action)) return true
            current = current.parent
            depth++
        }
        return false
    }
}

class ScreenTypeTool : AgentTool(
    ToolSpec(
        name = "screen_type",
        description = "Type text into an input field: the given element, or the currently focused field. Replaces existing text " +
            "unless append=true. submit=true presses the keyboard's enter/send action afterwards.",
        properties = mapOf(
            "text" to prop("string", "Text to enter"),
            "element_id" to prop("integer", "[n] of an editable element from screen_read; defaults to the focused field"),
            "append" to prop("boolean", "Keep existing text and add to it"),
            "submit" to prop("boolean", "Press enter/send when done"),
        ),
        required = listOf("text"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val acc = ctx.accessibility ?: return ToolOutput.error(NEED_ACCESSIBILITY)
        val text = args.str("text") ?: return ToolOutput.error("text is required")
        val node = targetNode(acc, args.int("element_id"))
            ?: return ToolOutput.error("No editable field is focused. Tap the field first with screen_tap, then retry.")
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val existing = if (args.bool("append") == true) node.text?.toString().orEmpty() else ""
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        var ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!ok) {
            ctx.context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Phone Agent", text))
            ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (!ok && ctx.root.isAvailable()) {
            ok = withContext(Dispatchers.IO) { ctx.root.run("input text ${shellQuoteForInput(text)}").ok }
        }
        if (!ok) return ToolOutput.error("Could not enter the text into that field.")
        var submitted = false
        if (args.bool("submit") == true) {
            delay(200)
            submitted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (!submitted && ctx.root.isAvailable()) {
                submitted = withContext(Dispatchers.IO) { ctx.root.run("input keyevent KEYCODE_ENTER").ok }
            }
        }
        delay(400)
        return ToolOutput.text(
            "Typed ${text.length} characters" +
                when {
                    args.bool("submit") == true && submitted -> " and pressed enter."
                    args.bool("submit") == true -> ", but the enter action was not available; tap the send button instead."
                    else -> "."
                },
        )
    }

    private fun targetNode(acc: AgentAccessibilityService, elementId: Int?): AccessibilityNodeInfo? {
        if (elementId != null) return acc.screenReader.element(elementId)?.node
        val root = acc.rootInActiveWindow ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        return findEditable(root, 0)
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        if (depth > 40) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** `input text` wants spaces as %s and the whole thing shell-quoted. */
    private fun shellQuoteForInput(text: String): String =
        "'" + text.replace("'", "'\\''").replace(" ", "%s") + "'"
}

class ScreenScrollTool : AgentTool(
    ToolSpec(
        name = "screen_scroll",
        description = "Scroll the screen, or a specific scrollable element, up/down/left/right by about half a screen.",
        properties = mapOf(
            "direction" to prop("string", "Direction to scroll the content", listOf("up", "down", "left", "right")),
            "element_id" to prop("integer", "Optional scrollable element from screen_read"),
        ),
        required = listOf("direction"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val acc = ctx.accessibility ?: return ToolOutput.error(NEED_ACCESSIBILITY)
        val direction = args.str("direction") ?: return ToolOutput.error("direction is required")
        args.int("element_id")?.let { acc.screenReader.element(it) }?.let { element ->
            val action = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (element.node.performAction(action)) {
                delay(700)
                return ToolOutput.text("Scrolled $direction inside element ${element.id}.")
            }
        }
        val size = acc.displaySize()
        val w = size.x.toFloat()
        val h = size.y.toFloat()
        val (x1, y1, x2, y2) = when (direction) {
            "down" -> listOf(w / 2, h * 0.70f, w / 2, h * 0.30f)
            "up" -> listOf(w / 2, h * 0.30f, w / 2, h * 0.70f)
            "left" -> listOf(w * 0.80f, h / 2, w * 0.20f, h / 2)
            "right" -> listOf(w * 0.20f, h / 2, w * 0.80f, h / 2)
            else -> return ToolOutput.error("Unknown direction '$direction'.")
        }
        val ok = acc.swipe(x1, y1, x2, y2, 350)
        delay(700)
        return if (ok) ToolOutput.text("Scrolled $direction.") else ToolOutput.error("The scroll gesture was rejected.")
    }
}

class ScreenSwipeTool : AgentTool(
    ToolSpec(
        name = "screen_swipe",
        description = "Swipe between two pixel coordinates (for carousels, sliders, dismissing items, unlocking).",
        properties = mapOf(
            "x1" to prop("integer", "Start x"),
            "y1" to prop("integer", "Start y"),
            "x2" to prop("integer", "End x"),
            "y2" to prop("integer", "End y"),
            "duration_ms" to prop("integer", "Gesture duration, default 300"),
        ),
        required = listOf("x1", "y1", "x2", "y2"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val acc = ctx.accessibility ?: return ToolOutput.error(NEED_ACCESSIBILITY)
        val x1 = args.int("x1") ?: return ToolOutput.error("x1 is required")
        val y1 = args.int("y1") ?: return ToolOutput.error("y1 is required")
        val x2 = args.int("x2") ?: return ToolOutput.error("x2 is required")
        val y2 = args.int("y2") ?: return ToolOutput.error("y2 is required")
        val duration = (args.int("duration_ms") ?: 300).coerceIn(50, 5000).toLong()
        val ok = acc.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration)
        delay(500)
        return if (ok) ToolOutput.text("Swiped from ($x1,$y1) to ($x2,$y2).") else ToolOutput.error("The swipe gesture was rejected.")
    }
}

class PressButtonTool : AgentTool(
    ToolSpec(
        name = "press_button",
        description = "Press a system button or shortcut.",
        properties = mapOf(
            "button" to prop(
                "string",
                "back, home, recents, notifications (open the shade), quick_settings, dismiss_notifications (close the shade), " +
                    "lock_screen, power_menu, wake_screen",
                listOf("back", "home", "recents", "notifications", "quick_settings", "dismiss_notifications", "lock_screen", "power_menu", "wake_screen"),
            ),
        ),
        required = listOf("button"),
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val button = args.str("button") ?: return ToolOutput.error("button is required")
        if (button == "wake_screen") {
            if (ctx.root.isAvailable()) {
                withContext(Dispatchers.IO) { ctx.root.run("input keyevent KEYCODE_WAKEUP") }
            } else {
                @Suppress("DEPRECATION")
                ctx.context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "phoneagent:wake")
                    .acquire(3000)
            }
            return ToolOutput.text("Woke the screen.")
        }
        val acc = ctx.accessibility ?: return ToolOutput.error(NEED_ACCESSIBILITY)
        val action = when (button) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "dismiss_notifications" ->
                if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
                else AccessibilityService.GLOBAL_ACTION_BACK
            else -> return ToolOutput.error("Unknown button '$button'.")
        }
        val ok = acc.performGlobalAction(action)
        delay(500)
        return if (ok) ToolOutput.text("Pressed $button.") else ToolOutput.error("The system rejected '$button'.")
    }
}

class TakeScreenshotTool : AgentTool(
    ToolSpec(
        name = "take_screenshot",
        description = "Capture the screen as an image so you can see exactly what is displayed (images, maps, games, " +
            "anything screen_read cannot express). Coordinates in the image must be multiplied by the reported scale factor before screen_tap.",
        timeoutMs = 25_000,
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val bitmap: Bitmap = ctx.accessibility?.screenshot() ?: rootScreenshot(ctx)
            ?: return ToolOutput.error("Screenshot failed. It needs the accessibility service or root access.")
        return withContext(Dispatchers.Default) {
            val screenW = bitmap.width
            val screenH = bitmap.height
            val longest = max(screenW, screenH)
            val scale = if (longest > 1280) 1280f / longest else 1f
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (screenW * scale).toInt(), (screenH * scale).toInt(), true)
            } else {
                bitmap
            }
            val imageW = scaled.width
            val imageH = scaled.height
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            val factor = if (scale < 1f) String.format(java.util.Locale.US, "%.2f", 1f / scale) else "1"
            ToolOutput.image(
                "Screenshot captured: image is ${imageW}x$imageH; screen is ${screenW}x$screenH. " +
                    "Scale factor $factor (screen coordinate = image coordinate x $factor).",
                Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        }
    }

    private suspend fun rootScreenshot(ctx: ToolContext): Bitmap? = withContext(Dispatchers.IO) {
        if (!ctx.root.isAvailable()) return@withContext null
        ctx.root.runBinary("screencap -p")?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
}
