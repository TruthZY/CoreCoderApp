package com.corecoder.app.core.tools

import com.corecoder.app.core.exec.CommandExecutor
import com.google.gson.JsonObject

/**
 * Find files matching a glob pattern.
 * Routes through [CommandExecutor] — uses shell `find` command.
 */
class GlobTool(
    private val executor: CommandExecutor
) : Tool {
    override val name = "glob"
    override val description =
        "Find files matching a glob pattern. " +
        "Supports ** for recursive matching (e.g. '**/*.py')."

    override val parameters: JsonObject = run {
        val props = JsonObject()

        val pattern = JsonObject()
        pattern.addProperty("type", "string")
        pattern.addProperty("description", "Glob pattern, e.g. '**/*.py' or 'src/**/*.ts'")
        props.add("pattern", pattern)

        val path = JsonObject()
        path.addProperty("type", "string")
        path.addProperty("description", "Directory to search in (default: cwd)")
        props.add("path", path)

        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", props)
        val required = com.google.gson.JsonArray()
        required.add("pattern")
        params.add("required", required)
        params
    }

    override suspend fun execute(args: Map<String, Any?>): String {
        val pattern = args["pattern"]?.toString() ?: return "Error: pattern is required"
        val pathStr = args["path"]?.toString() ?: "."

        val q = ShellHelper.quote(pathStr)

        // Safety: refuse to scan system root directories
        if (pathStr == "/" || pathStr == "/system" || pathStr == "/data" || pathStr == "/storage") {
            return "Error: refusing to scan system directory '$pathStr'. Please specify a more specific path."
        }

        // Convert glob pattern to find-compatible name pattern
        // `find` uses -name for single component, -path for full path patterns with **
        val hasDoubleStar = pattern.contains("**")
        val findNameArg = if (hasDoubleStar) {
            // Convert ** to shell-compatible pattern for -path
            val pathPattern = pattern
                .replace("**/", "%")
                .replace("**", "%")
                .replace("*", "*")
            // Use -path with the pattern
            null to pathPattern
        } else {
            // Simple pattern: just match filename
            pattern to null
        }

        val cmd = buildString {
            append("find '$q'")

            // Skip common junk dirs
            append(" \\( -name .git -o -name node_modules -o -name __pycache__ -o -name .venv -o -name build -o -name dist \\) -prune -o")

            append(" -type f")

            if (findNameArg.first != null) {
                // Simple glob match on filename
                append(" -name '${ShellHelper.quote(findNameArg.first!!)}'")
            } else if (findNameArg.second != null) {
                // Path-based glob with **
                append(" -path '${ShellHelper.quote(findNameArg.second!!)}'")
            }

            append(" -printf '%T@ %p\\n'")
            append(" | sort -rn | head -100 | cut -d' ' -f2-")
        }

        val result = executor.execute(cmd)
        val err = ShellHelper.checkError(result)
        if (err != null) return err

        val output = result.stdout.trim()
        if (output.isEmpty()) return "No files matched."

        val lines = output.lines()
        val total = lines.size
        val shown = lines.take(100)

        return if (total > 100) {
            "${shown.joinToString("\n")}\n... ($total matches, showing first 100)"
        } else {
            shown.joinToString("\n")
        }
    }
}
