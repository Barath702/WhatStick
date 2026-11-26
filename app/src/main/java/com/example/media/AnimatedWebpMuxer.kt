package com.example.media

import android.graphics.Bitmap
import android.os.Build
import com.example.whatsapp.WhatsAppValidator
import java.io.ByteArrayOutputStream
import kotlin.math.max

data class AnimatedFrameBitmap(
    val bitmap: Bitmap,
    val durationMs: Int
)

object AnimatedWebpMuxer {

    private const val STICKER_SIZE = 512

    /**
     * Encodes and muxes a sequence of 512x512 ARGB_8888 frames into a WhatsApp-compliant
     * animated WebP (<= 500 KB, <= 10s total, >= 8ms per frame, infinite loop).
     * Automatically adapts quality and, only if necessary, subsamples frames while preserving
     * exact total timeline duration.
     */
    fun encodeAnimatedWebp(
        frames: List<AnimatedFrameBitmap>,
        onProgress: (Float) -> Unit = {}
    ): Result<ByteArray> {
        if (frames.isEmpty()) {
            return Result.failure(IllegalArgumentException("No frames to encode."))
        }

        // Normalize durations to WhatsApp constraints: >= 16ms per frame, <= 10,000ms total
        val normalized = normalizeFrameTimings(frames)

        val qualityLevels = intArrayOf(80, 66, 52, 40, 30, 22)
        var workingFrames = normalized
        var lastBytes: ByteArray? = null

        for (pass in 0..2) {
            for ((qIdx, quality) in qualityLevels.withIndex()) {
                val encodedChunks = ArrayList<Pair<ByteArray, Int>>(workingFrames.size)
                for ((idx, frame) in workingFrames.withIndex()) {
                    val singleFrameWebp = compressSingleFrameWebp(frame.bitmap, quality)
                    val payloadChunks = extractBitstreamChunks(singleFrameWebp)
                    if (payloadChunks.isEmpty()) {
                        return Result.failure(IllegalStateException("Failed to extract WebP frame bitstream."))
                    }
                    encodedChunks.add(payloadChunks to frame.durationMs)
                    val progressBase = (pass * qualityLevels.size + qIdx).toFloat() / (3f * qualityLevels.size)
                    onProgress((progressBase + (idx + 1f) / workingFrames.size / (3f * qualityLevels.size)).coerceIn(0f, 0.98f))
                }

                val muxed = muxRiffAnimatedWebp(STICKER_SIZE, STICKER_SIZE, encodedChunks)
                lastBytes = muxed
                if (muxed.size <= WhatsAppValidator.MAX_ANIMATED_BYTES) {
                    val validationErr = WhatsAppValidator.validateStickerBytes(muxed, expectAnimated = true)
                    if (validationErr == null) {
                        onProgress(1f)
                        return Result.success(muxed)
                    }
                }
            }

            // Still > 500 KB: drop every second frame while adding dropped frame durations to kept frames
            // so total playback speed & duration stay identical!
            if (workingFrames.size <= 6) break
            workingFrames = subsamplePreservingDuration(workingFrames)
        }

        val finalBytes = lastBytes ?: return Result.failure(IllegalStateException("Encoding produced no bytes."))
        val err = WhatsAppValidator.validateStickerBytes(finalBytes, expectAnimated = true)
        return if (err == null) {
            onProgress(1f)
            Result.success(finalBytes)
        } else {
            Result.failure(IllegalStateException(err))
        }
    }

    internal fun normalizeFrameTimings(frames: List<AnimatedFrameBitmap>): List<AnimatedFrameBitmap> {
        val clamped = frames.map { frame ->
            frame.copy(durationMs = frame.durationMs.coerceAtLeast(16))
        }
        val total = clamped.sumOf { it.durationMs.toLong() }
        if (total <= WhatsAppValidator.MAX_ANIMATED_DURATION_MS) return clamped

        // Trim trailing frames cleanly if > 10s
        val result = ArrayList<AnimatedFrameBitmap>(clamped.size)
        var acc = 0L
        for (frame in clamped) {
            if (acc + frame.durationMs <= WhatsAppValidator.MAX_ANIMATED_DURATION_MS) {
                result += frame
                acc += frame.durationMs
            } else {
                val remaining = (WhatsAppValidator.MAX_ANIMATED_DURATION_MS - acc).toInt()
                if (remaining >= 16) {
                    result += frame.copy(durationMs = remaining)
                }
                break
            }
        }
        return result.ifEmpty { listOf(clamped.first().copy(durationMs = 100)) }
    }

