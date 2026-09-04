package io.github.judegibatron.phoneagent.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import io.github.judegibatron.phoneagent.core.AgentLog
import io.github.judegibatron.phoneagent.core.Settings
import io.github.judegibatron.phoneagent.tools.ToolContext
import io.github.judegibatron.phoneagent.tools.ToolOutput
import io.github.judegibatron.phoneagent.tools.ToolRegistry
import kotlinx.coroutines.future.await
import org.json.JSONObject

/**
 * One conversation with Claude for one voice session. Holds the message history, runs the
 * tool-use loop (request -> execute tool_use blocks -> send tool_result -> repeat) and returns the
 * final spoken text for each user utterance.
 */
class ClaudeAgent(
    private val settings: Settings,
    private val tools: ToolRegistry,
    private val deviceContext: () -> String,
) {
    class TurnResult(val text: String, val toolsUsed: List<String>, val suppressFollowUp: Boolean)

    private val history = mutableListOf<BetaMessageParam>()
    private val model: String = settings.model
    private val apiKey: String = settings.apiKey ?: ""

    private val client: AnthropicClient get() = ClaudeClients.get(apiKey)

    suspend fun runTurn(userText: String, ctx: ToolContext, onStatus: (String) -> Unit): TurnResult {
        val turnStart = history.size
        history += userMessage(userText)
        val used = mutableListOf<String>()
        var suppressFollowUp = false
        var rounds = 0

        while (rounds < settings.maxToolRounds) {
            rounds++
            onStatus(if (rounds == 1) "Thinking" else "Thinking (step $rounds)")
            // The async client returns a future; await() makes a hold-to-cancel take effect immediately
            // instead of waiting for the HTTP round-trip to finish.
            val response: BetaMessage = client.async().beta().messages().create(buildParams()).await()
            logUsage(response)
            history += response.toParam()

            val stop = response.stopReason().orElse(null)?.value()
            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" ").trim()

            when {
                stop == BetaStopReason.Value.TOOL_USE && toolUses.isNotEmpty() -> {
                    val results = ArrayList<BetaContentBlockParam>(toolUses.size)
                    for (toolUse in toolUses) {
                        used += toolUse.name()
                        onStatus(describe(toolUse))
                        val output = tools.execute(toolUse.name(), inputOf(toolUse), ctx)
                        if (output.suppressFollowUp) suppressFollowUp = true
                        AgentLog.d(TAG, "tool ${toolUse.name()} -> ${(if (output.isError) "ERROR " else "") + output.text.take(160)}")
                        results += toolResult(toolUse.id(), output)
                    }
                    // All results for one assistant turn go back in a single user message.
                    history += BetaMessageParam.builder()
                        .role(BetaMessageParam.Role.USER)
                        .contentOfBetaContentBlockParams(results)
                        .build()
                }
                stop == BetaStopReason.Value.PAUSE_TURN -> continue
                stop == BetaStopReason.Value.REFUSAL -> {
                    // A refused turn may carry an empty assistant message, which the API rejects on
                    // replay; roll the whole turn back so a follow-up starts from a clean history.
                    while (history.size > turnStart) history.removeAt(history.lastIndex)
                    val why = response.stopDetails().orElse(null)?.explanation()?.orElse(null)
                    return TurnResult(why?.let { "I can't help with that: $it" } ?: "I can't help with that request.", used, suppressFollowUp)
                }
                stop == BetaStopReason.Value.MAX_TOKENS -> {
                    return TurnResult(text.ifBlank { "I ran out of room while answering. Could you ask again more simply?" }, used, suppressFollowUp)
                }
                else -> return TurnResult(text.ifBlank { "Done." }, used, suppressFollowUp)
            }
        }
        return TurnResult("I hit my step limit before finishing. Here's where I got to: I used ${used.distinct().joinToString()}.", used, suppressFollowUp)
    }

    /**
     * The first user turn carries the volatile device context as its own text block. Keeping it out
     * of the system array means the cached prefix (tools + static instructions) never changes and
     * the conversation stays append-only for the top-level cache breakpoint.
     */
    private fun userMessage(userText: String): BetaMessageParam {
        val builder = BetaMessageParam.builder().role(BetaMessageParam.Role.USER)
        if (history.isEmpty()) {
            builder.contentOfBetaContentBlockParams(
                listOf(
                    BetaContentBlockParam.ofText(deviceContext() + " (Call get_device_state if you need fresher facts.)"),
                    BetaContentBlockParam.ofText(userText),
                ),
            )
        } else {
            builder.content(userText)
        }
        return builder.build()
    }

    private fun buildParams(): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(settings.maxTokens.toLong())
            .system(
                MessageCreateParams.System.ofBetaTextBlockParams(
                    listOf(
                        // Cache breakpoint after the stable prefix (tools + static instructions).
                        BetaTextBlockParam.builder()
                            .text(SystemPrompt.STATIC)
                            .cacheControl(BetaCacheControlEphemeral.builder().build())
                            .build(),
                    ),
                ),
            )
            .messages(history.toList())
            // Auto-cache the growing conversation as well.
            .cacheControl(BetaCacheControlEphemeral.builder().build())

        tools.toBetaTools().forEach { builder.addTool(it) }
        if (settings.webSearch) {
            // The dynamic-filtering variant needs a current-generation model; Haiku 4.5 takes the basic one.
            if (model.contains("haiku")) builder.addTool(BetaWebSearchTool20250305.builder().maxUses(3L).build())
            else builder.addTool(BetaWebSearchTool20260209.builder().maxUses(3L).build())
        }

        if (!model.contains("haiku")) {
            // Adaptive thinking is the only thinking mode on current models; effort controls depth.
            builder.thinking(BetaThinkingConfigAdaptive.builder().build())
            builder.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(settings.effort)).build())
        }
        if (model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5")) {
            // Server-side refusal fallbacks: a safety-classifier decline re-runs on a fallback model.
            builder.addBeta("server-side-fallback-2026-07-01")
            builder.fallbacks(BetaFallbacksParam.ofDefault())
        }
        return builder.build()
    }

    private fun inputOf(toolUse: BetaToolUseBlock): JSONObject = try {
        val map = toolUse.input(Map::class.java)
        JSONObject(map)
    } catch (e: Exception) {
        AgentLog.w(TAG, "could not parse tool input for ${toolUse.name()}: ${e.message}")
        JSONObject()
    }

    private fun toolResult(toolUseId: String, output: ToolOutput): BetaContentBlockParam {
        val builder = BetaToolResultBlockParam.builder().toolUseId(toolUseId)
        if (output.isError) builder.isError(true)
        val image = output.imageJpegBase64
        if (image == null) {
            builder.content(output.text)
        } else {
            builder.contentOfBlocks(
                listOf(
                    BetaToolResultBlockParam.Content.Block.ofText(output.text),
                    BetaToolResultBlockParam.Content.Block.ofImage(
                        BetaImageBlockParam.builder()
                            .source(
                                BetaBase64ImageSource.builder()
                                    .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(image)
                                    .build(),
                            )
                            .build(),
                    ),
                ),
            )
        }
        return BetaContentBlockParam.ofToolResult(builder.build())
    }

    private fun describe(toolUse: BetaToolUseBlock): String {
        val args = inputOf(toolUse)
        val hint = listOf("action", "name", "to", "button", "direction", "feature", "query", "command", "url", "destination")
            .firstNotNullOfOrNull { key -> args.optString(key).takeIf { it.isNotEmpty() } }
        return toolUse.name().replace('_', ' ') + (hint?.let { ": ${it.take(40)}" } ?: "")
    }

    private fun logUsage(response: BetaMessage) {
        val usage = response.usage()
        AgentLog.d(
            TAG,
            "model=${response.model()} stop=${response.stopReason().orElse(null)?.asString()} " +
                "in=${usage.inputTokens()} out=${usage.outputTokens()} " +
                "cacheRead=${usage.cacheReadInputTokens().orElse(0L)} cacheWrite=${usage.cacheCreationInputTokens().orElse(0L)}",
        )
    }

    private companion object {
        const val TAG = "Claude"
    }
}
