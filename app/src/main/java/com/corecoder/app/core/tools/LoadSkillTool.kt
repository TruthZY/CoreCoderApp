package com.corecoder.app.core.tools

import com.corecoder.app.core.SkillManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Tool to load a Skill's full SKILL.md content on demand.
 *
 * The agent uses this after seeing a skill summary in the system prompt
 * to get the complete instructions when it decides a skill is relevant.
 */
class LoadSkillTool(
    private val skillManager: SkillManager
) : Tool {
    override val name = "load_skill"
    override val description = "Load the full content of a Skill by name. Use this to get detailed instructions for a specific skill listed in your available skills."

    override val parameters: JsonObject = run {
        val props = JsonObject()

        val skillName = JsonObject()
        skillName.addProperty("type", "string")
        skillName.addProperty("description", "The name (folder name) of the skill to load.")
        props.add("skill_name", skillName)

        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", props)
        val required = JsonArray()
        required.add("skill_name")
        params.add("required", required)
        params
    }

    override suspend fun execute(args: Map<String, Any?>): String {
        val skillName = args["skill_name"]?.toString()
            ?: return "Error: skill_name is required"

        val content = skillManager.readSkillContent(skillName)
            ?: return buildString {
                appendLine("Error: Skill '$skillName' not found or SKILL.md is missing.")
                appendLine("Available skills can be found in ~/corecoder/skills/")
            }

        return buildString {
            appendLine("# Skill: $skillName")
            appendLine()
            append(content)
        }
    }
}
