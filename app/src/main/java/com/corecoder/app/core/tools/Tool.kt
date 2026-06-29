package com.corecoder.app.core.tools

import com.google.gson.JsonObject

/**
 * Base interface for all tools.
 * Corresponds to Python's Tool ABC (tools/base.py).
 */
interface Tool {
    /** Unique tool name used in LLM function calling. */
    val name: String

    /** Description shown to the LLM. */
    val description: String

    /** JSON Schema for the tool's parameters. */
    val parameters: JsonObject

    /** Execute the tool with given arguments and return a text result. */
    suspend fun execute(args: Map<String, Any?>): String

    /** Generate OpenAI function-calling schema. */
    fun schema(): JsonObject {
        val fn = JsonObject()
        fn.addProperty("name", name)
        fn.addProperty("description", description)
        fn.add("parameters", parameters)

        val wrapper = JsonObject()
        wrapper.addProperty("type", "function")
        wrapper.add("function", fn)
        return wrapper
    }
}
