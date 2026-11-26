package com.example.whatsapp

import android.graphics.BitmapFactory
import com.example.data.local.StickerFileStorage
import com.example.data.model.StickerPackWithStickers
import java.io.File

data class WebpInfo(
    val isValidWebp: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val isAnimated: Boolean = false,
    val hasAlpha: Boolean = false,
    val totalDurationMs: Long = 0L,
    val minFrameDurationMs: Int = 0,
    val frameCount: Int = 0,
    val error: String? = null
)

data class PackValidationResult(
    val isValid: Boolean,
    val issues: List<String>,
    val stickerCount: Int,
    val minStickers: Int = 3,
    val maxStickers: Int = 30
)

object WhatsAppValidator {
    const val REQUIRED_STICKER_DIMENSION = 512
    const val MAX_STATIC_BYTES = 100 * 1024 // 100 KB
    const val MAX_ANIMATED_BYTES = 500 * 1024 // 500 KB
    const val MAX_TRAY_BYTES = 50 * 1024 // 50 KB
    const val REQUIRED_TRAY_DIMENSION = 96
    const val MIN_STICKERS_PER_PACK = 3
    const val MAX_STICKERS_PER_PACK = 30
    const val MAX_ANIMATED_DURATION_MS = 10_000L
    const val MIN_FRAME_DURATION_MS = 8

    fun inspectWebp(bytes: ByteArray): WebpInfo {
        if (bytes.size < 20) {
            return WebpInfo(false, error = "File is too small to be a valid WebP.")
        }
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte() ||
            bytes[8] != 'W'.code.toByte() || bytes[9] != 'E'.code.toByte() ||
            bytes[10] != 'B'.code.toByte() || bytes[11] != 'P'.code.toByte()
        ) {
            return WebpInfo(false, error = "Not a RIFF/WEBP container.")
        }

        var offset = 12
        var width = 0
        var height = 0
        var isAnimated = false
        var hasAlpha = false
        var hasAnimChunk = false
        var frameCount = 0
        var totalDurationMs = 0L
        var minFrameDurationMs = Int.MAX_VALUE

        while (offset + 8 <= bytes.size) {
            val fourCc = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSizeLong = readUint32LE(bytes, offset + 4)
            val payloadStartLong = offset.toLong() + 8L
            if (payloadStartLong + chunkSizeLong > bytes.size.toLong()) {
                return WebpInfo(false, error = "Truncated WebP chunk '$fourCc'.")
            }
            val chunkSize = chunkSizeLong.toInt()
            val payloadStart = payloadStartLong.toInt()

            when (fourCc) {
                "VP8X" -> {
                    if (chunkSize < 10) return WebpInfo(false, error = "Invalid VP8X chunk.")
                    val flags = bytes[payloadStart].toInt() and 0xFF
                    isAnimated = (flags and 0x02) != 0
                    hasAlpha = (flags and 0x10) != 0
                    width = readUint24LE(bytes, payloadStart + 4) + 1
                    height = readUint24LE(bytes, payloadStart + 7) + 1
                }
                "VP8L" -> {
                    if (width == 0 && chunkSize >= 5 && bytes[payloadStart] == 0x2F.toByte()) {
                        val b1 = bytes[payloadStart + 1].toInt() and 0xFF
                        val b2 = bytes[payloadStart + 2].toInt() and 0xFF
                        val b3 = bytes[payloadStart + 3].toInt() and 0xFF
                        val b4 = bytes[payloadStart + 4].toInt() and 0xFF
                        val bits = b1 or (b2 shl 8) or (b3 shl 16) or (b4 shl 24)
                        width = (bits and 0x3FFF) + 1
                        height = ((bits shr 14) and 0x3FFF) + 1
                        hasAlpha = ((bits shr 28) and 0x01) != 0
                    }
                }
                "VP8 " -> {
                    if (width == 0 && chunkSize >= 10 &&
                        bytes[payloadStart + 3] == 0x9D.toByte() &&
                        bytes[payloadStart + 4] == 0x01.toByte() &&
                        bytes[payloadStart + 5] == 0x2A.toByte()
                    ) {
                        val wRaw = (bytes[payloadStart + 6].toInt() and 0xFF) or
                            ((bytes[payloadStart + 7].toInt() and 0xFF) shl 8)
                        val hRaw = (bytes[payloadStart + 8].toInt() and 0xFF) or
                            ((bytes[payloadStart + 9].toInt() and 0xFF) shl 8)
                        width = wRaw and 0x3FFF
                        height = hRaw and 0x3FFF
                    }
                }
                "ALPH" -> hasAlpha = true
                "ANIM" -> hasAnimChunk = true
                "ANMF" -> {
                    if (chunkSize < 16) return WebpInfo(false, error = "Invalid ANMF frame chunk.")
                    val duration = readUint24LE(bytes, payloadStart + 12)
                    frameCount++
                    totalDurationMs += duration
                    if (duration < minFrameDurationMs) {
                        minFrameDurationMs = duration
                    }
                }
            }

            val nextOffsetLong = payloadStartLong + chunkSizeLong + (chunkSizeLong and 1L)
            if (nextOffsetLong > Int.MAX_VALUE.toLong()) break
            offset = nextOffsetLong.toInt()
        }

