package com.corecoder.app.core.exec

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Command execution via Android's built-in /system/bin/sh.
 *
 * Used in debug builds for easy testing on emulators (x86_64).
 * No proot, no Ubuntu rootfs, no binary extraction needed.
 *
 * Limitations vs ProotCommandExecutor:
 * - No package management (apt-get)
 * - No full GNU tools (toybox versions instead)
 * - No programming languages (python3, node, etc.)
 * - No git (unless separately installed)
 *
 * But it works on ALL devices and architectures.
 */
@Singleton
class ShellCommandExecutor @Inject constructor(
    private val context: Context
) : CommandExecutor {

    companion object {
        private const val TAG = "ShellExecutor"
        private const val READ_BUFFER_SIZE = 4096
        private const val STARTUP_PROBE_TIMEOUT_MS = 3000L

        private const val BEGIN_PREFIX = "__CC_BEGIN__"
        private const val END_PREFIX = "__CC_END__"
    }

    @Volatile
    private var masterFd: Int = -1

    private val sessionActive = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private val outputBuffer = StringBuilder()
    private val outputLock = java.lang.Object()

    /** Serialize all command executions to avoid PTY race conditions. */
    private val execLock = java.util.concurrent.locks.ReentrantLock()

    /** Working directory tracking for persistent cwd across commands. */
    private var currentWorkDir: String = context.filesDir.absolutePath

    @Synchronized
    fun ensureSession(): Boolean {
        if (sessionActive.get() && masterFd >= 0) return true
        return startSession()
    }

    private fun startSession(): Boolean {
        val shellPath = "/system/bin/sh"
        if (!File(shellPath).exists()) {
            Log.e(TAG, "System shell not found: $shellPath")
            return false
        }

        val fd = Pty.createPty(
            cmd = shellPath,
            args = emptyList(),
            env = listOf(
                "TERM=xterm-256color",
                "HOME=${context.filesDir.absolutePath}",
                "PATH=/system/bin:/system/xbin:/vendor/bin",
                "LANG=en_US.UTF-8",
                "TMPDIR=${context.cacheDir.absolutePath}"
            ),
            cwd = context.filesDir.absolutePath
        )

        if (fd < 0) {
            Log.e(TAG, "Failed to create PTY")
            return false
        }

        // Startup probe
        if (!probeSession(fd)) {
            Log.e(TAG, "Startup probe failed — /system/bin/sh not responding")
            Pty.close(fd)
            return false
        }

        masterFd = fd
        sessionActive.set(true)

        // Start reader thread BEFORE config commands to capture their output
        startReaderThread()

        // Configure shell for clean output
        Pty.writeLine(fd, "set +o history")
        Pty.writeLine(fd, "stty -echo")
        Pty.writeLine(fd, "export PS1=''")

        // Warmup: send a dummy command and wait for it to ensure shell is ready
        val warmupMarker = "__WARMUP__"
        Pty.writeLine(fd, "echo $warmupMarker")
        
        // Wait for warmup marker in output (up to 2 seconds)
        val deadline = System.currentTimeMillis() + 2000
        synchronized(outputLock) {
            while (System.currentTimeMillis() < deadline) {
                if (outputBuffer.contains(warmupMarker)) break
                outputLock.wait(100)
            }
        }
        
        // Clear buffer after warmup
        synchronized(outputLock) {
            outputBuffer.clear()
        }

        Log.i(TAG, "Shell session started (fd=$fd, shell=$shellPath)")
        return true
    }

    private fun probeSession(fd: Int): Boolean {
        val probeMarker = "__SHELL_ALIVE__"
        Pty.writeLine(fd, "echo $probeMarker")

        val buffer = ByteArray(READ_BUFFER_SIZE)
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + STARTUP_PROBE_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            val n = Pty.read(fd, buffer)
            if (n <= 0) return false
            val chunk = String(buffer, 0, n, Charsets.UTF_8)
            output.append(chunk)
            if (output.contains(probeMarker)) {
                Log.i(TAG, "Shell probe succeeded")
                return true
            }
        }
        return false
    }

    private fun startReaderThread() {
        readerThread?.interrupt()
        readerThread = Thread({
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (sessionActive.get()) {
                try {
                    val n = Pty.read(masterFd, buffer)
                    if (n <= 0) break
                    val chunk = String(buffer, 0, n, Charsets.UTF_8)
                    synchronized(outputLock) {
                        outputBuffer.append(chunk)
                        outputLock.notifyAll()
                    }
                } catch (e: Exception) {
                    if (sessionActive.get()) {
                        Log.e(TAG, "PTY read error", e)
                    }
                    break
                }
            }
            sessionActive.set(false)
        }, "ShellReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun drainOutput(timeoutMs: Long) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val n = Pty.read(masterFd, buffer)
                if (n <= 0) break
            } catch (_: Exception) {
                break
            }
        }
    }

    override suspend fun execute(
        command: String,
        workDir: String?,
        timeout: Int
    ): CommandResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "execute() called: command='$command', workDir=$workDir, timeout=$timeout")

        if (!ensureSession()) {
            Log.e(TAG, "execute() failed: session not available")
            return@withContext CommandResult(
                stdout = "",
                stderr = "Shell session not available.",
                exitCode = -1
            )
        }

        val fd = masterFd
        if (fd < 0 || !sessionActive.get()) {
            Log.e(TAG, "execute() failed: session not active (fd=$fd)")
            return@withContext CommandResult(
                stdout = "",
                stderr = "Shell session not active.",
                exitCode = -1
            )
        }

        // Serialize command execution to prevent race conditions
        execLock.lock()
        try {
            val cmdId = UUID.randomUUID().toString().take(8)
            val beginMarker = "${BEGIN_PREFIX}_${cmdId}"
            val endMarker = "${END_PREFIX}_${cmdId}"

            // Build command with optional cd
            val effectiveDir = workDir ?: currentWorkDir
            val wrappedCmd = buildString {
                append("echo '$beginMarker'")
                append(" && cd '${effectiveDir.replace("'", "'\\''")}' 2>/dev/null")
                append(" && { $command ; }")
                append(" ; echo '${endMarker}:'\$?")
            }

            Log.d(TAG, "Sending command (id=$cmdId): $wrappedCmd")

            synchronized(outputLock) {
                outputBuffer.clear()
            }

            Pty.writeLine(fd, wrappedCmd)

            val fullOutput = waitForMarker(endMarker, timeout * 1000L)

            if (fullOutput == null) {
                Log.e(TAG, "Command timed out after ${timeout}s (id=$cmdId)")
                synchronized(outputLock) {
                    Log.e(TAG, "Buffer at timeout (id=$cmdId): ${outputBuffer.take(500)}")
                }
                return@withContext CommandResult(
                    stdout = "",
                    stderr = "Command timed out after ${timeout}s",
                    exitCode = -1
                )
            }

            Log.d(TAG, "Raw output (id=$cmdId, len=${fullOutput.length}): ${fullOutput.take(300).replace("\n", "\\n")}")

            // Update tracked workdir if cd succeeded
            if (workDir != null) {
                currentWorkDir = workDir
            }

            val result = parseOutput(fullOutput, beginMarker, endMarker)
            Log.d(TAG, "Command result (id=$cmdId): exitCode=${result.exitCode}, stdoutLen=${result.stdout.length}")
            result
        } finally {
            execLock.unlock()
        }
    }

    private fun waitForMarker(endMarker: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs

        // Search for marker at start of line (actual output), not embedded in command echo
        fun hasMarkerAtLineStart(output: String): Boolean {
            var idx = output.indexOf(endMarker)
            while (idx >= 0) {
                if (idx == 0 || output[idx - 1] == '\n' || output[idx - 1] == '\r') return true
                idx = output.indexOf(endMarker, idx + 1)
            }
            return false
        }

        synchronized(outputLock) {
            while (System.currentTimeMillis() < deadline) {
                if (hasMarkerAtLineStart(outputBuffer.toString())) {
                    return outputBuffer.toString()
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                outputLock.wait(minOf(100L, remaining))
            }
        }

        synchronized(outputLock) {
            val result = outputBuffer.toString()
            return if (hasMarkerAtLineStart(result)) result else null
        }
    }

    private fun parseOutput(fullOutput: String, beginMarker: String, endMarker: String): CommandResult {
        val beginIdx = fullOutput.indexOf(beginMarker)
        val endIdx = fullOutput.indexOf(endMarker)

        if (beginIdx < 0 || endIdx < 0) {
            return CommandResult(
                stdout = stripAnsi(fullOutput),
                stderr = "",
                exitCode = 0
            )
        }

        val contentStart = fullOutput.indexOf('\n', beginIdx) + 1
        val contentEnd = fullOutput.lastIndexOf('\n', endIdx)

        val stdout = if (contentStart > 0 && contentEnd > contentStart) {
            stripAnsi(fullOutput.substring(contentStart, contentEnd).trimEnd())
        } else {
            ""
        }

        val exitCodeStr = fullOutput.substring(endIdx + endMarker.length + 1).trim().takeWhile { it.isLetterOrDigit() }
        val exitCode = when {
            exitCodeStr == "True" || exitCodeStr == "true" -> 0
            exitCodeStr == "False" || exitCodeStr == "false" -> 1
            else -> exitCodeStr.toIntOrNull() ?: -1
        }

        return CommandResult(
            stdout = stdout,
            stderr = "",
            exitCode = exitCode
        )
    }

    private fun stripAnsi(input: String): String {
        return ANSI_PATTERN.replace(input, "")
    }

    private val ANSI_PATTERN = Regex("""\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])""")

    override suspend fun checkAvailability(): String {
        return "ready"  // /system/bin/sh is always available
    }

    fun shutdown() {
        sessionActive.set(false)
        readerThread?.interrupt()
        readerThread = null
        if (masterFd >= 0) {
            Pty.kill()
            Pty.close(masterFd)
            masterFd = -1
        }
        Log.i(TAG, "Shell session shut down")
    }
}
