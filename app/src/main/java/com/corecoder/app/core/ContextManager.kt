package com.corecoder.app.core

import com.google.gson.JsonObject

/**
 * Multi-layer context compression.
 * Corresponds to Python's context.py (196 lines).
 *
 * Claude Code uses a 4-layer strategy. CoreCoder implements 3 layers:
 *   Layer 1 (toolSnip)   - replace verbose tool results with truncated versions
 *   Layer 2 (summarize)  - LLM-powered summary of old conversation
 *   Layer 3 (hardCollapse) - last resort: drop everything except summary + recent
 */
class ContextManager(private val maxTokens: Int = 128_000) {

    // Layer thresholds (fraction of maxTokens)
    private val snipAt = (maxTokens * 0.50).toInt()     // 50% -> snip tool outputs
    private val summarizeAt = (maxTokens * 0.70).toInt() // 70% -> LLM summarize
    private val collapseAt = (maxTokens * 0.90).toInt()  // 90% -> hard collapse

    /**
     * Apply compression layers as needed.
     * Returns true if any compression happened.
     */
    fun maybeCompress(messages: MutableList<JsonObject>, llm: LLMClient? = null): Boolean {
        var current = estimateTokens(messages)
        var compressed = false

        // Layer 1: snip verbose tool outputs
        if (current > snipAt) {
            if (snipToolOutputs(messages)) {
                compressed = true
                current = estimateTokens(messages)
            }
        }

        // Layer 2: LLM-powered summarization of old turns
        if (current > summarizeAt && messages.size > 10) {
            if (summarizeOld(messages, llm, keepRecent = 8)) {
                compressed = true
                current = estimateTokens(messages)
            }
        }

        // Layer 3: hard collapse - last resort
        if (current > collapseAt && messages.size > 4) {
            hardCollapse(messages, llm)
            compressed = true
        }

        return compressed
    }

    companion object {
        /** Rough token count. ~3 chars/token for mixed en/zh content. */
        fun approxTokens(text: String): Int = text.length / 3

        fun estimateTokens(messages: List<JsonObject>): Int {
            var total = 0
            for (m in messages) {
                m.get("content")?.let { content ->
                    if (!content.isJsonNull) {
                        total += approxTokens(content.asString)
                    }
                }
                m.get("tool_calls")?.let { tc ->
                    total += approxTokens(tc.toString())
                }
            }
            return total
        }

        /**
         * Layer 1: Truncate tool results over 1500 chars to their first/last lines.
         * Mirrors Claude Code's HISTORY_SNIP.
         */
        private fun snipToolOutputs(messages: MutableList<JsonObject>): Boolean {
            var changed = false
            for (m in messages) {
                if (m.get("role")?.asString != "tool") continue
                val content = m.get("content")?.asString ?: continue
                if (content.length <= 1500) continue

                val lines = content.lines()
                if (lines.size <= 6) continue

                // Keep first 3 + last 3 lines
                val snipped = buildString {
                    append(lines.take(3).joinToString("\n"))
                    append("\n... (${lines.size} lines, snipped to save context) ...\n")
                    append(lines.takeLast(3).joinToString("\n"))
                }
                m.addProperty("content", snipped)
                changed = true
            }
            return changed
        }

        /**
         * Layer 2: Summarize old conversation, keep recent messages intact.
         */
        private fun summarizeOld(
            messages: MutableList<JsonObject>,
            llm: LLMClient?,
            keepRecent: Int = 8
        ): Boolean {
            if (messages.size <= keepRecent) return false

            val old = messages.subList(0, messages.size - keepRecent).toList()
            val tail = messages.subList(messages.size - keepRecent, messages.size).toList()

            val summary = getSummary(old, llm)

            messages.clear()

            val summaryMsg = JsonObject()
            summaryMsg.addProperty("role", "user")
            summaryMsg.addProperty("content", "[Context compressed - conversation summary]\n$summary")
            messages.add(summaryMsg)

            val ackMsg = JsonObject()
            ackMsg.addProperty("role", "assistant")
            ackMsg.addProperty("content", "Got it, I have the context from our earlier conversation.")
            messages.add(ackMsg)

            messages.addAll(tail)
            return true
        }

        /**
         * Layer 3: Emergency compression. Keep only last 4 messages + summary.
         */
        private fun hardCollapse(messages: MutableList<JsonObject>, llm: LLMClient?) {
            val tailSize = if (messages.size > 4) 4 else 2
            val tail = messages.subList(messages.size - tailSize, messages.size).toList()
            val toSummarize = messages.subList(0, messages.size - tailSize).toList()
            val summary = getSummary(toSummarize, llm)

            messages.clear()

            val summaryMsg = JsonObject()
            summaryMsg.addProperty("role", "user")
            summaryMsg.addProperty("content", "[Hard context reset]\n$summary")
            messages.add(summaryMsg)

            val ackMsg = JsonObject()
            ackMsg.addProperty("role", "assistant")
            ackMsg.addProperty("content", "Context restored. Continuing from where we left off.")
            messages.add(ackMsg)

            messages.addAll(tail)
        }

        /** Generate summary via LLM or fallback to extraction. */
        private fun getSummary(messages: List<JsonObject>, llm: LLMClient?): String {
            val flat = flatten(messages)

            if (llm != null) {
                try {
                    val summaryRequest = listOf(
                        JsonObject().apply {
                            addProperty("role", "system")
                            addProperty(
                                "content",
                                "Compress this conversation into a brief summary. " +
                                "Preserve: file paths edited, key decisions made, " +
                                "errors encountered, current task state. " +
                                "Drop: verbose command output, code listings, " +
                                "redundant back-and-forth."
                            )
                        },
                        JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", flat.take(15000))
                        }
                    )
                    val resp = kotlinx.coroutines.runBlocking {
                        llm.chat(messages = summaryRequest)
                    }
                    return resp.content
                } catch (_: Exception) {
                    // Fallback
                }
            }

            return extractKeyInfo(messages)
        }

        private fun flatten(messages: List<JsonObject>): String {
            return messages.joinToString("\n") { m ->
                val role = m.get("role")?.asString ?: "?"
                val text = m.get("content")?.let {
                    if (it.isJsonNull) "" else it.asString
                } ?: ""
                "[$role] ${text.take(400)}"
            }
        }

        /** Fallback: extract file paths, errors, and decisions without LLM. */
        private fun extractKeyInfo(messages: List<JsonObject>): String {
            val filesSeen = mutableSetOf<String>()
            val errors = mutableListOf<String>()
            val filePattern = Regex("""[\w./\-]+\.\w{1,5}""")

            for (m in messages) {
                val text = m.get("content")?.let {
                    if (it.isJsonNull) "" else it.asString
                } ?: ""

                filePattern.findAll(text).forEach { filesSeen.add(it.value) }
                text.lines().forEach { line ->
                    if (line.contains("error", ignoreCase = true) || line.contains("Error")) {
                        errors.add(line.trim().take(150))
                    }
                }
            }

            val parts = mutableListOf<String>()
            if (filesSeen.isNotEmpty()) {
                parts.add("Files touched: ${filesSeen.sorted().take(20).joinToString(", ")}")
            }
            if (errors.isNotEmpty()) {
                parts.add("Errors seen: ${errors.take(5).joinToString("; ")}")
            }
            return parts.joinToString("\n").ifEmpty { "(no extractable context)" }
        }
    }
}
