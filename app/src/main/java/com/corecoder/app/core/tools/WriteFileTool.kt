package com.corecoder.app.core.tools

import com.corecoder.app.core.exec.CommandExecutor
import com.google.gson.JsonObject

/**
 * Create a new file or completely overwrite an existing one.
 * Routes through [CommandExecutor] — uses base64 encoding to safely
 * transfer arbitrary file content through the shell.
 */
class WriteFileTool(
    private val executor: CommandExecutor
) : Tool {
    override val name = "write_file"
    override val description =
        "Create a new file or completely overwrite an existing one. " +
        "For small edits to existing files, prefer edit_file instead."

    override val parameters: JsonObject = run {
        val props = JsonObject()

        val filePath = JsonObject()
        filePath.addProperty("type", "string")
        filePath.addProperty("description", "Path for the file")
        props.add("file_path", filePath)

        val content = JsonObject()
        content.addProperty("type", "string")
        content.addProperty("description", "Full file content to write")
        props.add("content", content)

        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", props)
        val required = com.google.gson.JsonArray()
        required.add("file_path")
        required.add("content")
        params.add("required", required)
        params
    }

    override suspend fun execute(args: Map<String, Any?>): String {
        val filePath = args["file_path"]?.toString() ?: return "Error: file_path is required"
        val content = args["content"]?.toString() ?: return "Error: content is required"

        val q = ShellHelper.quote(filePath)
        val b64 = ShellHelper.encodeB64(content)

        // mkdir -p to ensure parent dirs, then decode base64 and write
        val cmd = "mkdir -p \"\$(dirname '$q')\" && echo '$b64' | base64 -d > '$q'"
        val result = executor.execute(cmd)
        val err = ShellHelper.checkError(result)
        if (err != null) return err

        EditFileTool.changedFiles.add(filePath)
        val nLines = content.count { it == '\n' } +
                (if (content.isNotEmpty() && !content.endsWith("\n")) 1 else 0)
        return "Wrote $nLines lines to $filePath"
    }
}
