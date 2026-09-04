package io.github.judegibatron.phoneagent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RunShellTool : AgentTool(
    ToolSpec(
        name = "run_shell",
        description = "Run a shell command as root (su) and return its output. Use for anything no other tool covers: " +
            "settings get/put, am, pm, svc, input, dumpsys, cmd, file access, etc. Output is truncated to 6000 characters. " +
            "Without root the command runs as the app's own unprivileged user.",
        properties = mapOf(
            "command" to prop("string", "POSIX shell command line"),
            "timeout_seconds" to prop("integer", "1-60, default 20"),
        ),
        required = listOf("command"),
        dangerous = true,
        timeoutMs = 75_000,
    ),
) {
    override fun confirmPrompt(args: JSONObject): String =
        "Run this shell command as root: ${args.str("command")?.take(140) ?: ""}?"

    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        val command = args.str("command") ?: return@withContext ToolOutput.error("command is required")
        val timeoutMs = (args.int("timeout_seconds") ?: 20).coerceIn(1, 60) * 1000L
        val rooted = ctx.root.isAvailable()
        val result = if (rooted) ctx.root.run(command, timeoutMs) else ctx.root.runUnprivileged(command, timeoutMs)
        val output = result.combined().let { if (it.length > 6000) it.take(6000) + "\n[truncated]" else it }
        val prefix = if (rooted) "" else "(no root: ran as the app's own user) "
        if (result.ok) ToolOutput.text(prefix + output.ifBlank { "(no output, exit code 0)" })
        else ToolOutput.error("${prefix}exit code ${result.exitCode}: ${output.ifBlank { "(no output)" }}")
    }
}
