package com.example.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class StickerFileStorage(private val context: Context) {

    private val packsRootDir: File
        get() = File(context.filesDir, "sticker_packs").apply { mkdirs() }

    fun getPackDir(packId: String): File {
        val sanitized = sanitizeSegment(packId)
        return File(packsRootDir, sanitized).apply { mkdirs() }
    }

    fun getAssetFile(packId: String, fileName: String): File {
        val sanitizedPack = sanitizeSegment(packId)
        val sanitizedFile = sanitizeFileName(fileName)
        return File(File(packsRootDir, sanitizedPack), sanitizedFile)
    }

    fun writeStickerBytes(packId: String, fileName: String, bytes: ByteArray): File {
        val dir = getPackDir(packId)
        val target = File(dir, sanitizeFileName(fileName))
        val tmp = File(dir, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    fun writeTrayIconBitmap(packId: String, fileName: String, source: Bitmap): File {
        val scaled = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        canvas.drawColor(Color.TRANSPARENT)
        val srcRatio = source.width.toFloat() / source.height.coerceAtLeast(1)
        val pad = 4f
        val avail = 96f - pad * 2f
        val dstRect = if (srcRatio >= 1f) {
            val h = avail / srcRatio
            val top = pad + (avail - h) / 2f
            RectF(pad, top, pad + avail, top + h)
        } else {
            val w = avail * srcRatio
            val left = pad + (avail - w) / 2f
            RectF(left, pad, left + w, pad + avail)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, dstRect, paint)

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        var bytes = baos.toByteArray()
        if (bytes.size > 48 * 1024) {
            val webpOut = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            scaled.compress(Bitmap.CompressFormat.WEBP, 85, webpOut)
            bytes = webpOut.toByteArray()
        }
        scaled.recycle()
        return writeStickerBytes(packId, fileName, bytes)
    }

    fun writeDefaultTrayIcon(packId: String, fileName: String, accentHex: Int): File {
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentHex }
        canvas.drawRoundRect(RectF(6f, 6f, 90f, 90f), 24f, 24f, bgPaint)

        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(37f, 40f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(59f, 40f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawArc(RectF(30f, 38f, 66f, 68f), 15f, 150f, false, fgPaint)

        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bmp.recycle()
        return writeStickerBytes(packId, fileName, baos.toByteArray())
    }

    fun deleteStickerFile(packId: String, fileName: String) {
        val file = getAssetFile(packId, fileName)
        if (file.exists()) file.delete()
    }

    fun deletePackDir(packId: String) {
        val dir = File(packsRootDir, sanitizeSegment(packId))
        if (dir.exists()) dir.deleteRecursively()
    }

    fun hasEnoughDiskSpace(requiredBytes: Long = 5L * 1024L * 1024L): Boolean {
        return context.filesDir.usableSpace >= requiredBytes
    }

    private fun sanitizeSegment(input: String): String {
        val cleaned = input.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return cleaned.ifBlank { "default" }
    }

    private fun sanitizeFileName(input: String): String {
        val base = File(input).name
        val cleaned = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return cleaned.ifBlank { "asset.webp" }
    }
}
