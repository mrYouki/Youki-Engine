package com.youki.users.utils

import android.content.Context
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root fallback (su). Only used to run shell commands when Shizuku isn't
 * available. The availability check is cached for 60s instead of spawning
 * `su` on every call.
 */
class RootManager private constructor(@Suppress("unused") private val context: Context) {

    companion object {
        @Volatile private var instance: RootManager? = null
        fun getInstance(ctx: Context) =
            instance ?: synchronized(this) {
                instance ?: RootManager(ctx.applicationContext).also { instance = it }
            }
        private const val CACHE_TTL_MS = 60_000L
    }

    @Volatile private var cachedAvailable: Boolean? = null
    @Volatile private var cacheTime: Long = 0L

    val isAvailable: Boolean
        get() {
            val now = System.currentTimeMillis()
            cachedAvailable?.let { if (now - cacheTime < CACHE_TTL_MS) return it }
            val result = checkRoot()
            cachedAvailable = result
            cacheTime = now
            return result
        }

    fun invalidateCache() { cachedAvailable = null }

    private fun checkRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        var output = ""
        val t = Thread { output = p.inputStream.bufferedReader().readText() }
        t.start()
        p.errorStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        t.join(1000)
        output.contains("uid=0")
    } catch (e: Exception) {
        arrayOf("/sbin/su", "/system/sbin/su", "/system/bin/su", "/system/xbin/su", "/su/bin/su")
            .any { File(it).canExecute() }
    }

    /** Blocking — call only from a background thread. */
    fun runShellSync(command: String): String? =
        try { executeRoot(command) } catch (e: Exception) { null }

    fun diagnose(cmd: String): String {
        if (!isAvailable) return "Root: su not available (isAvailable = false)"
        return "output:\n" + executeRoot(cmd).ifBlank { "(empty)" }
    }

    private fun executeRoot(command: String): String = try {
        val process = Runtime.getRuntime().exec("su")
        DataOutputStream(process.outputStream).use {
            it.writeBytes("$command\n")
            it.writeBytes("exit\n")
            it.flush()
        }
        var stdout = ""
        var stderr = ""
        val t1 = Thread { stdout = process.inputStream.bufferedReader().readText().trim() }
        val t2 = Thread { stderr = process.errorStream.bufferedReader().readText().trim() }
        t1.start(); t2.start()
        process.waitFor(10, TimeUnit.SECONDS)
        t1.join(2000); t2.join(2000)
        buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) { if (isNotEmpty()) append('\n'); append(stderr) }
            if (isEmpty()) append("(no output)")
        }
    } catch (e: Exception) { "error" }
}
