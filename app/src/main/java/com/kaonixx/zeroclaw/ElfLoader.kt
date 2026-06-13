package com.kaonixx.zeroclaw

import android.util.Log
import java.io.File

object ElfLoader {
    private const val TAG = "ElfLoader"
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            try {
                System.loadLibrary("elfloader")
                loaded = true
                Log.i(TAG, "libelfloader.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libelfloader.so", e)
            }
        }
    }

    private external fun loadAndExecBinary(binPath: String, args: Array<String>, env: Array<String>): Int

    fun exec(
        binary: File,
        args: List<String>,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): Process? {
        ensureLoaded()
        if (!loaded) { Log.e(TAG, "Library not loaded"); return null }
        if (!binary.exists()) { Log.e(TAG, "Binary not found"); return null }

        val envList = mutableListOf(
            "HOME=${workDir?.absolutePath ?: "/data/data/com.kaonixx.zeroclaw/files"}",
            "RUST_LOG=info", "TERM=dumb", "PATH=/system/bin:/system/xbin"
        )
        extraEnv.forEach { (k, v) -> envList.add("$k=$v") }
        try {
            val appInfo = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.app.Application
            appInfo?.let { envList.add("LD_LIBRARY_PATH=${it.applicationInfo.nativeLibraryDir}:/system/lib64:/vendor/lib64") }
        } catch (_: Exception) {}

        Log.i(TAG, "Loading: ${binary.name} ${args.joinToString(" ").take(200)}")
        val pid = loadAndExecBinary(binary.absolutePath, args.toTypedArray(), envList.toTypedArray())
        if (pid < 0) { Log.e(TAG, "loadAndExecBinary error: $pid"); return null }
        Log.i(TAG, "Started PID: $pid")
        return ElfProcess(pid)
    }
}

class ElfProcess(private val pid: Int) : Process() {
    private var destroyed = false
    override fun getOutputStream() = throw UnsupportedOperationException()
    override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int { waitFor(0L, java.util.concurrent.TimeUnit.MILLISECONDS); return exitValue() }
    override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean {
        val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
        while (System.currentTimeMillis() < deadline || timeout == 0L) {
            if (!isAlive()) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
        return !isAlive()
    }
    override fun exitValue(): Int { if (isAlive()) throw IllegalThreadStateException(); return readExitStatus() }
    override fun destroy() { android.os.Process.killProcess(pid); destroyed = true }
    override fun isAlive(): Boolean { if (destroyed) return false; return File("/proc/$pid/status").exists() }
    private fun readExitStatus(): Int {
        return try {
            for (line in File("/proc/$pid/status").readLines())
                if (line.startsWith("State:")) return if ("ZSR".contains(line.substringAfter(":").trim().firstOrNull() ?: ' ')) 0 else -1
            -1
        } catch (_: Exception) { -1 }
    }
}
