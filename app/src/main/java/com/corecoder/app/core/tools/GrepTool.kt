package com.corecoder.app.core.tools

import com.corecoder.app.core.exec.CommandExecutor
import com.google.gson.JsonObject

/**
 * Content search with regex support.
 * Routes through [CommandExecutor] — uses shell `grep` command.
 */
class GrepTool(
    private val executor: CommandExecutor
) : Tool {
    override val name = "grep"
    override val description =
        "Search file contents with regex. " +
        "Returns matching lines with file path and line number."

    override val parameters: JsonObject = run {
        val props = JsonObject()

        val pattern = JsonObject()
        pattern.addProperty("type", "string")
        pattern.addProperty("description", "Regex pattern to search for")
        props.add("pattern", pattern)

        val path = JsonObject()
        path.addProperty("type", "string")
        path.addProperty("description", "File or directory to search (default: cwd)")
        props.add("path", path)

        val include = JsonObject()
        include.addProperty("type", "string")
        include.addProperty("description", "Only search files matching this glob (e.g. '*.py')")
        props.add("include", include)

        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", props)
        val required = com.google.gson.JsonArray()
        required.add("pattern")
        params.add("required", required)
        params
    }

    override suspend fun execute(args: Map<String, Any?>): String {
        val patternStr = args["pattern"]?.toString() ?: return "Error: pattern is required"
        val pathStr = args["path"]?.toString() ?: "."
        val include = args["include"]?.toString()

        val q = ShellHelper.quote(pathStr)
        val qPattern = ShellHelper.quote(patternStr)

        // Safety: refuse to scan system root directories
        if (pathStr == "/" || pathStr == "/system" || pathStr == "/data" || pathStr == "/storage") {
            return "Error: refusing to scan system directory '$pathStr'. Please specify a more specific path."
        }

        val cmd = buildString {
            // Use grep with extended regex, recursive, with line numbers
            append("grep -rnE")

            // Skip junk directories via --exclude-dir
            for (dir in SKIP_DIRS) {
                append(" --exclude-dir=$dir")
            }

            // Include filter
            if (include != null) {
                append(" --include='${ShellHelper.quote(include)}'")
            }

            append(" '$qPattern'")
            append(" '$q'")

            // Limit output to 200 matches
            append(" | head -200")
        }

        val result = executor.execute(cmd)

        // grep returns exit code 1 when no matches found — that's not an error
        if (!result.isSuccess && result.exitCode != 1) {
            val msg = result.stderr.ifBlank { result.stdout }
            return "Error (exit ${result.exitCode}): $msg"
        }

        val output = result.stdout.trim()
        if (output.isEmpty()) return "No matches found."

        val lines = output.lines()
        val suffix = if (lines.size >= 200) "\n... (200 match limit reached)" else ""
        return lines.joinToString("\n") + suffix
    }

    companion object {
        private val SKIP_DIRS = setOf(
            ".git", "node_modules", "__pycache__", ".venv", "venv", ".tox", "dist", "build"
        )
    }
}
