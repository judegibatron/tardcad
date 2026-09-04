package io.github.judegibatron.phoneagent.tools

import org.json.JSONObject

class AskUserTool : AgentTool(
    ToolSpec(
        name = "ask_user",
        description = "Ask the user one short spoken question and wait for their spoken answer. Use it to resolve ambiguity " +
            "(which contact, what to say, which app) before acting, instead of guessing.",
        properties = mapOf("question" to prop("string", "One short, specific question")),
        required = listOf("question"),
        timeoutMs = 150_000,
    ),
) {
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        val question = args.str("question") ?: return ToolOutput.error("question is required")
        val answer = ctx.askUser(question)
        return ToolOutput.text(
            if (answer != null) "The user answered: \"$answer\""
            else "The user did not answer. Do not repeat the question; either proceed with the safest interpretation or end with a brief note.",
        )
    }
}
