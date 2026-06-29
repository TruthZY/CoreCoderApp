package com.corecoder.app.core

/**
 * Configuration data class for the app.
 * Corresponds to Python's config.py (57 lines).
 *
 * On Android, config is stored in DataStore (preferences) instead of env vars.
 */
data class AppConfig(
    val model: String = "gpt-4o",
    val apiKey: String = "",
    val baseUrl: String? = null,
    val maxTokens: Int = 4096,
    val temperature: Float = 0f,
    val maxContextTokens: Int = 128_000,
    val provider: String = "openai"
) {
    companion object {
        /** Pre-configured provider presets. */
        val PRESETS = mapOf(
            "OpenAI" to AppConfig(
                model = "gpt-4o",
                baseUrl = "https://api.openai.com/v1"
            ),
            "DeepSeek" to AppConfig(
                model = "deepseek-chat",
                baseUrl = "https://api.deepseek.com"
            ),
            "Kimi" to AppConfig(
                model = "kimi-k2.5",
                baseUrl = "https://api.moonshot.ai/v1"
            ),
            "Qwen" to AppConfig(
                model = "qwen-max",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
            ),
            "Ollama" to AppConfig(
                model = "qwen2.5-coder",
                baseUrl = "http://localhost:11434/v1",
                apiKey = "ollama"
            ),
            "OpenRouter" to AppConfig(
                model = "anthropic/claude-sonnet-4-6",
                baseUrl = "https://openrouter.ai/api/v1"
            )
        )
    }
}
