package io.github.judegibatron.phoneagent.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import java.time.Duration

/** One HTTP client per API key for the life of the process; rebuilt (and the old one closed) when the key changes. */
object ClaudeClients {

    private var currentKey: String? = null
    private var current: AnthropicClient? = null

    @Synchronized
    fun get(apiKey: String): AnthropicClient {
        current?.let { if (currentKey == apiKey) return it }
        current?.let { runCatching { it.close() } }
        val fresh = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(120))
            .maxRetries(2)
            .build()
        current = fresh
        currentKey = apiKey
        return fresh
    }
}
