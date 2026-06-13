package com.kaonixx.zeroclaw

import android.util.Log
import java.io.File

/**
 * Executes a native binary by loading it into a memfd (anonymous memory)
 * and forking+execing from there. This bypasses Android's noexec mount
 * flag on app data directories.
 *
 * The native library libmemexec.so is loaded automatically.
 */
object NativeExecutor {
    private const val TAG = "NativeExecutor"
    private var loaded = false

    /** Ensure the native library is loaded. */
    private fun ensureLoaded() {
        if (!loaded) {
            try {
                System.loadLibrary("memexec")
                loaded = true
                Log.i(TAG, "libmemexec.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libmemexec.so", e)
            }
        }
    }

    /**
     * Native method: create a memfd, copy the binary into it, fork,
     * and exec from /proc/self/fd/N.
     *
     * @param binPath absolute path to the native binary
     * @param args    command-line arguments (without argv[0])
     * @param env     environment variables as "KEY=VALUE" strings
     * @return PID of the child process, or negative on error
     */
    private external fun execBinary(binPath: String, args: Array<String>, env: Array<String>): Int

    /**
     * Execute a native binary from a memfd. High-level Kotlin wrapper.
     *
     * @param binary   the native binary file
     * @param args     command-line arguments
     * @param workDir  working directory for the process
     * @param extraEnv extra environment variables
     * @return the launched Process, or null on failure
     */
    fun exec(
        binary: File,
        args: List<String>,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): Process? {
        ensureLoaded()
        if (!loaded) {
            Log.e(TAG, "Native library not loaded")
            return null
        }
        if (!binary.exists()) {
            Log.e(TAG, "Binary not found: ${binary.absolutePath}")
            return null
        }

        // Build environment
        val envList = mutableListOf(
            "HOME=${workDir?.absolutePath ?: "/data/data/com.kaonixx.zeroclaw/files"}",
            "RUST_LOG=info",
            "TERM=dumb",
            "PATH=/system/bin:/system/xbin"
        )
        extraEnv.forEach { (k, v) -> envList.add("$k=$v") }

        // Add native library path so the binary can find its deps
        try {
            val appInfo = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.app.Application
            appInfo?.let {
                val libDir = it.applicationInfo.nativeLibraryDir
                envList.add("LD_LIBRARY_PATH=$libDir:/system/lib64:/vendor/lib64")
            }
        } catch (_: Exception) {}

        Log.i(TAG, "Executing: ${binary.name} ${args.joinToString(" ").take(200)}")

        val pid = execBinary(
            binary.absolutePath,
            args.toTypedArray(),
            envList.toTypedArray()
        )

        if (pid < 0) {
            Log.e(TAG, "execBinary returned error code $pid")
            return null
        }

        Log.i(TAG, "Binary started with PID $pid")
        return MemfdProcess(pid)
    }
}

/**
 * Minimal Process wrapper around a PID from [NativeExecutor].
 *
 * The child process's stdout/stderr go to logcat (inherited from the
 * parent Android process). This wrapper provides PID tracking, lifecycle
 * management, and exit-code detection via /proc.
 */
class MemfdProcess(private val pid: Int) : Process() {
    private var destroyed = false

    override fun getOutputStream() = throw UnsupportedOperationException("stdin not supported")
    override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))

    override fun waitFor() = waitFor(0, java.util.concurrent.TimeUnit.MILLISECONDS)

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
        while (System.currentTimeMillis() < deadline || timeout == 0L) {
            if (!isAlive()) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
        return !isAlive()
    }

    override fun exitValue(): Int {
        if (isAlive()) throw IllegalThreadStateException("Process $pid still running")
        return readExitStatus()
    }

    override fun destroy() {
        android.os.Process.killProcess(pid)
        destroyed = true
    }

    override fun isAlive(): Boolean {
        if (destroyed) return false
        return File("/proc/$pid/status").exists()
    }

    /** Read exit status from /proc/[pid]/status (state field). */
    private fun readExitStatus(): Int {
        return try {
            val status = File("/proc/$pid/status").readLines()
            for (line in status) {
                if (line.startsWith("State:")) {
                    val state = line.substringAfter("State:").trim().firstOrNull()
                    // 'Z' = zombie (exited but not reaped), 'S' = sleeping, 'R' = running
                    return if (state == 'Z' || state == 'S' || state == 'R') 0 else -1
                }
            }
            -1
        } catch (_: Exception) { -1 }
    }
}
