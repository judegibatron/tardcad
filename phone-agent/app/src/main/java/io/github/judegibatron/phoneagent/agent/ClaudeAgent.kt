package io.github.judegibatron.phoneagent.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Duration

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

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(settings.apiKey ?: "")
            .timeout(Duration.ofSeconds(120))
            .maxRetries(2)
            .build()
    }

    suspend fun runTurn(userText: String, ctx: ToolContext, onStatus: (String) -> Unit): TurnResult {
        history += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(userText).build()
        val used = mutableListOf<String>()
        var suppressFollowUp = false
        val context = deviceContext()
        var rounds = 0

        while (rounds < settings.maxToolRounds) {
            rounds++
            onStatus(if (rounds == 1) "Thinking" else "Thinking (step $rounds)")
            val response: BetaMessage = withContext(Dispatchers.IO) {
                client.beta().messages().create(buildParams(context))
            }
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

    private fun buildParams(context: String): MessageCreateParams {
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
                        BetaTextBlockParam.builder().text(context).build(),
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
