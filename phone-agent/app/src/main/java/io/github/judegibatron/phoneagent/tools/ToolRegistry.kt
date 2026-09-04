package io.github.judegibatron.phoneagent.tools

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolUnion
import io.github.judegibatron.phoneagent.core.AgentLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** The fixed, ordered tool set handed to Claude. Order is stable so the prompt prefix stays cacheable. */
class ToolRegistry(val tools: List<AgentTool>) {

    fun find(name: String): AgentTool? = tools.firstOrNull { it.spec.name == name }

    fun toBetaTools(): List<BetaToolUnion> = tools.map { tool ->
        BetaToolUnion.ofBetaTool(
            BetaTool.builder()
                .name(tool.spec.name)
                .description(tool.spec.description)
                .inputSchema(inputSchema(tool.spec))
                .build(),
        )
    }

    private fun inputSchema(spec: ToolSpec): BetaTool.InputSchema {
        val properties = BetaTool.InputSchema.Properties.builder()
        spec.properties.forEach { (key, schema) -> properties.putAdditionalProperty(key, JsonValue.from(schema)) }
        val builder = BetaTool.InputSchema.builder().properties(properties.build())
        if (spec.required.isNotEmpty()) builder.required(spec.required)
        return builder.build()
    }

    /** Runs a tool with the confirmation gate, a timeout, and exception-to-result conversion. */
    suspend fun execute(name: String, args: JSONObject, ctx: ToolContext): ToolOutput {
        val tool = find(name) ?: return ToolOutput.error("Unknown tool '$name'.")
        if (tool.spec.dangerous && ctx.settings.confirmFor(name)) {
            ctx.status("Waiting for your confirmation")
            val confirmed = try {
                ctx.confirm(tool.confirmPrompt(args))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentLog.e(TAG, "confirmation failed", e)
                false
            }
            if (!confirmed) {
                return ToolOutput.text("The user did not confirm, so nothing was done. Do not retry unless they ask again.")
            }
        }
        return try {
            withTimeout(tool.spec.timeoutMs) { tool.run(args, ctx) }
        } catch (e: TimeoutCancellationException) {
            ToolOutput.error("'$name' timed out after ${tool.spec.timeoutMs / 1000} seconds.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ToolOutput.error("'$name' needs a permission the app does not have: ${e.message}")
        } catch (e: Exception) {
            AgentLog.e(TAG, "tool $name failed", e)
            ToolOutput.error("'$name' failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Tools"

        fun standard(): ToolRegistry = ToolRegistry(
            listOf(
                GetDeviceStateTool(),
                ListMediaTool(),
                MediaControlTool(),
                FindContactTool(),
                SendSmsTool(),
                MakeCallTool(),
                OpenAppTool(),
                ListAppsTool(),
                OpenUrlTool(),
                NavigateTool(),
                SetAlarmTool(),
                SetTimerTool(),
                SetVolumeTool(),
                SetBrightnessTool(),
                FlashlightTool(),
                DoNotDisturbTool(),
                ConnectivityTool(),
                SetClipboardTool(),
                ReadNotificationsTool(),
                ScreenReadTool(),
                ScreenTapTool(),
                ScreenTypeTool(),
                ScreenScrollTool(),
                ScreenSwipeTool(),
                PressButtonTool(),
                TakeScreenshotTool(),
                WaitTool(),
                RunShellTool(),
                AskUserTool(),
            ),
        )
    }
}
