package com.corecoder.app.core

import com.corecoder.app.core.tools.SubAgentTool
import com.corecoder.app.core.tools.ToolRegistry
import com.corecoder.app.data.SkillEntity
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Result of an agent conversation, including text output and token usage.
 */
data class AgentResponse(
    val content: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/**
 * Core agent loop - the heart of CoreCoder.
 * Corresponds to Python's agent.py (122 lines).
 *
 * Pattern: user message -> LLM (with tools) -> tool calls? -> execute -> loop
 *                                     -> text reply? -> return to user
 *
 * Keeps looping until the LLM responds with plain text (no tool calls),
 * which means it's done working and ready to report back.
 */
class Agent(
    val llm: LLMClient,
    val toolRegistry: ToolRegistry,
    val maxContextTokens: Int = 128_000,
    val maxRounds: Int = 50,
    executorStatus: String = "ready",
    enabledSkills: List<SkillEntity> = emptyList()
) {
    val messages: MutableList<JsonObject> = mutableListOf()
    val context = ContextManager(maxTokens = maxContextTokens)

    private val systemPrompt: String = PromptBuilder.build(
        toolRegistry.all(),
        executorStatus,
        enabledSkills
    )

    init {
        // Wire up sub-agent capability
        for (tool in toolRegistry.all()) {
            if (tool is SubAgentTool) {
                tool.agentFactory = { subMaxRounds ->
                    { task: String ->
                        chatAsSubAgent(task, subMaxRounds)
                    }
                }
            }
        }
    }

    private fun fullMessages(): List<JsonObject> {
        val system = JsonObject()
        system.addProperty("role", "system")
        system.addProperty("content", systemPrompt)
        return listOf(system) + messages
    }

    /**
     * Process one user message. May involve multiple LLM/tool rounds.
     * Corresponds to Python's Agent.chat() method.
     */
    suspend fun chat(
        userInput: String,
        onToken: (String) -> Unit = {},
        onTool: (String, Map<String, Any?>) -> Unit = { _, _ -> }
    ): AgentResponse {
        val userMsg = JsonObject()
        userMsg.addProperty("role", "user")
        userMsg.addProperty("content", userInput)
        messages.add(userMsg)

        context.maybeCompress(messages, llm)

        var totalPrompt = 0
        var totalCompletion = 0

        repeat(maxRounds) {
            val resp = llm.chat(
                messages = fullMessages(),
                tools = toolRegistry.schemas(),
                onToken = onToken
            )
            totalPrompt += resp.promptTokens
            totalCompletion += resp.completionTokens

            // No tool calls -> LLM is done, return text
            if (resp.toolCalls.isEmpty()) {
                messages.add(resp.toMessage())
                return AgentResponse(
                    content = resp.content,
                    promptTokens = totalPrompt,
                    completionTokens = totalCompletion
                )
            }

            // Tool calls -> execute
            messages.add(resp.toMessage())

            val results = if (resp.toolCalls.size == 1) {
                val tc = resp.toolCalls[0]
                onTool(tc.name, tc.arguments)
                listOf(executeTool(tc))
            } else {
                // Parallel execution for multiple tool calls
                executeToolsParallel(resp.toolCalls, onTool)
            }

            for ((tc, result) in resp.toolCalls.zip(results)) {
                val toolMsg = JsonObject()
                toolMsg.addProperty("role", "tool")
                toolMsg.addProperty("tool_call_id", tc.id)
                toolMsg.addProperty("content", result)
                messages.add(toolMsg)
            }

            // Compress if tool outputs are big
            context.maybeCompress(messages, llm)
        }

        return AgentResponse(
            content = "(reached maximum tool-call rounds)",
            promptTokens = totalPrompt,
            completionTokens = totalCompletion
        )
    }

    private suspend fun executeTool(tc: ToolCall): String {
        val tool = toolRegistry.get(tc.name) ?: return "Error: unknown tool '${tc.name}'"
        return try {
            withContext(Dispatchers.IO) {
                tool.execute(tc.arguments)
            }
        } catch (e: IllegalArgumentException) {
            "Error: bad arguments for ${tc.name}: ${e.message}"
        } catch (e: Exception) {
            "Error executing ${tc.name}: ${e.message}"
        }
    }

    /**
     * Run multiple tool calls concurrently using coroutines.
     * Inspired by Claude Code's StreamingToolExecutor.
     */
    private suspend fun executeToolsParallel(
        toolCalls: List<ToolCall>,
        onTool: (String, Map<String, Any?>) -> Unit
    ): List<String> = coroutineScope {
        for (tc in toolCalls) {
            onTool(tc.name, tc.arguments)
        }

        val deferreds = toolCalls.map { tc ->
            async { executeTool(tc) }
        }
        deferreds.map { it.await() }
    }

    /**
     * Sub-agent chat with limited rounds and no sub-agent nesting.
     */
    private suspend fun chatAsSubAgent(task: String, subMaxRounds: Int): String {
        val subTools = toolRegistry.all().filter { it.name != "agent" }
        val subRegistry = ToolRegistry(subTools)
        val sub = Agent(
            llm = llm,
            toolRegistry = subRegistry,
            maxContextTokens = maxContextTokens,
            maxRounds = subMaxRounds
        )
        return sub.chat(task).content
    }

    /** Clear conversation history. */
    fun reset() {
        messages.clear()
    }
}
