package com.youki.users.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin Shizuku wrapper — only what user management needs:
 *  - connection / permission state + listeners
 *  - running shell commands as the shell user (pm create-user, pm list users, ...)
 *  - wrapping a system service binder so hidden APIs run with shell privileges
 *
 * No "grant everything" batch any more: `pm create-user` etc. run as the shell
 * user, so this app itself needs no extra permissions.
 */
class ShizukuManager private constructor(private val context: Context) {

    companion object {
        const val REQUEST_CODE = 0x5317
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

        @Volatile private var instance: ShizukuManager? = null
        fun getInstance(ctx: Context) =
            instance ?: synchronized(this) {
                instance ?: ShizukuManager(ctx.applicationContext).also { instance = it }
            }
    }

    // One listener per key so several screens can listen without evicting each other
    private val boundListeners   = ConcurrentHashMap<String, () -> Unit>()
    private val grantedListeners = ConcurrentHashMap<String, () -> Unit>()
    private val deniedListeners  = ConcurrentHashMap<String, () -> Unit>()
    private val unboundListeners = ConcurrentHashMap<String, () -> Unit>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var binderAlive = false
    @Volatile private var permissionGranted = false

    /** Fires immediately (on Main) if Shizuku is already connected. */
    fun addOnBoundListener(key: String, cb: () -> Unit) {
        boundListeners[key] = cb
        if (isAvailable) mainHandler.post { cb() }
    }
    fun removeOnBoundListener(key: String) { boundListeners.remove(key) }

    fun addOnGrantedListener(key: String, cb: () -> Unit) { grantedListeners[key] = cb }
    fun removeOnGrantedListener(key: String) { grantedListeners.remove(key) }

    fun addOnDeniedListener(key: String, cb: () -> Unit) { deniedListeners[key] = cb }
    fun removeOnDeniedListener(key: String) { deniedListeners.remove(key) }

    fun addOnUnboundListener(key: String, cb: () -> Unit) { unboundListeners[key] = cb }
    fun removeOnUnboundListener(key: String) { unboundListeners.remove(key) }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        binderAlive = true
        permissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }

        mainHandler.post {
            boundListeners.values.forEach { it() }
            if (permissionGranted) grantedListeners.values.forEach { it() }
            else try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Exception) {}
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderAlive = false
        permissionGranted = false
        mainHandler.post { unboundListeners.values.forEach { it() } }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            permissionGranted = result == PackageManager.PERMISSION_GRANTED
            mainHandler.post {
                if (permissionGranted) grantedListeners.values.forEach { it() }
                else deniedListeners.values.forEach { it() }
            }
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    val isAvailable: Boolean
        get() {
            if (binderAlive) return true
            val alive = try { Shizuku.pingBinder() } catch (e: Exception) { false }
            if (alive) binderAlive = true
            return alive
        }

    val hasPermission: Boolean
        get() {
            if (!isAvailable) return false
            if (permissionGranted) return true
            val granted = try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) { false }
            if (granted) permissionGranted = true
            return granted
        }

    /**
     * - Shizuku running  -> ask for its permission
     * - Shizuku installed but not running -> open it so the person can start it
     * - Not installed -> fall back to checking root
     */
    fun requestPermission() {
        when {
            hasPermission -> mainHandler.post { grantedListeners.values.forEach { it() } }
            isAvailable -> try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Exception) {}
            isShizukuAppInstalled() -> openShizukuApp()
            else -> RootManager.getInstance(context).invalidateCache()
        }
    }

    private fun isShizukuAppInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    private fun openShizukuApp() {
        try {
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    //  Shell — no waitFor()/exitValue(): ShizukuRemoteProcess sometimes throws
    //  "process hasn't exited"; reading stdout to EOF is enough here.
    // ─────────────────────────────────────────────────────────────

    private var newProcessMethod: Method? = null

    @Suppress("DiscouragedPrivateApi")
    private fun newProcess(cmd: String): ShizukuRemoteProcess {
        val m = newProcessMethod ?: Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).also { it.isAccessible = true; newProcessMethod = it }
        @Suppress("UNCHECKED_CAST")
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as ShizukuRemoteProcess
    }

    private fun execute(cmd: String): String {
        val p = newProcess(cmd)
        var err = ""
        val errThread = Thread { runCatching { err = p.errorStream.bufferedReader().readText() } }
        errThread.start()
        val out = p.inputStream.bufferedReader().readText().trim()
        errThread.join(500)
        runCatching { p.destroy() }
        return buildString {
            append(out)
            if (err.isNotBlank()) { if (isNotEmpty()) append('\n'); append(err.trim()) }
        }
    }

    fun runShell(cmd: String, scope: CoroutineScope = internalScope, onResult: (String) -> Unit) {
        if (!hasPermission) { onResult(""); return }
        scope.launch(Dispatchers.IO) {
            val r = try { execute(cmd) } catch (e: Exception) { "" }
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }

    /** Blocking — call only from a background thread. */
    fun runShellSync(cmd: String): String? {
        if (!hasPermission) return null
        return try { execute(cmd) } catch (e: Exception) { null }
    }

    /** Same as [runShellSync] but shows the exception instead of swallowing it. */
    fun diagnose(cmd: String): String {
        if (!isAvailable)   return "Shizuku: binder not connected (isAvailable = false)"
        if (!hasPermission) return "Shizuku: connected but without permission (hasPermission = false)"
        return try {
            val out = execute(cmd)
            "output (${out.length} chars):\n" + out.ifBlank { "(empty)" }
        } catch (e: Exception) {
            "Exception in newProcess(\"$cmd\"):\n${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        binderAlive = false
        permissionGranted = false
        boundListeners.clear(); grantedListeners.clear(); deniedListeners.clear(); unboundListeners.clear()
        internalScope.cancel()
    }
}
