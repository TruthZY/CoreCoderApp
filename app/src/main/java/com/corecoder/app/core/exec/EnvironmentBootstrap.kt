package com.corecoder.app.core.exec

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Bootstraps the embedded Linux environment on first launch.
 *
 * Extracts Ubuntu rootfs from assets and sets up proot, bash, busybox
 * binaries from the app's native library directory.
 */
object EnvironmentBootstrap {

    private const val TAG = "EnvBootstrap"
    private const val MARKER_FILE = ".corecoder_bootstrapped"
    private const val ROOTFS_DIR = "ubuntu"

    /** Progress state for UI display. */
    sealed class Progress {
        data object Idle : Progress()
        data class Extracting(val percent: Int, val message: String) : Progress()
        data object Configuring : Progress()
        data object Ready : Progress()
        data class Error(val message: String) : Progress()
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: Flow<Progress> = _progress

    /**
     * Returns the rootfs directory path.
     */
    fun getRootfsDir(context: Context): File {
        return File(context.filesDir, ROOTFS_DIR)
    }

    /**
     * Returns the proot binary path.
     */
    fun getProotPath(context: Context): String {
        return File(context.filesDir, "bin/proot").absolutePath
    }

    /**
     * Returns the bash binary path (inside rootfs).
     */
    fun getBashPath(context: Context): String {
        return "/bin/bash"  // Relative to rootfs, used inside proot
    }

    /**
     * Check if the environment has already been bootstrapped.
     */
    fun isBootstrapped(context: Context): Boolean {
        val marker = File(context.filesDir, MARKER_FILE)
        return marker.exists()
    }

    /**
     * Run the full bootstrap process.
     *
     * 1. Extract Ubuntu rootfs from assets
     * 2. Copy proot/bash/busybox binaries
     * 3. Run setup script (fake /proc, DNS, apt sources)
     * 4. Write marker file
     *
     * Safe to call multiple times — skips if already bootstrapped.
     */
    suspend fun bootstrap(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isBootstrapped(context)) {
            _progress.value = Progress.Ready
            return@withContext true
        }

        try {
            // Step 1: Extract rootfs
            _progress.value = Progress.Extracting(0, "Extracting Ubuntu rootfs...")
            val rootfsDir = getRootfsDir(context)
            rootfsDir.mkdirs()
            extractRootfs(context, rootfsDir)

            // Step 2: Setup binaries
            _progress.value = Progress.Extracting(80, "Setting up binaries...")
            setupBinaries(context)

            // Step 3: Run setup script
            _progress.value = Progress.Configuring
            runSetupScript(context, rootfsDir)

            // Step 4: Mark as done
            File(context.filesDir, MARKER_FILE).writeText("ok")
            _progress.value = Progress.Ready

            Log.i(TAG, "Bootstrap complete")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap failed", e)
            _progress.value = Progress.Error(e.message ?: "Unknown error")
            return@withContext false
        }
    }

    /**
     * Extract the Ubuntu rootfs tarball from assets into the target directory.
     *
     * Expects: assets/ubuntu-rootfs.tar.xz
     * Falls back to: assets/ubuntu-base.tar.gz
     */
    private fun extractRootfs(context: Context, rootfsDir: File) {
        val assetName = listOf(
            "ubuntu-rootfs.tar.xz",
            "ubuntu-base.tar.gz",
            "ubuntu-base.tar.xz"
        ).firstOrNull { name ->
            try {
                context.assets.open(name).close()
                true
            } catch (_: Exception) {
                false
            }
        }

        if (assetName == null) {
            throw IllegalStateException(
                "No Ubuntu rootfs found in assets. " +
                "Place ubuntu-rootfs.tar.xz or ubuntu-base.tar.gz in app/src/main/assets/"
            )
        }

        Log.i(TAG, "Extracting rootfs from: $assetName")

        // Write tarball to a temp file first (tar needs seekable input for .xz)
        val tmpFile = File(context.cacheDir, "rootfs_tmp.tar.xz")
        context.assets.open(assetName).use { input ->
            FileOutputStream(tmpFile).use { output ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                val estimatedSize = input.available().toLong().coerceAtLeast(50_000_000L)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val pct = ((totalRead * 70) / estimatedSize).toInt().coerceAtMost(70)
                    _progress.value = Progress.Extracting(pct, "Extracting... ${totalRead / 1_000_000}MB")
                }
            }
        }

