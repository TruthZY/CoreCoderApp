package com.corecoder.app.core.exec

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Command execution via embedded proot + Ubuntu rootfs.
 *
 * Replaces TermuxCommandExecutor with a self-contained Linux environment
 * that runs directly within the app — no external dependencies required.
 *
 * Architecture:
 * 1. PTY (pseudo-terminal) created via JNI forkpty()
 * 2. Child process runs: proot → bash (inside Ubuntu rootfs)
 * 3. Commands sent via marker protocol for reliable output parsing
 * 4. Persistent bash session — cwd, env vars, etc. preserved across calls
 */
@Singleton
class ProotCommandExecutor @Inject constructor(
    private val context: Context
) : CommandExecutor {

    companion object {
        private const val TAG = "ProotExecutor"
        private const val READ_BUFFER_SIZE = 4096
        private const val PTY_READ_TIMEOUT_MS = 100L  // Poll interval for PTY reads
        private const val STARTUP_PROBE_TIMEOUT_MS = 5000L  // 5s to verify proot is alive

        // Markers for command output parsing
        private const val BEGIN_PREFIX = "__CC_BEGIN__"
        private const val END_PREFIX = "__CC_END__"
    }

    /** The PTY master file descriptor, or -1 if not active. */
    @Volatile
    private var masterFd: Int = -1

    /** Whether the session is alive. */
    private val sessionActive = AtomicBoolean(false)

    /** Background thread reading from PTY. */
    private var readerThread: Thread? = null

    /** Accumulated output from PTY reads. */
    private val outputBuffer = StringBuilder()
    private val outputLock = java.lang.Object()

    /** Reason for last session start failure (for diagnostics). */
    @Volatile
    private var lastError: String? = null

    /**
     * Start the proot + bash session if not already running.
     */
    @Synchronized
    fun ensureSession(): Boolean {
        if (sessionActive.get() && masterFd >= 0) return true

        if (!EnvironmentBootstrap.isBootstrapped(context)) {
            Log.w(TAG, "Environment not bootstrapped yet")
            lastError = "Linux environment not bootstrapped"
            return false
        }

        lastError = null
        return startSession()
    }

    /**
     * Start a new proot + bash session.
     * Includes a startup probe to verify proot actually responds.
     */
    private fun startSession(): Boolean {
        val prootPath = EnvironmentBootstrap.getProotPath(context)
        val rootfsDir = EnvironmentBootstrap.getRootfsDir(context)

        if (!File(prootPath).exists()) {
            lastError = "proot binary not found: $prootPath"
            Log.e(TAG, lastError!!)
            return false
        }
        if (!rootfsDir.exists()) {
            lastError = "rootfs not found: ${rootfsDir.absolutePath}"
            Log.e(TAG, lastError!!)
            return false
        }

        // Build proot arguments
        val args = listOf(
            prootPath,
            "-r", rootfsDir.absolutePath,
            "-0",                          // fake root
            "-b", "/dev",                  // bind mount /dev
            "-b", "/proc",                 // bind mount /proc (limited)
            "-b", "/sys",                  // bind mount /sys
            "-b", "${context.filesDir.absolutePath}:/mnt/shared", // expose app files
            "-w", "/home",                 // working directory
            "/bin/bash",
            "--norc",                      // skip .bashrc to avoid noise
            "--noprofile"
        )

        val fd = Pty.createPty(
            cmd = prootPath,
            args = args.drop(1),
            env = listOf(
                "TERM=xterm-256color",
                "HOME=/home",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=en_US.UTF-8",
                "LD_LIBRARY_PATH="         // clear — prevents Android linker interference
            ),
            cwd = context.filesDir.absolutePath
        )

        if (fd < 0) {
            Log.e(TAG, "Failed to create PTY")
            return false
        }

        // --- Startup probe: verify proot + bash actually respond ---
        if (!probeSession(fd)) {
            lastError = "proot failed to start (SIGSYS/ptrace issue). " +
                    "proot binary may be incompatible with this device. " +
                    "On emulators, proot hangs due to ARM translation (houdini) ptrace conflict."
            Log.e(TAG, "Startup probe FAILED — $lastError")
            Pty.close(fd)
            return false
        }

        masterFd = fd
        sessionActive.set(true)

        // Disable bash line editing for clean output
        Pty.writeLine(fd, "set +o history")
        Pty.writeLine(fd, "stty -echo")
        Pty.writeLine(fd, "export PS1=''")

        // Drain the initial bash startup output
        drainOutput(1000)

        // Start background reader thread
        startReaderThread()

        Log.i(TAG, "Proot session started (fd=$fd)")
        return true
    }

    /**
     * Probe the PTY session to verify proot + bash are actually running.
     * Sends a known marker string and waits for it to appear in output.
     *
     * @return true if proot responds within timeout, false if it's dead/hung
     */
    private fun probeSession(fd: Int): Boolean {
        val probeMarker = "__PROOT_ALIVE__"
        Pty.writeLine(fd, "echo $probeMarker")

        val buffer = ByteArray(READ_BUFFER_SIZE)
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + STARTUP_PROBE_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            val n = Pty.read(fd, buffer)
            if (n <= 0) {
                // Child exited (EIO → 0) or error (-1) — proot failed to start
                Log.e(TAG, "PTY read returned $n during probe — child process exited")
                return false
            }
            val chunk = String(buffer, 0, n, Charsets.UTF_8)
            output.append(chunk)
            if (output.contains(probeMarker)) {
                Log.i(TAG, "Startup probe succeeded (proot is alive)")
                return true
            }
        }

        Log.e(TAG, "Startup probe timed out after ${STARTUP_PROBE_TIMEOUT_MS}ms. " +
                "Output so far: '${output.take(200)}'")
        return false
    }

    /**
     * Background thread that continuously reads from PTY
     * and appends to the shared output buffer.
     */
    private fun startReaderThread() {
        readerThread?.interrupt()
        readerThread = Thread({
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (sessionActive.get()) {
                try {
                    val n = Pty.read(masterFd, buffer)
                    if (n <= 0) {
                        Log.d(TAG, "PTY read returned $n (session may have ended)")
                        break
                    }
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
        }, "PtyReader").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Drain any pending output from the PTY (used during startup).
     */
    private fun drainOutput(timeoutMs: Long) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val n = Pty.read(masterFd, buffer)
                if (n <= 0) break
                // Discard startup output
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
        // Ensure session is running
        if (!ensureSession()) {
            return@withContext CommandResult(
                stdout = "",
                stderr = lastError ?: "Linux environment not ready. proot session failed to start.",
                exitCode = -1
            )
        }

        val fd = masterFd
        if (fd < 0 || !sessionActive.get()) {
            return@withContext CommandResult(
                stdout = "",
                stderr = "Shell session not available (proot may have crashed).",
                exitCode = -1
            )
        }

        val cmdId = UUID.randomUUID().toString().take(8)
        val beginMarker = "${BEGIN_PREFIX}_${cmdId}"
        val endMarker = "${END_PREFIX}_${cmdId}"

        // Build the wrapped command:
        // echo __BEGIN__; cd <workdir> 2>/dev/null; <command>; echo "__END__:$?"
        val wrappedCmd = buildString {
            append("echo '$beginMarker'")
            if (workDir != null) {
                append(" && cd '${workDir.replace("'", "'\\''")}'")
            }
            append(" && { $command ; }")
            append(" ; echo '${endMarker}:'\$?")
        }

        // Clear output buffer before sending command
        synchronized(outputLock) {
            outputBuffer.clear()
        }

        // Send command to PTY
        Pty.writeLine(fd, wrappedCmd)

        // Wait for end marker in output
        val fullOutput = waitForMarker(endMarker, timeout * 1000L)

        if (fullOutput == null) {
            return@withContext CommandResult(
                stdout = "",
                stderr = "Command timed out after ${timeout}s",
                exitCode = -1
            )
        }

        // Parse output: extract content between begin and end markers
        parseOutput(fullOutput, beginMarker, endMarker)
    }

    /**
     * Wait until the end marker appears in the output buffer.
     * Returns the full accumulated output, or null on timeout.
     */
    private fun waitForMarker(endMarker: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs

        synchronized(outputLock) {
            while (System.currentTimeMillis() < deadline) {
                if (outputBuffer.contains(endMarker)) {
                    return outputBuffer.toString()
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                outputLock.wait(minOf(100L, remaining))
            }
        }

        // Final check after timeout
        synchronized(outputLock) {
            val result = outputBuffer.toString()
            return if (result.contains(endMarker)) result else null
        }
    }

    /**
     * Parse the PTY output to extract stdout, stderr, and exit code.
     *
     * Expected format in output:
     * ```
     * <beginMarker>
     * <command output>
     * <endMarker>:<exitCode>
     * ```
     */
    private fun parseOutput(fullOutput: String, beginMarker: String, endMarker: String): CommandResult {
        val beginIdx = fullOutput.indexOf(beginMarker)
        val endIdx = fullOutput.indexOf(endMarker)

        if (beginIdx < 0 || endIdx < 0) {
            // Couldn't find markers — return raw output
            return CommandResult(
                stdout = stripAnsi(fullOutput),
                stderr = "",
                exitCode = 0
            )
        }

        // Content between markers (skip the begin marker line itself)
        val contentStart = fullOutput.indexOf('\n', beginIdx) + 1
        val contentEnd = fullOutput.lastIndexOf('\n', endIdx)

        val stdout = if (contentStart > 0 && contentEnd > contentStart) {
            stripAnsi(fullOutput.substring(contentStart, contentEnd).trimEnd())
        } else {
            ""
        }

        // Extract exit code from end marker line: "__CC_END_xxx:0"
        val exitCodeStr = fullOutput.substring(endIdx + endMarker.length + 1)
            .takeWhile { it.isDigit() }
        val exitCode = exitCodeStr.toIntOrNull() ?: -1

        return CommandResult(
            stdout = stdout,
            stderr = "",
            exitCode = exitCode
        )
    }

    /**
     * Strip ANSI escape sequences from terminal output.
     * PTY output includes escape codes for colors, cursor positioning, etc.
     */
    private fun stripAnsi(input: String): String {
        // Match ANSI escape sequences: ESC [ ... letter
        return ANSI_PATTERN.replace(input, "")
    }

    private val ANSI_PATTERN = Regex("""\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])""")

    override suspend fun checkAvailability(): String {
        if (EnvironmentBootstrap.isBootstrapped(context)) {
            return "ready"
        }
        return "not_bootstrapped"
    }

    /**
     * Terminate the proot session and clean up.
     */
    fun shutdown() {
        sessionActive.set(false)
        readerThread?.interrupt()
        readerThread = null
        if (masterFd >= 0) {
            Pty.kill()
            Pty.close(masterFd)
            masterFd = -1
        }
        Log.i(TAG, "Session shut down")
    }
}
