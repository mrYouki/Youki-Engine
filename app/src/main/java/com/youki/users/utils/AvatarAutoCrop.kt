package com.youki.users.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.Context
import kotlin.math.min

/**
 * Replaces the old CropAvatarActivity screen (drag/pinch a circle over the
 * photo, tap confirm) — that screen turned out to be a genuinely bad
 * experience (blank/stuck black-circle bug, an extra tap for something that
 * has an obvious default), so a picked still photo is now center-cropped to
 * a square automatically and set as the avatar immediately, no screen in
 * between at all.
 *
 * The crop itself — center square, no offset — is the same result
 * CircleCropView.exportCroppedBitmap() produced at its default scale (no
 * user drag/zoom applied): most people picking a profile photo never
 * touched the old screen's pan/zoom anyway, since a typical photo already
 * has the subject roughly centered.
 */
object AvatarAutoCrop {

    private const val MAX_DECODE_DIMENSION = 1600
    private const val OUTPUT_SIZE_PX = 512

    /**
     * Decodes [uri] downsampled (see [MAX_DECODE_DIMENSION] — avoids
     * OutOfMemoryError on a full wallpaper-resolution source image, the
     * same failure mode CropAvatarActivity's black-circle bug traced back
     * to), center-crops it to a square, and returns it scaled to
     * [OUTPUT_SIZE_PX]. Returns null if the image can't be decoded at all
     * (corrupt file, revoked permission on the Uri, etc.) — callers should
     * treat that as "picking failed", same as if the picker itself had
     * been cancelled.
     */
    fun cropToSquare(context: Context, uri: Uri): Bitmap? {
        val source = decodeDownsampled(context, uri) ?: return null
        val result = cropToSquare(source)
        if (result !== source) source.recycle()
        return result
    }

    /**
     * Center-crops an already-decoded [source] to a square scaled to [OUTPUT_SIZE_PX].
     * Does NOT recycle [source] — the caller owns it. Returns null if cropping fails.
     */
    fun cropToSquare(source: Bitmap): Bitmap? {
        val side = min(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        val square = try {
            Bitmap.createBitmap(source, x, y, side, side)
        } catch (e: Exception) {
            return null
        }
        if (square.width == OUTPUT_SIZE_PX) return square
        val scaled = Bitmap.createScaledBitmap(square, OUTPUT_SIZE_PX, OUTPUT_SIZE_PX, true)
        if (scaled !== square && square !== source) square.recycle()
        return scaled
    }

    private fun decodeDownsampled(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val longestSide = kotlin.math.max(bounds.outWidth, bounds.outHeight)
            while (longestSide / sampleSize > MAX_DECODE_DIMENSION) sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
