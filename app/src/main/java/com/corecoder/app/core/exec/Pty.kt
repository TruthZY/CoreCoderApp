package com.corecoder.app.core.exec

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * JNI wrapper for PTY (pseudo-terminal) operations.
 *
 * Creates a PTY pair, forks a child process running the given command,
 * and provides read/write access to the child's stdin/stdout via the
 * master file descriptor.
 */
object Pty {

    private const val TAG = "Pty"

    init {
        System.loadLibrary("corepty")
    }

    /**
     * Create a PTY and fork a child process.
     *
     * @param cmd  Absolute path to the executable (e.g. proot binary)
     * @param args Command arguments (list, NOT including argv[0])
     * @param env  Environment variables as KEY=VALUE pairs, or null to inherit
     * @param cwd  Working directory for the child, or null
     * @return Master file descriptor, or -1 on failure
     */
    fun createPty(
        cmd: String,
        args: List<String>,
        env: List<String>? = null,
        cwd: String? = null
    ): Int {
        val argsStr = args.joinToString("\n")
        val envStr = env?.joinToString("\n")
        return nativeCreatePty(cmd, argsStr, envStr, cwd)
    }

    /**
     * Write bytes to the PTY master fd.
     * @return Number of bytes written, or -1 on error
     */
    fun write(fd: Int, data: ByteArray): Int = nativeWrite(fd, data)

    /**
     * Write a string to the PTY (UTF-8 encoded + newline).
     */
    fun writeLine(fd: Int, line: String) {
        val bytes = "$line\n".toByteArray(Charsets.UTF_8)
        write(fd, bytes)
    }

    /**
     * Read bytes from the PTY master fd.
     * Blocks until data is available or child exits.
     * @return Number of bytes read, 0 on EOF, -1 on error
     */
    fun read(fd: Int, buffer: ByteArray): Int = nativeRead(fd, buffer)

    /**
     * Close the PTY master fd and reap the child process.
     */
    fun close(fd: Int) = nativeClose(fd)

    /**
     * Send SIGTERM to the child process.
     */
    fun kill() = nativeKill()

    // --- Native declarations ---

    private external fun nativeCreatePty(
        cmd: String, args: String, env: String?, cwd: String?
    ): Int

    private external fun nativeWrite(fd: Int, data: ByteArray): Int

    private external fun nativeRead(fd: Int, buffer: ByteArray): Int

    private external fun nativeClose(fd: Int)

    private external fun nativeKill()
}
