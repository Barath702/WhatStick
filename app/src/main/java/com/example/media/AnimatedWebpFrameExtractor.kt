package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

import java.io.InputStream

/**
 * Extracts individual frames and millisecond durations from an existing animated WebP file
 * by splitting its ANMF chunks into standalone single-frame WebPs for BitmapFactory decoding.
 */
object AnimatedWebpFrameExtractor {

    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024

    suspend fun extractFrames(context: Context, uri: Uri): Result<List<AnimatedFrameBitmap>> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    readBoundedBytes(stream, MAX_INPUT_BYTES)
                } ?: return@withContext Result.failure(IllegalArgumentException("Cannot open WebP file."))
                val frames = decodeAnimatedWebpFrames(bytes)
                if (frames.isEmpty()) {
                    Result.failure(IllegalArgumentException("No animation frames found in WebP."))
                } else {
                    Result.success(frames)
                }
            } catch (e: OutOfMemoryError) {
                Result.failure(IllegalStateException("Animated WebP is too large to decode safely.", e))
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Failed to decode animated WebP: ${e.message ?: "Invalid WebP"}", e))
            }
        }

    private fun readBoundedBytes(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalStateException("Animated WebP exceeds ${maxBytes / (1024 * 1024)} MB input limit.")
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    internal fun decodeAnimatedWebpFrames(bytes: ByteArray): List<AnimatedFrameBitmap> {
        if (bytes.size < 24) return emptyList()
        val frames = mutableListOf<AnimatedFrameBitmap>()
        var offset = 12
        try {
            while (offset + 8 <= bytes.size && frames.size < 180) {
                val fourCc = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSizeLong = readUint32LE(bytes, offset + 4).toLong() and 0xFFFFFFFFL
                val payloadStartLong = offset.toLong() + 8L
                if (payloadStartLong + chunkSizeLong > bytes.size.toLong()) break
                val chunkSize = chunkSizeLong.toInt()
                val payloadStart = payloadStartLong.toInt()

                if (fourCc == "ANMF" && chunkSize > 16) {
                    val durationMs = readUint24LE(bytes, payloadStart + 12).coerceAtLeast(20)
                    val innerBitstream = bytes.copyOfRange(payloadStart + 16, payloadStart + chunkSize)
                    val singleWebp = wrapAsSingleWebp(innerBitstream)
                    val bmp = BitmapFactory.decodeByteArray(singleWebp, 0, singleWebp.size)
                    if (bmp != null) {
                        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else {
                            bmp.copy(Bitmap.Config.ARGB_8888, true).also { bmp.recycle() }
                        }
                        frames += AnimatedFrameBitmap(argb, durationMs)
                    }
                }
                val nextOffsetLong = payloadStartLong + chunkSizeLong + (chunkSizeLong and 1L)
                if (nextOffsetLong > Int.MAX_VALUE.toLong()) break
                offset = nextOffsetLong.toInt()
            }
            return frames
        } catch (t: Throwable) {
            frames.forEach { it.bitmap.recycle() }
            throw t
        }
    }

    private fun wrapAsSingleWebp(innerChunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(12 + innerChunks.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val size = 4 + innerChunks.size
        out.write(size and 0xFF)
        out.write((size ushr 8) and 0xFF)
        out.write((size ushr 16) and 0xFF)
        out.write((size ushr 24) and 0xFF)
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(innerChunks)
        return out.toByteArray()
    }

    private fun readUint24LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }

    private fun readUint32LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
