package com.corecoder.app.core.tools

import android.util.Base64
import com.corecoder.app.core.exec.CommandExecutor
import com.corecoder.app.core.exec.CommandResult

/**
 * Shell helpers for file tools that route through [CommandExecutor].
 *
 * Key insight: we use **base64** to transfer file content between
 * Kotlin and the shell, completely avoiding shell escaping issues
 * (special chars, newlines, quotes, etc.).
 */
internal object ShellHelper {

    /** Encode a Kotlin string to base64 for safe shell transfer. */
    fun encodeB64(content: String): String =
        Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** Decode a base64 string received from the shell back to Kotlin string. */
    fun decodeB64(encoded: String): String =
        String(Base64.decode(encoded.trim(), Base64.DEFAULT), Charsets.UTF_8)

    /** Shell-safe quoted path: replace ' with '\'' */
    fun quote(path: String): String =
        path.replace("'", "'\\''")

    /** Check if executor returned an error result. */
    fun checkError(result: CommandResult): String? {
        if (!result.isSuccess) {
            val msg = result.stderr.ifBlank { result.stdout }
            return "Error (exit ${result.exitCode}): $msg"
        }
        return null
    }
}