    internal fun subsamplePreservingDuration(
        frames: List<AnimatedFrameBitmap>
    ): List<AnimatedFrameBitmap> {
        if (frames.size <= 2) return frames
        val result = ArrayList<AnimatedFrameBitmap>((frames.size + 1) / 2)
        var i = 0
        while (i < frames.size) {
            val current = frames[i]
            val extraDuration = if (i + 1 < frames.size) frames[i + 1].durationMs else 0
            result.add(current.copy(durationMs = current.durationMs + extraDuration))
            i += 2
        }
        return result
    }

    private fun compressSingleFrameWebp(bitmap: Bitmap, quality: Int): ByteArray {
        val scaled = if (bitmap.width == STICKER_SIZE && bitmap.height == STICKER_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, STICKER_SIZE, STICKER_SIZE, true)
        }
        return try {
            val baos = ByteArrayOutputStream()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, baos)
            } else {
                @Suppress("DEPRECATION")
                scaled.compress(Bitmap.CompressFormat.WEBP, quality, baos)
            }
            baos.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * Extracts raw ALPH, VP8 , and VP8L chunks from a single-frame RIFF WEBP byte array.
     */
    internal fun extractBitstreamChunks(singleWebpBytes: ByteArray): ByteArray {
        if (singleWebpBytes.size < 12) return ByteArray(0)
        val out = ByteArrayOutputStream()
        var offset = 12
        while (offset + 8 <= singleWebpBytes.size) {
            val fourCc = String(singleWebpBytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = readUint32LE(singleWebpBytes, offset + 4)
            val paddedSize = chunkSize + (chunkSize and 1)
            val totalChunkBytes = 8 + paddedSize
            if (chunkSize < 0 || offset + 8 + chunkSize > singleWebpBytes.size) break

            if (fourCc == "ALPH" || fourCc == "VP8 " || fourCc == "VP8L") {
                val end = minOf(singleWebpBytes.size, offset + totalChunkBytes)
                out.write(singleWebpBytes, offset, end - offset)
                if ((chunkSize and 1) == 1 && offset + 8 + chunkSize == singleWebpBytes.size) {
                    out.write(0)
                }
            }
            offset += totalChunkBytes
        }
        return out.toByteArray()
    }

    /**
     * Builds a valid RIFF WEBP animated container (VP8X + ANIM + ANMF frames).
     */
    internal fun muxRiffAnimatedWebp(
        width: Int,
        height: Int,
        frameChunks: List<Pair<ByteArray, Int>>
    ): ByteArray {
        val body = ByteArrayOutputStream()

        // 1. VP8X chunk (10-byte payload)
        body.write("VP8X".toByteArray(Charsets.US_ASCII))
        writeUint32LE(body, 10)
        // Flags: Animation (bit 1 = 0x02) | Alpha (bit 4 = 0x10) => 0x12
        body.write(0x12)
        body.write(0x00)
        body.write(0x00)
        body.write(0x00)
        writeUint24LE(body, width - 1)
        writeUint24LE(body, height - 1)

        // 2. ANIM chunk (6-byte payload)
        body.write("ANIM".toByteArray(Charsets.US_ASCII))
        writeUint32LE(body, 6)
        // Background color BGRA = transparent (0, 0, 0, 0)
        writeUint32LE(body, 0)
        // Loop count = 0 (infinite)
        writeUint16LE(body, 0)

        // 3. ANMF chunks
        for ((bitstream, durationMs) in frameChunks) {
            val payloadSize = 16 + bitstream.size
            body.write("ANMF".toByteArray(Charsets.US_ASCII))
            writeUint32LE(body, payloadSize)
            writeUint24LE(body, 0) // Frame X / 2
            writeUint24LE(body, 0) // Frame Y / 2
            writeUint24LE(body, width - 1)
            writeUint24LE(body, height - 1)
            writeUint24LE(body, max(8, durationMs))
            // Frame flags: Bit 0 = 1 (Dispose to background), Bit 1 = 1 (Do not blend) => 0x03
            body.write(0x03)
            body.write(bitstream)
            if ((payloadSize and 1) == 1) {
                body.write(0)
            }
        }

        val bodyBytes = body.toByteArray()
        val riff = ByteArrayOutputStream(12 + bodyBytes.size)
        riff.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeUint32LE(riff, 4 + bodyBytes.size)
        riff.write("WEBP".toByteArray(Charsets.US_ASCII))
        riff.write(bodyBytes)
        return riff.toByteArray()
    }

    private fun readUint32LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeUint16LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeUint24LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
    }

    private fun writeUint32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
