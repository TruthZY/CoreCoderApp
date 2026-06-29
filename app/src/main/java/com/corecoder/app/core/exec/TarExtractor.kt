package com.corecoder.app.core.exec

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Pure-Java tar extraction with full symlink support.
 *
 * Replaces busybox-based tar extraction to avoid executing external binaries
 * (which can fail with SIGSYS/seccomp on some Android devices).
 *
 * Handles: plain .tar, .tar.gz, .tar.xz
 */
object TarExtractor {

    private const val TAG = "TarExtractor"
    private const val BUFFER_SIZE = 8192

    /**
     * Extract a tar archive to the target directory.
     *
     * @param input The raw input stream of the tar file (may be compressed).
     * @param targetDir The directory to extract into.
     * @param assetName The filename, used to detect compression format.
     * @param onProgress Optional callback: (bytesExtracted, fileCount)
     */
    fun extract(
        input: InputStream,
        targetDir: File,
        assetName: String,
        onProgress: ((Long, Int) -> Unit)? = null
    ) {
        targetDir.mkdirs()

        val buffered = BufferedInputStream(input, BUFFER_SIZE)

        // Wrap with decompressor based on file extension
        val decompressed: InputStream = when {
            assetName.endsWith(".gz") || assetName.endsWith(".tgz") ->
                GzipCompressorInputStream(buffered)
            assetName.endsWith(".xz") ->
                XZCompressorInputStream(buffered)
            else -> buffered  // plain .tar
        }

        val tarInput = TarArchiveInputStream(decompressed)
        var entry: TarArchiveEntry? = tarInput.nextTarEntry
        var fileCount = 0
        var bytesExtracted = 0L

        while (entry != null) {
            val outputFile = File(targetDir, entry.name)

            // Security: prevent path traversal (zip slip)
            if (!outputFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                Log.w(TAG, "Skipping path traversal entry: ${entry.name}")
                entry = tarInput.nextTarEntry
                continue
            }

            try {
                when {
                    entry.isDirectory -> {
                        outputFile.mkdirs()
                    }

                    entry.isSymbolicLink -> {
                        // Create symlink: outputFile → entry.linkName
                        outputFile.parentFile?.mkdirs()
                        // Delete existing file/link if present
                        Files.deleteIfExists(outputFile.toPath())
                        val linkTarget = Paths.get(entry.linkName)
                        Files.createSymbolicLink(outputFile.toPath(), linkTarget)
                    }

                    entry.isLink -> {
                        // Hard link — create after all files are extracted
                        // (target may not exist yet)
                        outputFile.parentFile?.mkdirs()
                        val linkTarget = File(targetDir, entry.linkName)
                        Files.deleteIfExists(outputFile.toPath())
                        try {
                            Files.createLink(outputFile.toPath(), linkTarget.toPath())
                        } catch (e: Exception) {
                            // Hard links may fail if target doesn't exist yet;
                            // create a symlink as fallback
                            Files.createSymbolicLink(outputFile.toPath(), linkTarget.toPath())
                        }
                    }

                    entry.isFile -> {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { out ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (tarInput.read(buffer).also { bytesRead = it } != -1) {
                                out.write(buffer, 0, bytesRead)
                                bytesExtracted += bytesRead
                            }
                        }
                        // Preserve executable permission
                        if (entry.mode and 0b001_000_000 != 0) { // owner execute bit
                            outputFile.setExecutable(true, false)
                        }
                    }

                    else -> {
                        // Skip special entries (block devices, char devices, fifos, etc.)
                        Log.d(TAG, "Skipping special entry: ${entry.name} (type=${entry.linkFlag})")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract entry: ${entry.name}", e)
            }

            fileCount++
            if (fileCount % 500 == 0) {
                onProgress?.invoke(bytesExtracted, fileCount)
            }

            entry = tarInput.nextTarEntry
        }

        tarInput.close()
        Log.i(TAG, "Extracted $fileCount entries (${bytesExtracted / 1_000_000}MB) to ${targetDir.absolutePath}")
    }
}
