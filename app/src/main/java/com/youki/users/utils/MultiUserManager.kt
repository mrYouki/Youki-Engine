package com.youki.users.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import com.youki.users.R
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

/**
 * MultiUserManager — create / list / switch / remove users + user avatars.
 *
 * Standalone version: no app cloning and no auto-provisioning. Creating a user
 * is `pm create-user` + (optionally) setting the avatar, nothing else.
 */
@SuppressLint("MissingPermission")
object MultiUserManager {

    private const val TAG = "YoukiUsers"

    data class YoukiUser(
        val id: Int,
        val name: String,
        val isCurrentUser: Boolean,
        val isRunning: Boolean,
        val avatarPath: String? = null,
        /** Changes whenever the avatar file changes, so the list knows to redraw it. */
        val avatarVersion: Long = 0L
    )

    /** Result of [createUser]. [avatarOk] is null when no avatar was requested. */
    data class CreateResult(
        val ok: Boolean,
        val userId: Int,
        val message: String,
        val avatarOk: Boolean? = null,
        val avatarMessage: String? = null
    )

    // ─────────────────────────────────────────────────────────────
    //  Privilege
    // ─────────────────────────────────────────────────────────────

    fun hasPrivilege(context: Context): Boolean =
        ShizukuManager.getInstance(context).hasPermission || RootManager.getInstance(context).isAvailable

    // ─────────────────────────────────────────────────────────────
    //  Listing
    // ─────────────────────────────────────────────────────────────

    suspend fun listUsers(context: Context): List<YoukiUser> {
        val shizuku = ShizukuManager.getInstance(context)
        val root    = RootManager.getInstance(context)
        val hasPriv = shizuku.hasPermission || root.isAvailable

        suspend fun tryOnce(): List<YoukiUser> = suspendCancellableCoroutine { cont ->
            fun deliver(raw: String) {
                if (!cont.isActive) return
                cont.resume(if (raw.isNotBlank()) parseUsers(context, raw) else emptyList()) {}
            }
            when {
                shizuku.hasPermission -> shizuku.runShell("pm list users") { deliver(it) }
                root.isAvailable      -> Thread { deliver(root.runShellSync("pm list users") ?: "") }.start()
                else -> if (cont.isActive) cont.resume(emptyList()) {}
            }
        }

        // The first call right after the Shizuku binder connects can come back
        // empty (timing race) — retry twice before calling it a real empty result.
        var result = tryOnce()
        var attempt = 0
        while (result.isEmpty() && hasPriv && attempt < 2) {
            delay(300L * (attempt + 1))
            result = tryOnce()
            attempt++
        }
        return result.ifEmpty { listUsersViaApi(context) }
    }

    private fun parseUsers(context: Context, raw: String): List<YoukiUser> {
        val currentId = getCurrentUserId()
        // UserInfo{10:John:230} running   — names may contain ':' so (.*?) is non-greedy
        val regex = Regex("""UserInfo\{(\d+):(.*?):[^:{}]*\}(\s*running)?""")
        return regex.findAll(raw).mapNotNull { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            YoukiUser(
                id            = id,
                name          = m.groupValues[2].trim(),
                isCurrentUser = id == currentId,
                isRunning     = m.groupValues[3].isNotBlank(),
                avatarPath    = getUserAvatarPath(context, id),
                avatarVersion = getAvatarVersion(context, id)
            )
        }.toList().sortedBy { it.id }
    }

    @SuppressLint("NewApi")
    private fun listUsersViaApi(context: Context): List<YoukiUser> = try {
        val um      = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        val current = getCurrentUserId()
        val users   = um.javaClass.getMethod("getUsers", Boolean::class.java).invoke(um, true) as List<*>
        users.mapNotNull { info ->
            val cls  = info?.javaClass ?: return@mapNotNull null
            val id   = cls.getField("id").getInt(info)
            val name = cls.getField("name").get(info) as? String
                ?: context.getString(R.string.default_user_name, id)
            YoukiUser(id, name, id == current, false, getUserAvatarPath(context, id), getAvatarVersion(context, id))
        }.sortedBy { it.id }
    } catch (e: Exception) {
        Log.e(TAG, "listUsersViaApi: ${e.message}")
        emptyList()
    }

    fun diagnoseUserListing(context: Context): String = buildString {
        val shizuku = ShizukuManager.getInstance(context)
        val root    = RootManager.getInstance(context)
        appendLine(context.getString(R.string.diag_status))
        appendLine("Shizuku: available=${shizuku.isAvailable}, permission=${shizuku.hasPermission}")
        appendLine("Root: available=${root.isAvailable}")
        appendLine("Current user id: ${getCurrentUserId()}")
        appendLine()
        appendLine("pm list users:")
        when {
            shizuku.hasPermission -> appendLine(shizuku.diagnose("pm list users"))
            root.isAvailable      -> appendLine(root.diagnose("pm list users"))
            else                  -> appendLine(context.getString(R.string.diag_no_privilege))
        }
        appendLine()
        appendLine("UserManager.getUsers(true):")
        val api = listUsersViaApi(context)
        if (api.isEmpty()) appendLine("  (empty)")
        else api.forEach { appendLine("  id=${it.id}  name=\"${it.name}\"  current=${it.isCurrentUser}") }
    }

