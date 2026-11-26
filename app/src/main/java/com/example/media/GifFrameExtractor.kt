package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

/**
 * Pure-Kotlin GIF87a/GIF89a decoder extracting exact per-frame ARGB bitmaps and millisecond delays
 * without relying on frame-rate guesswork or loading excessive frames into memory.
 */
object GifFrameExtractor {

    private const val MAX_DECODED_GIF_FRAMES = 180

    suspend fun extractFrames(
        context: Context,
        uri: Uri,
        maxSourceSide: Int = 512
    ): Result<List<AnimatedFrameBitmap>> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                readBoundedBytes(stream, 32 * 1024 * 1024)
            } ?: return@withContext Result.failure(IllegalArgumentException("Cannot open GIF file."))

            val decoder = PureGifDecoder(bytes)
            val frames = decoder.decode(maxSourceSide, MAX_DECODED_GIF_FRAMES)
            if (frames.isEmpty()) {
                Result.failure(IllegalArgumentException("GIF contains no decodable frames."))
            } else {
                Result.success(frames)
            }
        } catch (e: OutOfMemoryError) {
            Result.failure(IllegalStateException("GIF is too large to decode safely.", e))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to decode GIF: ${e.message ?: "Invalid GIF"}", e))
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
                throw IllegalStateException("GIF exceeds ${maxBytes / (1024 * 1024)} MB input limit.")
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private class PureGifDecoder(private val data: ByteArray) {
        private var pos = 0
        private var logicalW = 0
        private var logicalH = 0
        private var gctFlag = false
        private var gctSize = 0
        private var bgIndex = 0
        private var gct = IntArray(0)

        private var gceDisposal = 0
        private var gceTransparency = false
        private var gceTransparentIdx = 0
        private var gceDelayMs = 100

        fun decode(maxSide: Int, maxFrames: Int): List<AnimatedFrameBitmap> {
            if (data.size < 13) return emptyList()
            val sig = String(data, 0, 6, Charsets.US_ASCII)
            if (sig != "GIF87a" && sig != "GIF89a") return emptyList()
            pos = 6

            logicalW = readU16()
            logicalH = readU16()
            val totalLogicalPixels = logicalW.toLong() * logicalH.toLong()
            if (logicalW <= 0 || logicalH <= 0 || totalLogicalPixels > 16_777_216L) return emptyList()
            val packed = readU8()
            gctFlag = (packed and 0x80) != 0
            gctSize = 2 shl (packed and 0x07)
            bgIndex = readU8()
            readU8() // pixel aspect ratio

            if (gctFlag) {
                gct = readColorTable(gctSize)
            }

            val scale = min(1f, maxSide.toFloat() / maxOf(logicalW, logicalH).toFloat())
            val outW = (logicalW * scale).toInt().coerceAtLeast(1)
            val outH = (logicalH * scale).toInt().coerceAtLeast(1)

            val canvasPixels = IntArray(logicalW * logicalH)
            var previousPixels = IntArray(logicalW * logicalH)
            val frames = mutableListOf<AnimatedFrameBitmap>()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            try {
                while (pos < data.size && frames.size < maxFrames) {
                when (val block = readU8()) {
                    0x2C -> { // Image Descriptor
                        val ix = readU16()
                        val iy = readU16()
                        val iw = readU16()
                        val ih = readU16()
                        val ipacked = readU8()
                        val lctFlag = (ipacked and 0x80) != 0
                        val interlace = (ipacked and 0x40) != 0
                        val lctSize = 2 shl (ipacked and 0x07)
                        val activeTable = if (lctFlag) readColorTable(lctSize) else gct
                        val framePixelCount = iw.toLong() * ih.toLong()
                        if (iw <= 0 || ih <= 0 || framePixelCount > 16_777_216L) {
                            skipSubBlocks()
                            resetGce()
                            continue
                        }

                        if (gceDisposal == 3) {
                            previousPixels = canvasPixels.copyOf()
                        }

                        val indices = decodeLzwPixels(iw * ih)
                        if (activeTable.isNotEmpty()) {
                            var pass = 0
                            var passStart = 0
                            var passInc = 8
                            var row = 0
                            for (srcRow in 0 until ih) {
                                val destRowOffset = if (interlace) {
                                    if (row >= ih) {
                                        pass++
                                        when (pass) {
                                            1 -> { passStart = 4; passInc = 8 }
                                            2 -> { passStart = 2; passInc = 4 }
                                            3 -> { passStart = 1; passInc = 2 }
                                        }
                                        row = passStart
                                    }
                                    val r = row
                                    row += passInc
                                    r
                                } else {
                                    srcRow
                                }

                                val dy = iy + destRowOffset
                                if (dy !in 0 until logicalH) continue
                                for (srcCol in 0 until iw) {
                                    val dx = ix + srcCol
                                    if (dx !in 0 until logicalW) continue
                                    val colorIdx = indices[srcRow * iw + srcCol].toInt() and 0xFF
                                    if (gceTransparency && colorIdx == gceTransparentIdx) continue
                                    if (colorIdx < activeTable.size) {
                                        canvasPixels[dy * logicalW + dx] = activeTable[colorIdx]
                                    }
                                }
                            }
                        }

                        // Snapshot current canvas to scaled output bitmap
                        val rawFrame = Bitmap.createBitmap(canvasPixels, logicalW, logicalH, Bitmap.Config.ARGB_8888)
                        val scaledFrame = if (outW == logicalW && outH == logicalH) {
                            rawFrame
                        } else {
                            val dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                            Canvas(dst).drawBitmap(rawFrame, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), paint)
                            rawFrame.recycle()
                            dst
                        }
                        frames += AnimatedFrameBitmap(scaledFrame, gceDelayMs.coerceAtLeast(20))

                        // Handle disposal after rendering frame
                        when (gceDisposal) {
                            2 -> { // Restore to background (transparent)
                                val fill = if (gceTransparency) Color.TRANSPARENT
                                else if (gctFlag && bgIndex < gct.size) gct[bgIndex] else Color.TRANSPARENT
                                for (y in iy until min(logicalH, iy + ih)) {
                                    for (x in ix until min(logicalW, ix + iw)) {
                                        canvasPixels[y * logicalW + x] = fill
                                    }
                                }
                            }
                            3 -> { // Restore to previous
                                previousPixels.copyInto(canvasPixels)
                            }
                        }
                        resetGce()
                    }
                    0x21 -> { // Extension
                        val label = readU8()
                        if (label == 0xF9) { // Graphic Control Extension
                            readU8() // block size (4)
                            val gcePacked = readU8()
                            gceDisposal = (gcePacked ushr 2) and 0x07
                            gceTransparency = (gcePacked and 0x01) != 0
                            val delayCs = readU16()
                            gceDelayMs = if (delayCs <= 1) 100 else delayCs * 10
                            gceTransparentIdx = readU8()
                            readU8() // terminator
                        } else {
                            skipSubBlocks()
                        }
                    }
                    0x3B -> break // Trailer
                    else -> break
                }
            }
                return frames
            } catch (t: Throwable) {
                frames.forEach { it.bitmap.recycle() }
                throw t
            }
        }

        private fun resetGce() {
            gceDisposal = 0
            gceTransparency = false
            gceTransparentIdx = 0
            gceDelayMs = 100
        }

        private fun readColorTable(count: Int): IntArray {
            val table = IntArray(count)
            for (i in 0 until count) {
                if (pos + 2 >= data.size) break
                val r = data[pos++].toInt() and 0xFF
                val g = data[pos++].toInt() and 0xFF
                val b = data[pos++].toInt() and 0xFF
                table[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return table
        }

        private fun skipSubBlocks() {
            while (pos < data.size) {
                val len = readU8()
                if (len == 0) break
                pos = (pos + len).coerceAtMost(data.size)
            }
        }

        private fun decodeLzwPixels(pixelCount: Int): ByteArray {
            val out = ByteArray(pixelCount)
            if (pos >= data.size) return out
            val minCodeSize = readU8().coerceIn(2, 11)
            val clearCode = 1 shl minCodeSize
            val endCode = clearCode + 1

            val prefix = IntArray(4096)
            val suffix = ByteArray(4096)
            val stack = ByteArray(4097)

            var codeSize = minCodeSize + 1
            var codeMask = (1 shl codeSize) - 1
            var available = clearCode + 2
            var oldCode = -1
            var firstChar = 0

            for (c in 0 until clearCode) {
                prefix[c] = 0
                suffix[c] = c.toByte()
            }

            var blockSize = 0
            var blockPos = 0
            var blockBuf = ByteArray(0)
            var datum = 0
            var bits = 0
            var outIdx = 0
            var top = 0

            while (outIdx < pixelCount) {
                if (top == 0) {
                    while (bits < codeSize) {
                        if (blockPos >= blockSize) {
                            if (pos >= data.size) return out
                            blockSize = readU8()
                            if (blockSize == 0) return out
                            val end = (pos + blockSize).coerceAtMost(data.size)
                            blockBuf = data.copyOfRange(pos, end)
                            pos = end
                            blockPos = 0
                        }
                        if (blockPos >= blockBuf.size) return out
                        datum += (blockBuf[blockPos++].toInt() and 0xFF) shl bits
                        bits += 8
                    }
                    val code = datum and codeMask
                    datum = datum ushr codeSize
                    bits -= codeSize

                    if (code > available || code == endCode) break
                    if (code == clearCode) {
                        codeSize = minCodeSize + 1
                        codeMask = (1 shl codeSize) - 1
                        available = clearCode + 2
                        oldCode = -1
                        continue
                    }
                    if (oldCode == -1) {
                        out[outIdx++] = suffix[code]
                        oldCode = code
                        firstChar = code
                        continue
                    }
                    val inCode = code
                    var cur = code
                    if (cur == available) {
                        stack[top++] = firstChar.toByte()
                        cur = oldCode
                    }
                    while (cur > clearCode && cur in 0 until 4096 && top < stack.size - 1) {
                        stack[top++] = suffix[cur]
                        cur = prefix[cur]
                    }
                    if (cur !in 0 until 4096) break
                    firstChar = suffix[cur].toInt() and 0xFF
                    stack[top++] = firstChar.toByte()

                    if (available < 4096) {
                        prefix[available] = oldCode
                        suffix[available] = firstChar.toByte()
                        available++
                        if ((available and codeMask) == 0 && available < 4096) {
                            codeSize++
                            codeMask = (1 shl codeSize) - 1
                        }
                    }
                    oldCode = inCode
                }
                top--
                out[outIdx++] = stack[top]
            }
            skipSubBlocks()
            return out
        }

        private fun readU8(): Int = if (pos < data.size) (data[pos++].toInt() and 0xFF) else 0
        private fun readU16(): Int = readU8() or (readU8() shl 8)
    }
}
