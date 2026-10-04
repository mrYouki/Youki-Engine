package com.youki.users.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.io.File

/**
 * AvatarDisplay — the single place that knows how to show a user's avatar
 * on any [ImageView], preferring an animated GIF (see
 * [MultiUserManager.saveUserAvatarGif] — "بروفايلك جيفت", set during
 * onboarding) over the plain static PNG when one exists.
 *
 * Every avatar surface in the app (OnboardingProfileFragment,
 * SettingsHeaderController's header, UserAdapter's Users list,
 * UserSwitcherPopup) calls [showOn] instead of decoding/loading the file
 * itself, so "does this user have an animated avatar" only needs to be
 * answered in one place.
 */
object AvatarDisplay {

    private const val TAG = "AvatarDisplay"

    /**
     * Loads and displays [userId]'s avatar on [imageView]: the animated
     * GIF if one is set, otherwise the static PNG via
     * [MultiUserManager.loadUserAvatar]/[MultiUserManager.getUserAvatarPath].
     * Returns true if anything was actually shown, so callers can decide
     * what to do with the placeholder/icon view when nothing was (mirrors
     * the existing per-call-site pattern of toggling a separate
     * placeholder ImageView's visibility).
     */
    fun showOn(imageView: ImageView, context: Context, userId: Int): Boolean {
        // Belt-and-suspenders on top of ShapeableImageView's own
        // cornerFull shape: a GIF is shown at its original, un-cropped
        // aspect ratio (see class doc), so if it isn't square its corners
        // can otherwise poke out past the round avatar background. An
        // explicit circular outline + clipToOutline masks that
        // unconditionally, regardless of the GIF's actual dimensions.
        applyCircularClip(imageView)

        val gifPath = MultiUserManager.getUserAvatarGifPath(context, userId)
        if (gifPath != null) {
            val shown = showGif(imageView, gifPath)
            if (shown) return true
            // Corrupt/unreadable GIF file — fall through to the static
            // avatar rather than leaving the view empty.
        }

        val bmp = MultiUserManager.loadUserAvatar(context, userId) ?: return false
        imageView.setImageBitmap(bmp)
        return true
    }

    /**
     * Forces [view] to always clip to a perfect circle matching its own
     * bounds, independent of whatever drawable/bitmap is currently set on
     * it. This is what turns "static image so ShapeableImageView's
     * cornerFull shape handles it" into "always circular no matter what",
     * which matters specifically for animated GIFs since those are shown
     * at their original aspect ratio rather than pre-cropped square.
     */
    private fun applyCircularClip(view: View) {
        if (view.outlineProvider is CircleOutline) return
        view.outlineProvider = CircleOutline
        view.clipToOutline = true
    }

    private object CircleOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private fun showGif(imageView: ImageView, gifPath: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(File(gifPath))
                val drawable = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
                }
                imageView.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.start()
                true
            } else {
                // Pre-API 28: no AnimatedImageDrawable — show the GIF's
                // first frame as a plain still image rather than nothing.
                val bmp = BitmapFactory.decodeFile(gifPath) ?: return false
                imageView.setImageBitmap(bmp)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "showGif failed for $gifPath: ${e.message}")
            false
        }
    }
}