        val effectiveAnimated = isAnimated && hasAnimChunk && frameCount > 0
        return WebpInfo(
            isValidWebp = width > 0 && height > 0,
            width = width,
            height = height,
            isAnimated = effectiveAnimated,
            hasAlpha = hasAlpha,
            totalDurationMs = totalDurationMs,
            minFrameDurationMs = if (frameCount > 0) minFrameDurationMs else 0,
            frameCount = frameCount
        )
    }

    fun validateStickerBytes(bytes: ByteArray, expectAnimated: Boolean): String? {
        val info = inspectWebp(bytes)
        if (!info.isValidWebp) return info.error ?: "Invalid WebP image."
        if (info.width != REQUIRED_STICKER_DIMENSION || info.height != REQUIRED_STICKER_DIMENSION) {
            return "Sticker must be 512×512 px (got ${info.width}×${info.height})."
        }
        if (expectAnimated) {
            if (!info.isAnimated) return "Expected animated WebP for animated sticker pack."
            if (bytes.size > MAX_ANIMATED_BYTES) {
                return "Animated sticker exceeds 500 KB limit (${bytes.size / 1024} KB)."
            }
            if (info.totalDurationMs > MAX_ANIMATED_DURATION_MS) {
                return "Animation exceeds 10 second limit (${info.totalDurationMs} ms)."
            }
            if (info.minFrameDurationMs < MIN_FRAME_DURATION_MS) {
                return "Frame duration (${info.minFrameDurationMs} ms) is below WhatsApp's 8 ms minimum."
            }
        } else {
            if (info.isAnimated) return "Static packs cannot contain animated stickers."
            if (bytes.size > MAX_STATIC_BYTES) {
                return "Static sticker exceeds 100 KB limit (${bytes.size / 1024} KB)."
            }
        }
        return null
    }

    fun validatePack(
        packWithStickers: StickerPackWithStickers,
        storage: StickerFileStorage
    ): PackValidationResult {
        val issues = mutableListOf<String>()
        val pack = packWithStickers.pack
        val stickers = packWithStickers.sortedStickers

        if (pack.name.isBlank() || pack.name.length > 128) {
            issues += "Pack name must be 1–128 characters."
        }
        if (pack.publisher.isBlank() || pack.publisher.length > 128) {
            issues += "Publisher name must be 1–128 characters."
        }
        if (stickers.size < MIN_STICKERS_PER_PACK) {
            val remaining = MIN_STICKERS_PER_PACK - stickers.size
            issues += "Add $remaining more sticker${if (remaining == 1) "" else "s"} (WhatsApp requires at least 3)."
        }
        if (stickers.size > MAX_STICKERS_PER_PACK) {
            issues += "WhatsApp supports at most 30 stickers per pack."
        }

        val trayFile = storage.getAssetFile(pack.id, pack.trayIconFileName)
        if (!trayFile.exists()) {
            issues += "Pack tray icon is missing."
        } else {
            if (trayFile.length() > MAX_TRAY_BYTES) {
                issues += "Tray icon exceeds 50 KB."
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(trayFile.absolutePath, opts)
            if (opts.outWidth != REQUIRED_TRAY_DIMENSION || opts.outHeight != REQUIRED_TRAY_DIMENSION) {
                issues += "Tray icon must be 96×96 px (is ${opts.outWidth}×${opts.outHeight})."
            }
        }

        stickers.forEachIndexed { index, sticker ->
            val file: File = storage.getAssetFile(pack.id, sticker.fileName)
            if (!file.exists()) {
                issues += "Sticker #${index + 1} file is missing."
            } else if (file.length() > MAX_ANIMATED_BYTES * 4L) {
                issues += "Sticker #${index + 1}: File exceeds maximum allowed sticker size (${file.length() / 1024} KB)."
            } else {
                val err = validateStickerBytes(file.readBytes(), pack.isAnimated)
                if (err != null) {
                    issues += "Sticker #${index + 1}: $err"
                }
            }
        }

        return PackValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            stickerCount = stickers.size
        )
    }

    private fun readUint24LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }

    private fun readUint32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
