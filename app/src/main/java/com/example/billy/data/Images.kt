package com.example.billy.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.min

const val PROFILE_PHOTO_PX = 512

/**
 * Turns a picked image into a square [PROFILE_PHOTO_PX] JPEG suitable for upload.
 *
 * Re-encoding from pixels means none of the original file's metadata survives. In particular
 * EXIF GPS coordinates, camera model and timestamps are dropped, so a photo can't leak where
 * it was taken.
 */
suspend fun prepareProfilePhoto(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val decoded = decode(context, uri)
    val square = centerCropSquare(decoded, PROFILE_PHOTO_PX)
    if (square !== decoded) decoded.recycle()
    ByteArrayOutputStream().use { out ->
        square.compress(Bitmap.CompressFormat.JPEG, 85, out)
        square.recycle()
        out.toByteArray()
    }
}

private fun decode(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies EXIF orientation itself.
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val shortSide = min(info.size.width, info.size.height)
            if (shortSide > PROFILE_PHOTO_PX * 2) {
                val scale = PROFILE_PHOTO_PX * 2f / shortSide
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PROFILE_PHOTO_PX) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IOException("Couldn't read image")

    val rotation = resolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    if (rotation == 0f) return bitmap
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

private fun centerCropSquare(src: Bitmap, size: Int): Bitmap {
    val side = min(src.width, src.height)
    val x = (src.width - side) / 2
    val y = (src.height - side) / 2
    // Downscale to `size`; small images keep their native size rather than being upscaled.
    val scale = size.toFloat() / side
    val matrix = Matrix().apply { if (side > size) postScale(scale, scale) }
    return Bitmap.createBitmap(src, x, y, side, side, matrix, true)
}
