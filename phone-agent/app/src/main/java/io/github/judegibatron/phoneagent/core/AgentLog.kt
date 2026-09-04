package io.github.judegibatron.phoneagent.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Ring-buffer log mirrored to logcat. The setup screen shows it so problems are visible on-device. */
object AgentLog {
    private const val TAG = "PhoneAgent"
    private const val CAPACITY = 400

    private val lines = ArrayDeque<String>(CAPACITY)
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val listeners = mutableListOf<() -> Unit>()

    fun d(tag: String, msg: String) {
        Log.d(TAG, "[$tag] $msg")
        append("D", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(TAG, "[$tag] $msg")
        append("W", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$tag] $msg", t)
        append("E", tag, if (t != null) "$msg (${t.javaClass.simpleName}: ${t.message})" else msg)
    }

    private fun append(level: String, tag: String, msg: String) {
        val snapshotListeners: List<() -> Unit>
        synchronized(lines) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast("${format.format(Date())} $level/$tag: $msg")
            snapshotListeners = listeners.toList()
        }
        snapshotListeners.forEach { runCatching { it() } }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun addListener(listener: () -> Unit) = synchronized(lines) { listeners += listener }

    fun removeListener(listener: () -> Unit) = synchronized(lines) { listeners -= listener }
}
