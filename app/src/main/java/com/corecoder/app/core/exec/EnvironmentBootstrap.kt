package com.corecoder.app.core.exec

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Bootstraps the embedded Linux environment on first launch.
 *
 * Creates symbolic links from filesDir/bin/<name> → nativeLibraryDir/lib<name>.so.
 * The symlink has NO .so extension, so when execve() follows the link the kernel
 * loads the ELF binary directly — Android's linker (which intercepts .so filenames)
 * is completely bypassed. No file copying needed.
 */
object EnvironmentBootstrap {

    private const val TAG = "EnvBootstrap"
    private const val MARKER_FILE = ".corecoder_bootstrapped"
    private const val ROOTFS_DIR = "ubuntu"
    private const val BIN_DIR = "bin"

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

    /** Returns the rootfs directory path. */
    fun getRootfsDir(context: Context): File = File(context.filesDir, ROOTFS_DIR)

    /** Returns the proot binary path — directly from nativeLibraryDir, no copy/symlink needed. */
    fun getProotPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    /** Returns the bash binary path — directly from nativeLibraryDir, no copy/symlink needed. */
    fun getBashPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libbash.so").absolutePath

    /** Check if the environment has already been bootstrapped. */
    fun isBootstrapped(context: Context): Boolean =
        File(context.filesDir, MARKER_FILE).exists()

    /**
     * Run the bootstrap process.
     *
     * Binaries (proot, bash) are read directly from nativeLibraryDir — no setup needed.
     * Only extracts the Ubuntu rootfs on first launch.
     */
    suspend fun bootstrap(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isBootstrapped(context)) {
            _progress.value = Progress.Ready
            return@withContext true
        }

        try {
            // Extract rootfs using pure-Java tar (supports symlinks, no binary needed)
            _progress.value = Progress.Extracting(5, "Extracting Ubuntu rootfs...")
            val rootfsDir = getRootfsDir(context)
            rootfsDir.mkdirs()
            extractRootfs(context, rootfsDir)

            // Run setup script (DNS, apt sources, fake /proc)
            _progress.value = Progress.Configuring
            runSetupScript(context, rootfsDir)

            // Mark as done
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
     * Create symbolic links from filesDir/bin/<name> → nativeLibraryDir/lib<name>.so.
     *
     * The symlink filename has NO .so extension, so when execve() follows the link,
     * the kernel reads ELF magic bytes directly and executes the binary.
     * Android's linker only intercepts filenames ending in .so, so symlinks bypass it.
     *
     * This avoids copying files entirely — zero extra storage, instant setup.
     */
    private fun setupBinaries(context: Context) {
        val binDir = File(context.filesDir, BIN_DIR)
        binDir.mkdirs()

        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        // Map: .so name in jniLibs → symlink name in bin/ (NO .so extension)
        val binaries = mapOf(
            "libproot.so" to "proot",
            "libbash.so" to "bash"
        )

        for ((soName, linkName) in binaries) {
            val soFile = File(nativeLibDir, soName)
            val linkFile = File(binDir, linkName)

            if (!soFile.exists()) {
                Log.w(TAG, "Binary not found: $soFile (skipping)")
                continue
            }

            try {
                // Set execute permission on the .so file itself
                soFile.setExecutable(true, false)

                // Delete existing link/file to avoid FileAlreadyExistsException
                Files.deleteIfExists(linkFile.toPath())

                // Create symbolic link: bin/proot → nativeLibDir/libproot.so
                Files.createSymbolicLink(linkFile.toPath(), soFile.toPath())

                // Verify the link works
                if (linkFile.exists() && linkFile.canExecute()) {
                    Log.i(TAG, "Created symlink: $linkName → $soFile (${soFile.length()} bytes)")
                } else {
                    Log.e(TAG, "Symlink verification failed for $linkName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create symlink for $linkName", e)
            }
        }
    }

    /**
     * Extract the Ubuntu rootfs tarball from assets into the target directory.
     * Uses pure-Java TarExtractor (Apache Commons Compress) — no external binaries needed.
     * This avoids SIGSYS/seccomp issues that can occur when executing busybox on some devices.
     */
    private fun extractRootfs(context: Context, rootfsDir: File) {
        val assetName = listOf(
            "ubuntu-rootfs.tar.xz",
            "ubuntu-base.tar.gz",
            "ubuntu-base.tar.xz",
            "ubuntu-base.tar"
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
                "Place ubuntu-rootfs.tar.xz, ubuntu-base.tar.gz, or ubuntu-base.tar in app/src/main/assets/"
            )
        }

        Log.i(TAG, "Extracting rootfs from: $assetName (pure Java)")

        context.assets.open(assetName).use { input ->
            TarExtractor.extract(
                input = input,
                targetDir = rootfsDir,
                assetName = assetName
            ) { bytesExtracted, fileCount ->
                val mb = bytesExtracted / 1_000_000
                _progress.value = Progress.Extracting(
                    percent = (5 + (mb * 70 / 200).toInt()).coerceIn(5, 90),
                    message = "Extracting... ${mb}MB, $fileCount files"
                )
            }
        }

        Log.i(TAG, "Rootfs extracted to: ${rootfsDir.absolutePath}")
    }

    /**
     * Run the setup script inside the rootfs to configure:
     * - Fake /proc entries
     * - DNS (/etc/resolv.conf)
     * - apt sources
     */
    private fun runSetupScript(context: Context, rootfsDir: File) {
        // Extract setup script from assets directly into rootfs
        val targetScript = File(rootfsDir, "home/setup.sh")
        try {
            targetScript.parentFile?.mkdirs()
            context.assets.open("setup.sh").use { input ->
                FileOutputStream(targetScript).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No setup.sh in assets, skipping configuration step")
            return
        }

        // Run setup script using proot via symlink (bin/proot → nativeLibDir/libproot.so)
        val prootPath = getProotPath(context)
        if (!File(prootPath).exists()) {
            Log.w(TAG, "proot not found at $prootPath, skipping setup script")
            return
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                prootPath,
                "-r", rootfsDir.absolutePath,
                "-0",
                "/bin/sh", "/home/setup.sh"
            ))

            // Wait with timeout — proot may hang on emulators (houdini/ptrace conflict)
            val completed = process.waitFor(30, TimeUnit.SECONDS)
            if (!completed) {
                Log.w(TAG, "Setup script timed out after 30s, killing process")
                process.destroyForcibly()
            }

            targetScript.delete()
            val exitCode = if (completed) process.exitValue() else -1
            Log.i(TAG, "Setup script completed (exit $exitCode, timed_out=${!completed})")
        } catch (e: Exception) {
            Log.w(TAG, "Setup script failed: ${e.message}")
            // Non-fatal — the environment may still work
        }
    }

    /**
     * Reset the environment (delete rootfs, binaries, and marker).
     * Useful for re-installation or troubleshooting.
     */
    suspend fun reset(context: Context) = withContext(Dispatchers.IO) {
        File(context.filesDir, MARKER_FILE).delete()
        getRootfsDir(context).deleteRecursively()
        // Symlinks in bin/ are recreated on next bootstrap — safe to delete
        File(context.filesDir, BIN_DIR).deleteRecursively()
        _progress.value = Progress.Idle
    }
}