        // Extract using tar (available on Android)
        val extractCmd = if (assetName.endsWith(".xz")) {
            arrayOf("tar", "-xJf", tmpFile.absolutePath, "-C", rootfsDir.absolutePath)
        } else {
            arrayOf("tar", "-xzf", tmpFile.absolutePath, "-C", rootfsDir.absolutePath)
        }

        val process = Runtime.getRuntime().exec(extractCmd)
        val exitCode = process.waitFor()
        tmpFile.delete()

        if (exitCode != 0) {
            val err = process.errorStream.bufferedReader().readText()
            throw RuntimeException("tar extraction failed (exit $exitCode): $err")
        }

        Log.i(TAG, "Rootfs extracted to: ${rootfsDir.absolutePath}")
    }

    /**
     * Copy proot, bash, busybox from nativeLibraryDir (jniLibs) to a bin directory.
     * The binaries are disguised as .so files in the APK.
     */
    private fun setupBinaries(context: Context) {
        val binDir = File(context.filesDir, "bin")
        binDir.mkdirs()

        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        // Map: .so name in jniLibs → target name in bin/
        val binaries = mapOf(
            "libproot.so" to "proot",
            "libbash.so" to "bash",
            "libbusybox.so" to "busybox"
        )

        for ((soName, targetName) in binaries) {
            val srcFile = File(nativeLibDir, soName)
            val dstFile = File(binDir, targetName)

            if (srcFile.exists()) {
                srcFile.copyTo(dstFile, overwrite = true)
                dstFile.setExecutable(true)
                Log.i(TAG, "Installed binary: $targetName")
            } else {
                Log.w(TAG, "Binary not found: $srcFile (skipping)")
            }
        }
    }

    /**
     * Run the setup script inside the rootfs to configure:
     * - Fake /proc entries
     * - DNS (/etc/resolv.conf)
     * - apt sources
     */
    private fun runSetupScript(context: Context, rootfsDir: File) {
        // Extract setup script from assets
        val setupScript = File(context.filesDir, "bin/setup.sh")
        try {
            context.assets.open("setup.sh").use { input ->
                FileOutputStream(setupScript).use { output ->
                    input.copyTo(output)
                }
            }
            setupScript.setExecutable(true)
        } catch (e: Exception) {
            Log.w(TAG, "No setup.sh in assets, skipping configuration step")
            return
        }

        // Run setup script using the rootfs's sh via proot
        val prootPath = getProotPath(context)
        val prootFile = File(prootPath)
        if (!prootFile.exists()) {
            Log.w(TAG, "proot not found, skipping setup script")
            return
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                prootPath,
                "-r", rootfsDir.absolutePath,
                "-0",
                "/bin/sh", "/home/setup.sh"
            ))
            // Copy setup script into rootfs for proot access
            setupScript.copyTo(File(rootfsDir, "home/setup.sh"), overwrite = true)

            // Re-run with the script in place
            val process2 = Runtime.getRuntime().exec(arrayOf(
                prootPath,
                "-r", rootfsDir.absolutePath,
                "-0",
                "/bin/sh", "/home/setup.sh"
            ))
            val exitCode = process2.waitFor()
            File(rootfsDir, "home/setup.sh").delete()

            Log.i(TAG, "Setup script completed (exit $exitCode)")
        } catch (e: Exception) {
            Log.w(TAG, "Setup script failed: ${e.message}")
            // Non-fatal — the environment may still work
        }
    }

    /**
     * Reset the environment (delete rootfs and marker).
     * Useful for re-installation or troubleshooting.
     */
    suspend fun reset(context: Context) = withContext(Dispatchers.IO) {
        File(context.filesDir, MARKER_FILE).delete()
        getRootfsDir(context).deleteRecursively()
        File(context.filesDir, "bin").deleteRecursively()
        _progress.value = Progress.Idle
    }
}
