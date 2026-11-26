package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BitmapLoader {

    suspend fun loadWorkingBitmap(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1024
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Unable to decode image dimensions."))
            }

            val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@withContext Result.failure(IllegalArgumentException("Image file could not be decoded."))

            val rotation = readExifRotationDegrees(context, uri)
            val oriented = applyExifTransform(decoded, rotation)
            val scaled = scaleDownIfNeeded(oriented, maxDimension)
            Result.success(scaled)
        } catch (e: OutOfMemoryError) {
            Result.failure(IllegalStateException("Image is too large to process safely.", e))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to load image: ${e.message ?: "Corrupted file"}", e))
        }
    }

    suspend fun loadWorkingBitmapFromFile(
        file: File,
        maxDimension: Int = 1024
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Invalid sticker image file."))
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
                ?: return@withContext Result.failure(IllegalArgumentException("Failed to decode sticker."))
            Result.success(scaleDownIfNeeded(decoded, maxDimension))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        var halfW = width
        var halfH = height
        while (halfW > maxDim * 2 || halfH > maxDim * 2) {
            inSampleSize *= 2
            halfW /= 2
            halfH /= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun readExifRotationDegrees(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun applyExifTransform(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return ensureMutableArgb(bitmap)
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return ensureMutableArgb(rotated)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDim) return ensureMutableArgb(bitmap)
        val scale = maxDim.toFloat() / maxSide.toFloat()
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return ensureMutableArgb(scaled)
    }

    private fun ensureMutableArgb(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable) {
            bitmap.setHasAlpha(true)
            return bitmap
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        copy.setHasAlpha(true)
        bitmap.recycle()
        return copy
    }
}
