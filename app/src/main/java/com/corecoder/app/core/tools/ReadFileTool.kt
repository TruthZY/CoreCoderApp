package com.corecoder.app.core.tools

import com.corecoder.app.core.exec.CommandExecutor
import com.google.gson.JsonObject

/**
 * Read file contents with line numbers.
 * Routes through [CommandExecutor] to operate in the correct filesystem
 * (e.g., Ubuntu proot home instead of Android app sandbox).
 */
class ReadFileTool(
    private val executor: CommandExecutor
) : Tool {
    override val name = "read_file"
    override val description = "Read a file's contents with line numbers. Always read a file before editing it."

    override val parameters: JsonObject = run {
        val props = JsonObject()

        val filePath = JsonObject()
        filePath.addProperty("type", "string")
        filePath.addProperty("description", "Path to the file")
        props.add("file_path", filePath)

        val offset = JsonObject()
        offset.addProperty("type", "integer")
        offset.addProperty("description", "Start line (1-based). Default 1.")
        props.add("offset", offset)

        val limit = JsonObject()
        limit.addProperty("type", "integer")
        limit.addProperty("description", "Max lines to read. Default 2000.")
        props.add("limit", limit)

        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", props)
        val required = com.google.gson.JsonArray()
        required.add("file_path")
        params.add("required", required)
        params
    }

    override suspend fun execute(args: Map<String, Any?>): String {
        val filePath = args["file_path"]?.toString() ?: return "Error: file_path is required"
        val offset = (args["offset"] as? Number)?.toInt() ?: 1
        val limit = (args["limit"] as? Number)?.toInt() ?: 2000

        val q = ShellHelper.quote(filePath)

        // Read file content via base64 to avoid encoding issues
        val readResult = executor.execute("base64 < '$q'")
        val err = ShellHelper.checkError(readResult)
        if (err != null) return err

        val content = ShellHelper.decodeB64(readResult.stdout)
        if (content.isEmpty()) return "(empty file)"

        val lines = content.lines()
        val total = lines.size
        val start = (offset - 1).coerceAtLeast(0)
        val end = (start + limit).coerceAtMost(total)

        if (start >= total) {
            return "Error: offset $offset exceeds file length ($total lines)"
        }

        val chunk = lines.subList(start, end)
        val numbered = chunk.mapIndexed { i, line -> "${start + i + 1}\t$line" }
        val result = StringBuilder(numbered.joinToString("\n"))

        if (total > end) {
            result.append("\n... ($total lines total, showing ${start + 1}-$end)")
        }

        return result.toString()
    }
}
