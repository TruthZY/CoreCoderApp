package com.corecoder.app.core.tools

import com.corecoder.app.core.SkillManager
import com.corecoder.app.core.exec.CommandExecutor

/**
 * Tool registry - manages all available tools.
 * Corresponds to Python's tools/__init__.py.
 */
class ToolRegistry(tools: List<Tool> = emptyList()) {

    private val toolMap = mutableMapOf<String, Tool>()

    init {
        tools.forEach { register(it) }
    }

    fun register(tool: Tool) {
        toolMap[tool.name] = tool
    }

    fun get(name: String): Tool? = toolMap[name]

    fun all(): List<Tool> = toolMap.values.toList()

    fun schemas(): List<com.google.gson.JsonObject> = all().map { it.schema() }

    companion object {
        /**
         * Create a default registry with all built-in tools.
         *
         * @param executor Command execution backend. **Required** — all file tools
         *                 and BashTool route through it for filesystem operations.
         * @param skillManager Skill manager for the load_skill tool.
         */
        fun createDefault(executor: CommandExecutor, skillManager: SkillManager? = null): ToolRegistry {
            val tools = mutableListOf<Tool>(
                ReadFileTool(executor),
                WriteFileTool(executor),
                EditFileTool(executor),
                GlobTool(executor),
                GrepTool(executor),
                BashTool(executor),
                SubAgentTool()
            )
            if (skillManager != null) {
                tools.add(LoadSkillTool(skillManager))
            }
            return ToolRegistry(tools)
        }
    }
}
