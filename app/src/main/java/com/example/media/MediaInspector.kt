package com.example.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.whatsapp.WhatsAppValidator

enum class DetectedMediaType {
    STATIC_IMAGE,
    ANIMATED_GIF,
    ANIMATED_WEBP,
    VIDEO,
    UNSUPPORTED
}

data class InspectedMedia(
    val uri: Uri,
    val type: DetectedMediaType,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long
)

object MediaInspector {

    fun inspect(context: Context, uri: Uri): Result<InspectedMedia> {
        return try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)?.lowercase()
            var displayName = "media"
            var sizeBytes = -1L

            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                }
            }

            // Read header bytes safely (max 4 KB) to detect real file signature
            val header = resolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(4096)
                val read = stream.read(buf)
                if (read > 0) buf.copyOf(read) else ByteArray(0)
            } ?: return Result.failure(IllegalArgumentException("Cannot open selected media file."))

            if (header.size < 4) {
                return Result.failure(IllegalArgumentException("Selected file is empty or corrupted."))
            }

            val detected = detectTypeFromHeaderAndMime(header, mime, displayName)
            if (detected == DetectedMediaType.UNSUPPORTED) {
                return Result.failure(
                    IllegalArgumentException("Unsupported format (${mime ?: displayName}). Use JPG, PNG, WebP, GIF, MP4, or WebM.")
                )
            }

            Result.success(
                InspectedMedia(
                    uri = uri,
                    type = detected,
                    displayName = displayName,
                    mimeType = mime,
                    sizeBytes = sizeBytes
                )
            )
        } catch (e: SecurityException) {
            Result.failure(IllegalStateException("Permission denied while reading selected file.", e))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to inspect media: ${e.message ?: "Unknown error"}", e))
        }
    }

    internal fun detectTypeFromHeaderAndMime(
        header: ByteArray,
        mime: String?,
        displayName: String
    ): DetectedMediaType {
        // Check GIF signature: GIF87a or GIF89a
        if (header.size >= 6 &&
            header[0] == 'G'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == '8'.code.toByte()
        ) {
            return DetectedMediaType.ANIMATED_GIF
        }

        // Check WebP RIFF signature and whether ANIM/VP8X animation flag is present
        if (header.size >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()
        ) {
            val webpInfo = WhatsAppValidator.inspectWebp(header)
            val hasAnimFlag = header.size >= 21 &&
                header[12] == 'V'.code.toByte() &&
                header[13] == 'P'.code.toByte() &&
                header[14] == '8'.code.toByte() &&
                header[15] == 'X'.code.toByte() &&
                ((header[20].toInt() and 0x02) != 0)
            return if (webpInfo.isAnimated || hasAnimFlag) {
                DetectedMediaType.ANIMATED_WEBP
            } else {
                DetectedMediaType.STATIC_IMAGE
            }
        }

        // PNG or JPEG or BMP or HEIF
        if (header.size >= 4 &&
            ((header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()) ||
                (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()))
        ) {
            return DetectedMediaType.STATIC_IMAGE
        }

        // WebM / Matroska EBML header: 1A 45 DF A3
        if (header.size >= 4 &&
            header[0] == 0x1A.toByte() &&
            header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() &&
            header[3] == 0xA3.toByte()
        ) {
            return DetectedMediaType.VIDEO
        }

        // MP4 / ISO BMFF: 'ftyp' at offset 4
        if (header.size >= 8 &&
            header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()
        ) {
            // HEIC/HEIF uses ftyp heic/mif1 -> static image
            if (header.size >= 12) {
                val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
                if (brand in setOf("heic", "heix", "mif1", "msf1", "avif")) {
                    return DetectedMediaType.STATIC_IMAGE
                }
            }
            return DetectedMediaType.VIDEO
        }

        if (mime != null) {
            return when {
                mime == "image/gif" -> DetectedMediaType.ANIMATED_GIF
                mime.startsWith("video/") -> DetectedMediaType.VIDEO
                mime.startsWith("image/") -> DetectedMediaType.STATIC_IMAGE
                else -> DetectedMediaType.UNSUPPORTED
            }
        }

        val lowerName = displayName.lowercase()
        return when {
            lowerName.endsWith(".gif") -> DetectedMediaType.ANIMATED_GIF
            lowerName.endsWith(".mp4") || lowerName.endsWith(".webm") ||
                lowerName.endsWith(".mkv") || lowerName.endsWith(".mov") -> DetectedMediaType.VIDEO
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".webp") -> DetectedMediaType.STATIC_IMAGE
            else -> DetectedMediaType.UNSUPPORTED
        }
    }
}
