package com.corecoder.app.core.tools

import com.corecoder.app.core.exec.CommandExecutor
import com.google.gson.JsonObject
import java.util.regex.Pattern

/**
 * Shell command execution tool with safety checks.
 * Corresponds to Python's BashTool (tools/bash.py, 115 lines).
 *
 * Delegates actual execution to [CommandExecutor], which can be
 * swapped between proot/Ubuntu, local shell, or a custom backend.
 */
class BashTool(
    private val executor: CommandExecutor
) : Tool {

    override val name = "bash"
    override val description =
        "Execute a shell command. Returns stdout, stderr, and exit code. " +
        "Use this for running tests, installing packages, git operations, etc."

    override val parameters: JsonObject = run {
        val props = JsonObject()

        val command = JsonObject()
        command.addProperty("type", "string")
        command.addProperty("description", "The shell command to run")
        props.add("command", command)

        val timeout = JsonObject()
        timeout.addProperty("type", "integer")
        timeout.addProperty("description", "Timeout in seconds (default 120)")
        props.add("timeout", timeout)

        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", props)
        val required = com.google.gson.JsonArray()
        required.add("command")
        params.add("required", required)
        params
    }

    /** Track working directory across cd commands. */
    private var workingDir: String? = null

    override suspend fun execute(args: Map<String, Any?>): String {
        val command = args["command"]?.toString() ?: return "Error: command is required"
        val timeout = (args["timeout"] as? Number)?.toInt() ?: 120

        // Safety check
        val warning = checkDangerous(command)
        if (warning != null) {
            return "⚠ Blocked: $warning\nCommand: $command\nIf intentional, modify the command to be more specific."
        }

        val result = executor.execute(command, workDir = workingDir, timeout = timeout)

        // Track cd commands
        if (result.isSuccess) {
            updateCwd(command)
        }

        return result.toDisplayString()
    }

    companion object {
        // Patterns that could wreck the filesystem or leak secrets
        private val DANGEROUS_PATTERNS = listOf(
            Pattern.compile("\\brm\\s+(-\\w*)?-r\\w*\\s+(/|~|\\\$HOME)") to "recursive delete on home/root",
            Pattern.compile("\\brm\\s+(-\\w*)?-rf\\s") to "force recursive delete",
            Pattern.compile("\\bmkfs\\b") to "format filesystem",
            Pattern.compile("\\bdd\\s+.*of=/dev/") to "raw disk write",
            Pattern.compile(">\\s*/dev/sd[a-z]") to "overwrite block device",
            Pattern.compile("\\bchmod\\s+(-R\\s+)?777\\s+/") to "chmod 777 on root",
            Pattern.compile(":\\(\\)\\s*\\{.*:\\|:.*\\}") to "fork bomb",
            Pattern.compile("\\bcurl\\b.*\\|\\s*(sudo\\s+)?bash") to "pipe curl to bash",
            Pattern.compile("\\bwget\\b.*\\|\\s*(sudo\\s+)?bash") to "pipe wget to bash",
        )

        /** Return a warning string if the command looks destructive, else null. */
        fun checkDangerous(cmd: String): String? {
            for ((pattern, reason) in DANGEROUS_PATTERNS) {
                if (pattern.matcher(cmd).find()) return reason
            }
            return null
        }
    }

    /** Track directory changes from cd commands. */
    private fun updateCwd(command: String) {
        val parts = command.split("&&")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("cd ")) {
                val target = trimmed.removePrefix("cd ").trim().trim('\'', '"')
                if (target.isNotBlank()) {
                    // Simple heuristic: just record the last cd target
                    workingDir = if (target.startsWith("/")) {
                        target
                    } else {
                        val base = workingDir ?: "/data/data/com.corecoder.app/files"
                        java.io.File(base, target).canonicalPath
                    }
                }
            }
        }
    }
}
