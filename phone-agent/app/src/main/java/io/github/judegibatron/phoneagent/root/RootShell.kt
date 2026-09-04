package io.github.judegibatron.phoneagent.root

import io.github.judegibatron.phoneagent.core.AgentLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs commands through `su -c` (Magisk, KernelSU and APatch all accept this form).
 * Every call spawns a fresh su process; that costs ~50-100 ms but never leaves a hung shell behind.
 */
class RootShell {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0

        /** stdout followed by stderr (tagged), trimmed. */
        fun combined(): String = buildString {
            append(stdout.trimEnd())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("[stderr] ").append(stderr.trimEnd())
            }
        }
    }

    @Volatile
    private var available: Boolean? = null

    /** Last known root state without running anything; null until the first check completes. */
    val knownAvailability: Boolean? get() = available

    /** True when `su -c id` reports uid 0. The first call may pop the root manager's grant dialog. */
    fun isAvailable(refresh: Boolean = false): Boolean {
        if (!refresh) available?.let { return it }
        val result = runCatching { exec(listOf("su", "-c", "id"), 25_000) }.getOrNull()
        val ok = result != null && result.exitCode == 0 && result.stdout.contains("uid=0")
        available = ok
        AgentLog.d(TAG, if (ok) "root available" else "root unavailable: ${result?.combined() ?: "su missing"}")
        return ok
    }

    fun run(command: String, timeoutMs: Long = 15_000): Result = exec(listOf("su", "-c", command), timeoutMs)

    /** Same as [run] but as the app's own uid, for the no-root fallback of the shell tool. */
    fun runUnprivileged(command: String, timeoutMs: Long = 15_000): Result =
        exec(listOf("sh", "-c", command), timeoutMs)

    /** Runs a root command and returns raw stdout bytes (used for `screencap -p`). */
    fun runBinary(command: String, timeoutMs: Long = 20_000): ByteArray? {
        val process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: IOException) {
            AgentLog.e(TAG, "cannot start su", e)
            return null
        }
        val buffer = ByteArrayOutputStream()
        val reader = thread(isDaemon = true, name = "su-binary") {
            runCatching { process.inputStream.use { it.copyTo(buffer) } }
        }
        val errDrain = thread(isDaemon = true, name = "su-binary-err") {
            runCatching { process.errorStream.bufferedReader().use { it.readText() } }
        }
        process.outputStream.close()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            errDrain.join(500)
            AgentLog.w(TAG, "binary command timed out: $command")
            return null
        }
        reader.join(2000)
        errDrain.join(500)
        return if (process.exitValue() == 0) buffer.toByteArray() else null
    }

    /** Starts a long-lived root process whose stdout the caller streams (the hold detector). */
    fun startStreaming(command: String): Process =
        ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()

    private fun exec(cmd: List<String>, timeoutMs: Long): Result {
        val process = try {
            ProcessBuilder(cmd).start()
        } catch (e: IOException) {
            return Result(127, "", "cannot start ${cmd.first()}: ${e.message}")
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = thread(isDaemon = true, name = "su-out") {
            runCatching { process.inputStream.bufferedReader().use { out.append(it.readText()) } }
        }
        val errReader = thread(isDaemon = true, name = "su-err") {
            runCatching { process.errorStream.bufferedReader().use { err.append(it.readText()) } }
        }
        process.outputStream.close()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            outReader.join(1000)
            errReader.join(1000)
            return Result(124, out.toString(), err.toString() + "\n[timed out after ${timeoutMs}ms]")
        }
        outReader.join(2000)
        errReader.join(2000)
        return Result(process.exitValue(), out.toString(), err.toString())
    }

    private companion object {
        const val TAG = "Root"
    }
}
