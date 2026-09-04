package io.github.judegibatron.phoneagent.access

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Turns the accessibility tree into a compact numbered list the model can reason about, and keeps
 * the node handles so `screen_tap 12` can act on element 12 from the last read.
 */
class ScreenReader(private val service: AgentAccessibilityService) {

    class Element(val id: Int, val node: AccessibilityNodeInfo, val bounds: Rect, val line: String)

    @Volatile
    private var elements: List<Element> = emptyList()

    fun element(id: Int): Element? = elements.firstOrNull { it.id == id }

    fun hasElements(): Boolean = elements.isNotEmpty()

    fun dump(maxItems: Int = 150): String {
        val roots = collectRoots()
        if (roots.isEmpty()) {
            elements = emptyList()
            return "No readable window content. The screen may be locked, off, or the app blocks accessibility."
        }
        val sb = StringBuilder()
        val list = ArrayList<Element>()
        for ((title, root) in roots) {
            sb.appendLine("== $title ==")
            walk(root, 0, list, sb, maxItems)
        }
        elements = list
        if (list.size >= maxItems) sb.appendLine("(truncated at $maxItems elements; scroll to see more)")
        if (list.isEmpty()) sb.appendLine("(window has no visible text or interactive elements)")
        return sb.toString().trim()
    }

    private fun collectRoots(): List<Pair<String, AccessibilityNodeInfo>> {
        val out = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        val windows: List<AccessibilityWindowInfo> = try {
            service.windows
        } catch (e: Exception) {
            emptyList()
        }
        val own = service.packageName
        val ordered = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { (if (it.isActive) 2 else 0) + (if (it.isFocused) 1 else 0) }
        for (window in ordered) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == own) continue
            val title = window.title?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            out += "$title ($pkg)" to root
            if (out.size >= 3) break
        }
        if (out.isEmpty()) {
            service.rootInActiveWindow?.let { root ->
                if (root.packageName?.toString() != own) out += (root.packageName?.toString() ?: "window") to root
            }
        }
        return out
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        list: MutableList<Element>,
        sb: StringBuilder,
        max: Int,
    ) {
        if (list.size >= max || depth > 48) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = node.hintText?.toString()?.trim().orEmpty()
        val interesting = node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable ||
            node.isScrollable || text.isNotEmpty() || desc.isNotEmpty()
        if (interesting) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val id = list.size + 1
                val role = node.className?.toString()?.substringAfterLast('.')?.ifBlank { null } ?: "View"
                val parts = ArrayList<String>()
                parts += "[$id]"
                parts += role
                if (text.isNotEmpty()) parts += "\"${text.take(80).replace('\n', ' ')}\""
                if (desc.isNotEmpty() && desc != text) parts += "desc=\"${desc.take(60).replace('\n', ' ')}\""
                if (hint.isNotEmpty()) parts += "hint=\"${hint.take(40)}\""
                node.viewIdResourceName?.substringAfter('/', "")?.takeIf { it.isNotBlank() }?.let { parts += "id=$it" }
                parts += "@(${bounds.centerX()},${bounds.centerY()})"
                val flags = ArrayList<String>()
                if (node.isClickable) flags += "clickable"
                if (node.isLongClickable) flags += "long-clickable"
                if (node.isEditable) flags += "editable"
                if (node.isCheckable) flags += if (node.isChecked) "checked" else "unchecked"
                if (node.isScrollable) flags += "scrollable"
                if (node.isFocused) flags += "focused"
                if (node.isSelected) flags += "selected"
                if (!node.isEnabled) flags += "disabled"
                if (flags.isNotEmpty()) parts += flags.joinToString(",")
                val line = parts.joinToString(" ")
                sb.appendLine(line)
                list += Element(id, node, bounds, line)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, list, sb, max)
        }
    }
}
