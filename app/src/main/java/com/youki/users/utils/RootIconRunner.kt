package com.youki.users.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import kotlin.system.exitProcess

/**
 * Tiny command-line entry point, started as root with:
 *
 *   CLASSPATH=<this apk> app_process /system/bin com.youki.users.utils.RootIconRunner <userId> <png>
 *
 * Root is allowed to call IUserManager.setUserIcon (needs MANAGE_USERS), which the
 * adb/shell user is not on many devices. Prints ICON_OK or ICON_ERR: <reason>.
 */
object RootIconRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val userId = args[0].toInt()
            val bmp = BitmapFactory.decodeFile(args[1])
                ?: throw IllegalStateException("cannot decode image")

            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "user") as IBinder
            val stub = Class.forName("android.os.IUserManager\$Stub")
            val iUm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            iUm!!.javaClass
                .getMethod("setUserIcon", Int::class.javaPrimitiveType, Bitmap::class.java)
                .invoke(iUm, userId, bmp)
            println("ICON_OK")
        } catch (e: Throwable) {
            val root = generateSequence(e) { it.cause }.last()
            println("ICON_ERR: ${root.javaClass.simpleName}: ${root.message?.take(160)}")
        }
        exitProcess(0)
    }
}