    // ─────────────────────────────────────────────────────────────
    //  Create — user + optional avatar. That's all (no cloning).
    // ─────────────────────────────────────────────────────────────

    /**
     * Creates the user, then (if [avatarUri] != null) applies it as the avatar.
     * A failed avatar never fails the creation — the user already exists by then.
     */
    suspend fun createUser(
        context: Context,
        name: String,
        avatarUri: Uri?
    ): CreateResult = withContext(Dispatchers.IO) {
        val raw    = runPrivileged(context, "pm create-user \"${name.sanitize()}\"")
        val userId = extractCreatedUserId(raw)
            ?: return@withContext CreateResult(false, -1, context.getString(R.string.create_user_failed, raw))

        if (avatarUri == null) {
            return@withContext CreateResult(true, userId, context.getString(R.string.create_user_success, userId))
        }
        val (avatarOk, avatarMsg) = applyPickedAvatar(context, userId, avatarUri)
        CreateResult(true, userId, context.getString(R.string.create_user_success, userId), avatarOk, avatarMsg)
    }

    // ─────────────────────────────────────────────────────────────
    //  Remove / switch
    // ─────────────────────────────────────────────────────────────

    suspend fun removeUser(context: Context, userId: Int): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (userId == 0) return@withContext Pair(false, context.getString(R.string.remove_user_primary_blocked))
        val raw = runPrivileged(context, "pm remove-user $userId")
        val ok  = raw.contains("Success", ignoreCase = true) || raw.isSuccess()
        Pair(ok, if (ok) context.getString(R.string.remove_user_success, userId)
                 else context.getString(R.string.remove_user_failed, raw))
    }

    suspend fun switchToUser(context: Context, userId: Int): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val raw = runPrivileged(context, "am switch-user $userId")
        val ok  = raw.isSuccess()
        Pair(ok, if (ok) context.getString(R.string.switch_user_success, userId)
                 else context.getString(R.string.switch_user_failed, raw))
    }

    // ─────────────────────────────────────────────────────────────
    //  Avatars
    // ─────────────────────────────────────────────────────────────

    /**
     * Single entry point for "the person picked an image/GIF as this user's avatar".
     *  - GIF (sniffed from magic bytes): the animated file is kept for this app's own UI,
     *    and its first frame is cropped + registered as the system icon.
     *  - Otherwise: center-cropped to a square and registered as the system icon.
     */
    suspend fun applyPickedAvatar(context: Context, userId: Int, uri: Uri): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val failed = Pair(false, context.getString(R.string.avatar_load_failed))
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) { null } ?: return@withContext failed

            if (isGif(bytes)) {
                val frame = decodeFirstGifFrame(bytes) ?: return@withContext failed
                saveUserAvatarGif(context, userId, bytes)
                val square = AvatarAutoCrop.cropToSquare(frame) ?: return@withContext failed
                setUserAvatar(context, userId, square)
            } else {
                deleteUserAvatarGif(context, userId)
                val square = AvatarAutoCrop.cropToSquare(context, uri) ?: return@withContext failed
                setUserAvatar(context, userId, square)
            }
        }

    /**
     * Saves the avatar inside the app (always works) and, only when root (`su`) is
     * available, also tries to apply it as the system icon. The message stays short and
     * neutral either way — a failed system icon is not an error from the person's view.
     */
    private fun setUserAvatar(context: Context, userId: Int, bitmap: Bitmap): Pair<Boolean, String> {
        val saved = saveUserAvatar(context, userId, bitmap)
        if (!saved) return Pair(false, context.getString(R.string.avatar_load_failed))
        // Best effort, silent, and off the UI path so the new photo shows up immediately
        Thread { setSystemUserIconWithRoot(context, userId) }.start()
        return Pair(true, context.getString(R.string.avatar_saved))
    }

    /**
     * Root only: runs [RootIconRunner] as uid 0 through `su` + `app_process` so the photo
     * also becomes the system user icon. Shizuku is not used for this (the adb/shell user
     * lacks MANAGE_USERS on many devices), and any failure is only logged.
     */
    private fun setSystemUserIconWithRoot(context: Context, userId: Int) {
        val root = RootManager.getInstance(context)
        if (!root.isAvailable) return
        val png = getUserAvatarPath(context, userId) ?: return
        try {
            val apk = context.applicationInfo.sourceDir
            val out = root.runShellSync(
                "CLASSPATH='$apk' app_process /system/bin ${RootIconRunner::class.java.name} $userId '$png'"
            ).orEmpty()
            if (!out.contains("ICON_OK")) Log.w(TAG, "system icon via root failed: ${out.take(200)}")
        } catch (e: Throwable) {
            Log.w(TAG, "system icon via root failed", e)
        }
    }

    /** GIF magic bytes: "GIF87a" or "GIF89a". */
    fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()

    private fun decodeFirstGifFrame(gifBytes: ByteArray): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(gifBytes))
            val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                decoder.isDecodeAsAlphaMaskEnabled = false
            }
            (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.let { anim ->
                val bmp = Bitmap.createBitmap(anim.intrinsicWidth, anim.intrinsicHeight, Bitmap.Config.ARGB_8888)
                anim.setBounds(0, 0, anim.intrinsicWidth, anim.intrinsicHeight)
                anim.draw(android.graphics.Canvas(bmp))
                bmp
            } ?: BitmapFactory.decodeByteArray(gifBytes, 0, gifBytes.size)
        } else {
            BitmapFactory.decodeByteArray(gifBytes, 0, gifBytes.size)
        }
    } catch (e: Exception) {
        Log.e(TAG, "decodeFirstGifFrame: ${e.message}")
        null
    }

    // ── Local avatar files ───────────────────────────────────────

    fun saveUserAvatar(context: Context, userId: Int, bitmap: Bitmap): Boolean = try {
        val dir = File(context.filesDir, "user_avatars").also { it.mkdirs() }
        File(dir, "user_$userId.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) { Log.e(TAG, "saveUserAvatar: ${e.message}"); false }

    fun saveUserAvatarGif(context: Context, userId: Int, gifBytes: ByteArray): Boolean = try {
        val dir = File(context.filesDir, "user_avatars").also { it.mkdirs() }
        File(dir, "user_${userId}_animated.gif").writeBytes(gifBytes)
        true
    } catch (e: Exception) { Log.e(TAG, "saveUserAvatarGif: ${e.message}"); false }

    fun getUserAvatarGifPath(context: Context, userId: Int): String? =
        File(context.filesDir, "user_avatars/user_${userId}_animated.gif").takeIf { it.exists() }?.absolutePath

    fun deleteUserAvatarGif(context: Context, userId: Int) {
        try { File(context.filesDir, "user_avatars/user_${userId}_animated.gif").delete() }
        catch (e: Exception) { Log.w(TAG, "deleteUserAvatarGif: ${e.message}") }
    }

    /** Sum of the PNG/GIF modified times: changes when either is replaced or deleted. */
    fun getAvatarVersion(context: Context, userId: Int): Long {
        val dir = File(context.filesDir, "user_avatars")
        return File(dir, "user_$userId.png").lastModified() + File(dir, "user_${userId}_animated.gif").lastModified()
    }

    fun getUserAvatarPath(context: Context, userId: Int): String? =
        File(context.filesDir, "user_avatars/user_$userId.png").takeIf { it.exists() }?.absolutePath

    fun deleteUserAvatar(context: Context, userId: Int) {
        try { File(context.filesDir, "user_avatars/user_$userId.png").delete() }
        catch (e: Exception) { Log.w(TAG, "deleteUserAvatar: ${e.message}") }
        deleteUserAvatarGif(context, userId)
    }

    /** Local avatar first; falls back to the system icon of that user (works for the current user). */
    fun loadUserAvatar(context: Context, userId: Int): Bitmap? {
        getUserAvatarPath(context, userId)?.let { return BitmapFactory.decodeFile(it) }
        return try {
            val um = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
            android.os.UserManager::class.java.getMethod("getUserIcon", Int::class.javaPrimitiveType)
                .invoke(um, userId) as? Bitmap
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────

    fun getCurrentUserId(): Int = try {
        android.os.UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
    } catch (e: Exception) { 0 }

    /** Always called from Dispatchers.IO */
    private fun runPrivileged(context: Context, cmd: String): String {
        val shizuku = ShizukuManager.getInstance(context)
        val root    = RootManager.getInstance(context)
        return when {
            shizuku.hasPermission -> shizuku.runShellSync(cmd) ?: ""
            root.isAvailable      -> root.runShellSync(cmd) ?: ""
            else                  -> "no_privilege"
        }
    }

    private fun String.isSuccess(): Boolean {
        if (isBlank() || this == "(no output)") return true
        val lower = lowercase()
        return !lower.contains("exception") &&
               !lower.contains("failed:") &&
               !lower.contains("unknown command") &&
               !lower.contains("no_privilege") &&
               !lower.startsWith("error:")
    }

    /** Strips shell metacharacters — the name ends up inside `pm create-user "..."`. */
    private fun String.sanitize(): String =
        replace(Regex("""["'\\`$;|&<>(){}]"""), "").take(64)

    private fun extractCreatedUserId(raw: String): Int? =
        Regex("""created user id (\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
}
