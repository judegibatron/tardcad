package io.github.judegibatron.phoneagent.trigger

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.core.Settings
import io.github.judegibatron.phoneagent.root.RootShell
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/**
 * Global "hold anywhere for N seconds" detector.
 *
 * Android gives no app a way to observe touches on other apps' windows, so this reads the raw
 * touchscreen event stream (`/dev/input/eventN`) through a root shell (`su -c cat`). It follows
 * the Linux multi-touch protocol B (slots + tracking ids) with a protocol A / BTN_TOUCH fallback.
 *
 * A hold fires when exactly [Settings.holdFingers] contacts stay down, each within a small slop of
 * where it landed, for [Settings.holdMillis]. Stationary fingers produce no events, so the deadline
 * is tracked with a timer rather than waiting for the next event.
 */
class HoldDetector(
    context: Context,
    private val root: RootShell,
    private val settings: Settings,
    private val onHold: () -> Unit,
) {
    data class TouchDevice(val path: String, val name: String, val maxX: Int, val maxY: Int, val direct: Boolean)

    private class Touch(val downAt: Long) {
        var startX = 0
        var startY = 0
        var x = 0
        var y = 0
        var frames = 0
        var moved = false
    }

    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hold-timer").apply { isDaemon = true }
    }
    private val lock = Any()

    @Volatile
    var status: String = "stopped"
        private set

    @Volatile
    private var running = false
    private var reader: Thread? = null

    @Volatile
    private var process: Process? = null

    // Touch state, guarded by [lock].
    private val slots = HashMap<Int, Touch>()
    private val slotX = IntArray(MAX_SLOTS)
    private val slotY = IntArray(MAX_SLOTS)
    private var currentSlot = 0
    private var sawTrackingId = false
    private var fired = false
    private var groupSince = 0L
    private var pending: ScheduledFuture<*>? = null
    private var slop = 40

    fun start() {
        if (running) return
        running = true
        reader = thread(isDaemon = true, name = "hold-reader") { loop() }
    }

    fun stop() {
        running = false
        process?.let { p ->
            // Closing our end of the pipe unblocks readFully(); destroy() takes down the su client.
            runCatching { p.inputStream.close() }
            runCatching { p.destroy() }
        }
        reader?.interrupt()
        reader = null
        synchronized(lock) {
            slots.clear()
            cancelPending()
        }
        status = "stopped"
    }

    private fun loop() {
        var backoff = 2_000L
        while (running) {
            if (!root.isAvailable(refresh = true)) {
                status = "root not granted"
                if (!sleepQuietly(15_000)) break
                continue
            }
            val device = discoverDevice()
            if (device == null) {
                status = "no touchscreen found in getevent -p"
                if (!sleepQuietly(backoff)) break
                backoff = (backoff * 2).coerceAtMost(60_000)
                continue
            }
            slop = max(20, (max(device.maxX, device.maxY) * 0.04).toInt())
            status = "watching ${device.path} (${device.name})"
            AgentLog.d(TAG, "$status, slop=$slop raw units")
            backoff = 2_000L
            try {
                readEvents(device)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running) AgentLog.e(TAG, "event reader failed", e)
            }
            if (running) {
                status = "reader exited, retrying"
                if (!sleepQuietly(backoff)) break
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
        status = "stopped"
    }

    private fun sleepQuietly(ms: Long): Boolean = try {
        Thread.sleep(ms)
        running
    } catch (e: InterruptedException) {
        false
    }

    private fun discoverDevice(): TouchDevice? {
        var out = root.run("getevent -pl", 15_000).stdout
        if (!out.contains("add device")) out = root.run("getevent -p", 15_000).stdout
        val devices = parseGetevent(out)
        if (devices.isEmpty()) return null
        val override = settings.touchDeviceOverride.trim()
        if (override.isNotEmpty()) {
            devices.firstOrNull { it.path == override || it.name.contains(override, ignoreCase = true) }
                ?.let { return it }
            AgentLog.w(TAG, "touch device override '$override' not found; auto-selecting")
        }
        return devices.maxByOrNull { score(it) }
    }

    private fun score(d: TouchDevice): Int {
        var s = 0
        val n = d.name.lowercase()
        if (d.direct) s += 10
        if ("touch" in n || n.endsWith("_ts") || "_ts" in n || "tsp" in n) s += 5
        if ("pen" in n || "stylus" in n || "digitizer" in n) s -= 10
        if ("key" in n || "button" in n || "fingerprint" in n) s -= 5
        s += (d.maxX / 1000).coerceAtMost(4)
        return s
    }

    private fun readEvents(device: TouchDevice) {
        synchronized(lock) {
            slots.clear()
            sawTrackingId = false
            fired = false
            cancelPending()
        }
        val proc = root.startStreaming("exec cat ${device.path}")
        process = proc
        thread(isDaemon = true, name = "hold-reader-err") {
            runCatching {
                proc.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { AgentLog.w(TAG, "cat: $it") }
                }
            }
        }
        // struct input_event is {timeval, u16 type, u16 code, s32 value}: 24 bytes on 64-bit, 16 on 32-bit.
        val recordSize = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) 24 else 16
        val buffer = ByteArray(recordSize)
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val input = DataInputStream(BufferedInputStream(proc.inputStream, 8192))
        try {
            while (running) {
                input.readFully(buffer)
                val off = recordSize - 8
                handle(
                    type = bb.getShort(off).toInt() and 0xffff,
                    code = bb.getShort(off + 2).toInt() and 0xffff,
                    value = bb.getInt(off + 4),
                )
            }
        } catch (e: EOFException) {
            AgentLog.w(TAG, "event stream ended")
        } finally {
            runCatching { proc.destroy() }
            process = null
            synchronized(lock) {
                slots.clear()
                cancelPending()
            }
            if (!running) {
                // The root `cat` may outlive its su client; make sure it releases the input device.
                runCatching { root.run("pkill -f 'cat ${device.path}'", 5_000) }
            }
        }
    }

    private fun handle(type: Int, code: Int, value: Int) {
        synchronized(lock) {
            when (type) {
                EV_ABS -> when (code) {
                    ABS_MT_SLOT -> currentSlot = value.coerceIn(0, MAX_SLOTS - 1)
                    ABS_MT_TRACKING_ID -> {
                        sawTrackingId = true
                        if (value < 0) {
                            slots.remove(currentSlot)
                        } else {
                            slots[currentSlot] = newTouch(currentSlot)
                        }
                        onCountChanged()
                    }
                    ABS_MT_POSITION_X -> {
                        slotX[currentSlot] = value
                        slots[currentSlot]?.let { updateX(it, value) }
                    }
                    ABS_MT_POSITION_Y -> {
                        slotY[currentSlot] = value
                        slots[currentSlot]?.let { updateY(it, value) }
                    }
                    ABS_X -> if (!sawTrackingId) {
                        slotX[0] = value
                        slots[0]?.let { updateX(it, value) }
                    }
                    ABS_Y -> if (!sawTrackingId) {
                        slotY[0] = value
                        slots[0]?.let { updateY(it, value) }
                    }
                }
                EV_KEY -> if (code == BTN_TOUCH && !sawTrackingId) {
                    if (value != 0) {
                        if (!slots.containsKey(0)) slots[0] = newTouch(0)
                    } else {
                        slots.clear()
                    }
                    onCountChanged()
                }
                EV_SYN -> if (code == SYN_REPORT) slots.values.forEach { it.frames++ }
            }
        }
    }

    private fun newTouch(slot: Int): Touch = Touch(now()).also {
        it.startX = slotX[slot]
        it.startY = slotY[slot]
        it.x = it.startX
        it.y = it.startY
    }

    private fun updateX(t: Touch, v: Int) {
        if (t.frames == 0) t.startX = v
        t.x = v
        checkMoved(t)
    }

    private fun updateY(t: Touch, v: Int) {
        if (t.frames == 0) t.startY = v
        t.y = v
        checkMoved(t)
    }

    private fun checkMoved(t: Touch) {
        if (!t.moved && (abs(t.x - t.startX) > slop || abs(t.y - t.startY) > slop)) t.moved = true
    }

    /** Called under [lock] whenever the number of active contacts changes. */
    private fun onCountChanged() {
        val n = slots.size
        if (n == 0) {
            fired = false
            groupSince = 0
            cancelPending()
            return
        }
        val need = settings.holdFingers.coerceIn(1, 5)
        if (n == need && !fired) {
            val since = slots.values.maxOf { it.downAt }
            if (since != groupSince) {
                groupSince = since
                schedule(since)
            }
        } else {
            groupSince = 0
            cancelPending()
        }
    }

    private fun schedule(since: Long) {
        cancelPending()
        val delay = (settings.holdMillis - (now() - since)).coerceAtLeast(0)
        pending = timer.schedule({ fireIfStillHeld(since) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun cancelPending() {
        pending?.cancel(false)
        pending = null
    }

    private fun fireIfStillHeld(since: Long) {
        val fire = synchronized(lock) {
            if (!running || fired || groupSince != since) return
            if (slots.size != settings.holdFingers.coerceIn(1, 5)) return
            if (slots.values.any { it.moved }) {
                AgentLog.d(TAG, "hold cancelled: finger moved")
                return
            }
            if (!powerManager.isInteractive) return
            fired = true
            true
        }
        if (fire) {
            AgentLog.d(TAG, "hold detected after ${settings.holdMillis} ms")
            mainHandler.post {
                runCatching { onHold() }.onFailure { AgentLog.e(TAG, "onHold callback failed", it) }
            }
        }
    }

    private fun now(): Long = SystemClock.uptimeMillis()

    companion object {
        private const val TAG = "Hold"
        private const val MAX_SLOTS = 64

        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03
        private const val SYN_REPORT = 0x00
        private const val BTN_TOUCH = 0x14a
        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39

        private val DEVICE_LINE = Regex("""add device \d+:\s*(/dev/input/event\d+)""")
        private val NAME_LINE = Regex("""name:\s*"(.*)"""")
        private val ABS_X_LINE = Regex("""(?:ABS_MT_POSITION_X|\b0035)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
        private val ABS_Y_LINE = Regex("""(?:ABS_MT_POSITION_Y|\b0036)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")

        /** Parses `getevent -p[l]` output into candidate multi-touch devices. */
        fun parseGetevent(output: String): List<TouchDevice> {
            val devices = mutableListOf<TouchDevice>()
            var path: String? = null
            var name = ""
            var maxX = 0
            var maxY = 0
            var direct = false

            fun flush() {
                val p = path
                if (p != null && maxX > 0 && maxY > 0) devices += TouchDevice(p, name, maxX, maxY, direct)
                path = null; name = ""; maxX = 0; maxY = 0; direct = false
            }

            for (raw in output.lineSequence()) {
                val line = raw.trim()
                DEVICE_LINE.find(line)?.let {
                    flush()
                    path = it.groupValues[1]
                    return@let
                }
                if (path == null) continue
                NAME_LINE.find(line)?.let { name = it.groupValues[1] }
                ABS_X_LINE.find(line)?.let { maxX = it.groupValues[2].toInt() - it.groupValues[1].toInt() }
                ABS_Y_LINE.find(line)?.let { maxY = it.groupValues[2].toInt() - it.groupValues[1].toInt() }
                if (line.contains("INPUT_PROP_DIRECT")) direct = true
            }
            flush()
            return devices
        }
    }
}
