package com.corecoder.app.core.exec

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Command execution via OperitTerminalCore's proot + Ubuntu stack.
 *
 * Bridges [TerminalManager.executeHiddenCommand] (which returns [HiddenExecResult])
 * into CoreCoder's [CommandExecutor] / [CommandResult] interface.
 * All AI tools (BashTool, EditFileTool, WriteFileTool, etc.) work without any changes.
 */
@Singleton
class OperitCommandExecutor @Inject constructor(
    private val context: Context
) : CommandExecutor {

    companion object {
        private const val TAG = "OperitExecutor"
    }

    private val terminalManager: TerminalManager by lazy {
        TerminalManager.getInstance(context)
    }

    override suspend fun execute(
        command: String,
        workDir: String?,
        timeout: Int
    ): CommandResult {
        val fullCommand = if (workDir != null) {
            "cd '${workDir.replace("'", "'\\''")}' && $command"
        } else {
            command
        }

        val result = try {
            terminalManager.executeHiddenCommand(
                command = fullCommand,
                timeoutMs = timeout * 1000L
            )
        } catch (e: Exception) {
            Log.e(TAG, "executeHiddenCommand failed", e)
            return CommandResult(
                stdout = "",
                stderr = e.message ?: "Unknown error",
                exitCode = -1
            )
        }

        val stderr = if (!result.isOk) {
            val preview = result.rawOutputPreview
            if (preview.isNotBlank()) {
                "${result.error}\n--- shell output ---\n$preview"
            } else {
                result.error
            }
        } else ""

        if (!result.isOk) {
            Log.w(TAG, "Command failed [state=${result.state}]: ${result.error}")
            Log.w(TAG, "Shell output preview: ${result.rawOutputPreview}")
        }

        return CommandResult(
            stdout = result.output,
            stderr = stderr,
            exitCode = result.exitCode
        )
    }

    override suspend fun checkAvailability(): String {
        return try {
            val success = terminalManager.initializeEnvironment()
            if (success) "ready" else "not_ready"
        } catch (e: Exception) {
            Log.e(TAG, "checkAvailability failed", e)
            "error: ${e.message}"
        }
    }
}
