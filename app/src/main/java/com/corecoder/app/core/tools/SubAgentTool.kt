package com.corecoder.app.core.tools

import com.google.gson.JsonObject

/**
 * Sub-agent spawning (inspired by Claude Code's AgentTool, 1397 lines).
 * Corresponds to Python's AgentTool (tools/agent.py, 58 lines).
 *
 * For complex sub-tasks, spawn an independent agent with its own
 * conversation history and tool access. The sub-agent runs to
 * completion and returns a text summary.
 */
class SubAgentTool : Tool {
    override val name = "agent"
    override val description =
        "Spawn a sub-agent to handle a complex sub-task independently. " +
        "The sub-agent has its own context and tool access. Use this for: " +
        "researching a codebase, implementing a multi-step change in isolation, " +
        "or any task that would benefit from a fresh context window."

    override val parameters: JsonObject = run {
        val props = JsonObject()

        val task = JsonObject()
        task.addProperty("type", "string")
        task.addProperty("description", "What the sub-agent should accomplish")
        props.add("task", task)

        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", props)
        val required = com.google.gson.JsonArray()
        required.add("task")
        params.add("required", required)
        params
    }

    /** Set by Agent after construction to enable sub-agent spawning. */
    var agentFactory: ((maxRounds: Int) -> suspend (String) -> String)? = null

    override suspend fun execute(args: Map<String, Any?>): String {
        val task = args["task"]?.toString() ?: return "Error: task is required"

        if (agentFactory == null) {
            return "Error: agent tool not initialized (no parent agent)"
        }

        return try {
            val subAgentChat = agentFactory!!.invoke(20)
            val result = subAgentChat(task)

            // Trim long results to avoid blowing up parent's context
            val trimmed = if (result.length > 5000) {
                result.take(4500) + "\n... (sub-agent output truncated)"
            } else {
                result
            }
            "[Sub-agent completed]\n$trimmed"
        } catch (e: Exception) {
            "Sub-agent error: ${e.message}"
        }
    }
}
