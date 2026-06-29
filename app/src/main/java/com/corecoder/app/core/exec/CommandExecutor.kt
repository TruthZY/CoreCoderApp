package com.corecoder.app.core.exec

/**
 * Result of a shell command execution.
 */
data class CommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** Format for display to the LLM (matches Python BashTool output format). */
    fun toDisplayString(maxLength: Int = 15_000): String {
        var out = stdout
        if (stderr.isNotBlank()) {
            out += "\n[stderr]\n$stderr"
        }
        if (exitCode != 0) {
            out += "\n[exit code: $exitCode]"
        }
        if (out.length > maxLength) {
            out = out.take(6000) +
                    "\n\n... truncated (${out.length} chars total) ...\n\n" +
                    out.takeLast(3000)
        }
        return out.ifBlank { "(no output)" }
    }
}

/**
 * Abstraction for command execution.
 *
 * Implementations can target:
 * - Embedded proot + Ubuntu rootfs ([ProotCommandExecutor])
 * - Local Android shell via ProcessBuilder (future)
 * - Remote SSH server (future)
 *
 * Swap the implementation in [com.corecoder.app.di.AppModule] to change
 * the execution backend without touching any tool code.
 */
interface CommandExecutor {

    /**
     * Execute a shell command and return the result.
     *
     * @param command  The shell command string (passed to `sh -c` or equivalent).
     * @param workDir  Working directory for the command. Defaults to app home.
     * @param timeout  Timeout in seconds. Default 120.
     * @return [CommandResult] with stdout, stderr, and exit code.
     */
    suspend fun execute(
        command: String,
        workDir: String? = null,
        timeout: Int = 120
    ): CommandResult

    /**
     * Check whether the execution backend is available and ready.
     * Returns a human-readable status message.
     * - "ready"            → backend is available
     * - "not_installed"    → required binaries not found
     * - "not_bootstrapped"  → environment not set up yet
     * - other string       → custom error description
     */
    suspend fun checkAvailability(): String
}
