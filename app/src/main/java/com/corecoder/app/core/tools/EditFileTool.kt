package com.corecoder.app.core.tools

import com.corecoder.app.core.exec.CommandExecutor
import com.google.gson.JsonObject

/**
 * Search-and-replace file editing (Claude Code's key innovation).
 * Routes through [CommandExecutor] for filesystem access.
 *
 * The core idea: instead of sending whole-file rewrites or line-number patches,
 * the LLM specifies an exact substring to find and its replacement. The substring
 * must appear exactly once in the file, which eliminates ambiguity.
 */
class EditFileTool(
    private val executor: CommandExecutor
) : Tool {
    override val name = "edit_file"
    override val description =
        "Edit a file by replacing an exact string match. " +
        "old_string must appear exactly once in the file for safety. " +
        "Include enough surrounding context to ensure uniqueness."

    override val parameters: JsonObject = run {
        val props = JsonObject()

        val filePath = JsonObject()
        filePath.addProperty("type", "string")
        filePath.addProperty("description", "Path to the file to edit")
        props.add("file_path", filePath)

        val oldString = JsonObject()
        oldString.addProperty("type", "string")
        oldString.addProperty("description", "Exact text to find (must be unique in file)")
        props.add("old_string", oldString)

        val newString = JsonObject()
        newString.addProperty("type", "string")
        newString.addProperty("description", "Replacement text")
        props.add("new_string", newString)

        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", props)
        val required = com.google.gson.JsonArray()
        required.add("file_path")
        required.add("old_string")
        required.add("new_string")
        params.add("required", required)
        params
    }

    override suspend fun execute(args: Map<String, Any?>): String {
        val filePath = args["file_path"]?.toString() ?: return "Error: file_path is required"
        val oldString = args["old_string"]?.toString() ?: return "Error: old_string is required"
        val newString = args["new_string"]?.toString() ?: return "Error: new_string is required"

        val q = ShellHelper.quote(filePath)

        // Step 1: Read current content via base64
        val readResult = executor.execute("base64 < '$q'")
        val readErr = ShellHelper.checkError(readResult)
        if (readErr != null) return readErr

        val content = ShellHelper.decodeB64(readResult.stdout)
        val occurrences = countOccurrences(content, oldString)

        if (occurrences == 0) {
            val preview = content.take(500) + if (content.length > 500) "..." else ""
            return "Error: old_string not found in $filePath.\nFile starts with:\n$preview"
        }
        if (occurrences > 1) {
            return "Error: old_string appears $occurrences times in $filePath. " +
                    "Include more surrounding lines to make it unique."
        }

        // Step 2: Do replacement in Kotlin
        val newContent = content.replaceFirst(oldString, newString)

        // Step 3: Write back via base64
        val b64 = ShellHelper.encodeB64(newContent)
        val writeResult = executor.execute("echo '$b64' | base64 -d > '$q'")
        val writeErr = ShellHelper.checkError(writeResult)
        if (writeErr != null) return writeErr

        changedFiles.add(filePath)
        val diff = unifiedDiff(content, newContent, filePath)
        return "Edited $filePath\n$diff"
    }

    private fun countOccurrences(text: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            idx = text.indexOf(sub, idx)
            if (idx == -1) break
            count++
            idx += sub.length
        }
        return count
    }

    companion object {
        /** Track files changed this session. */
        val changedFiles = mutableSetOf<String>()

        /** Generate a compact unified diff between old and new content. */
        fun unifiedDiff(old: String, new: String, filename: String, context: Int = 3): String {
            val oldLines = old.lines()
            val newLines = new.lines()

            val sb = StringBuilder()
            sb.appendLine("--- a/$filename")
            sb.appendLine("+++ b/$filename")

            val maxLen = maxOf(oldLines.size, newLines.size)
            var diffStart = -1

            for (i in 0 until maxLen) {
                val o = oldLines.getOrNull(i)
                val n = newLines.getOrNull(i)
                if (o != n) {
                    if (diffStart == -1) diffStart = i
                } else if (diffStart != -1) {
                    val hunkStart = (diffStart - context).coerceAtLeast(0)
                    val hunkEnd = (i + context).coerceAtMost(maxLen)
                    appendHunk(sb, oldLines, newLines, diffStart, i, hunkStart, hunkEnd)
                    diffStart = -1
                }
            }
            if (diffStart != -1) {
                val hunkStart = (diffStart - context).coerceAtLeast(0)
                appendHunk(sb, oldLines, newLines, diffStart, maxLen, hunkStart, maxLen)
            }

            val result = sb.toString()
            return if (result.length > 3000) {
                result.take(2500) + "\n... (diff truncated)\n"
            } else {
                result
            }
        }

        private fun appendHunk(
            sb: StringBuilder,
            oldLines: List<String>,
            newLines: List<String>,
            changeStart: Int,
            changeEnd: Int,
            hunkStart: Int,
            hunkEnd: Int
        ) {
            val oldSlice = oldLines.subList(hunkStart, minOf(hunkEnd, oldLines.size))
            val newSlice = newLines.subList(hunkStart, minOf(hunkEnd, newLines.size))

            sb.appendLine("@@ -${hunkStart + 1},${oldSlice.size} +${hunkStart + 1},${newSlice.size} @@")
            for (line in oldSlice) sb.appendLine("-$line")
            for (line in newSlice) sb.appendLine("+$line")
        }
    }
}
