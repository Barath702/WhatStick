package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VideoMetadata(
    val durationMs: Long,
    val width: Int,
    val height: Int
)

data class AnimatedTransformConfig(
    val startMs: Long = 0L,
    val endMs: Long = 5000L,
    val speedMultiplier: Float = 1f,
    val cropCenterFill: Boolean = true,
    val zoom: Float = 1f,
    val stretchX: Float = 1f,
    val stretchY: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val textOverlay: TextOverlayItem? = null
)

object VideoFrameExtractor {

    private const val STICKER_SIZE = 512

    suspend fun inspectVideo(context: Context, uri: Uri): Result<VideoMetadata> =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                if (dur <= 0L || w <= 0 || h <= 0) {
                    Result.failure(IllegalArgumentException("Invalid or unsupported video stream."))
                } else {
                    Result.success(VideoMetadata(dur, w, h))
                }
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Could not open video codec: ${e.message}", e))
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }

    suspend fun extractPreviewThumbnails(
        context: Context,
        uri: Uri,
        durationMs: Long,
        count: Int = 8
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val thumbs = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(context, uri)
            val safeCount = count.coerceIn(2, 12)
            for (i in 0 until safeCount) {
                coroutineContext.ensureActive()
                val timeUs = (durationMs * i / max(1, safeCount - 1)) * 1000L
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        120,
                        120
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { raw ->
                            val scaled = Bitmap.createScaledBitmap(raw, 120, 120, true)
                            if (scaled !== raw) raw.recycle()
                            scaled
                        }
                }
                if (bmp != null) thumbs.add(bmp)
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        thumbs
    }

    /**
     * Extracts lightweight preview frames preserving natural aspect ratio (without baking crop/zoom/stretch)
     * so interactive zoom and stretch sliders update the live preview immediately without re-decoding.
     */
    suspend fun extractRawPreviewFrames(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        targetFps: Int = 6
    ): Result<List<AnimatedFrameBitmap>> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<AnimatedFrameBitmap>()
        try {
            retriever.setDataSource(context, uri)
            val rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (rawDuration <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("Video has zero duration."))
            }

            val clampedStart = startMs.coerceIn(0L, max(0L, rawDuration - 200L))
            val clampedEnd = endMs.coerceIn(clampedStart + 200L, rawDuration)
            val sourceSpanMs = clampedEnd - clampedStart
            val frameCount = ((sourceSpanMs / 1000f) * targetFps).toInt().coerceIn(4, 60)
            val frameDurationMs = (sourceSpanMs / frameCount).toInt().coerceAtLeast(25)

            for (i in 0 until frameCount) {
                coroutineContext.ensureActive()
                val fraction = if (frameCount <= 1) 0f else i.toFloat() / (frameCount - 1).toFloat()
                val sourceTimeMs = clampedStart + (sourceSpanMs * fraction).toLong()
                val timeUs = sourceTimeMs * 1000L

                val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        STICKER_SIZE,
                        STICKER_SIZE
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { raw ->
                        val maxDim = max(raw.width, raw.height)
                        if (maxDim > STICKER_SIZE) {
                            val s = STICKER_SIZE.toFloat() / maxDim.toFloat()
                            val sw = (raw.width * s).roundToInt().coerceAtLeast(1)
                            val sh = (raw.height * s).roundToInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(raw, sw, sh, true).also {
                                if (it !== raw) raw.recycle()
                            }
                        } else {
                            raw
                        }
                    }
                }
                if (decoded != null) {
                    frames += AnimatedFrameBitmap(decoded, frameDurationMs)
                }
            }

            if (frames.isEmpty()) {
                Result.failure(IllegalStateException("Could not decode preview frames from this video."))
            } else {
                Result.success(frames)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            frames.forEach { it.bitmap.recycle() }
            throw e
        } catch (e: Exception) {
            frames.forEach { it.bitmap.recycle() }
            Result.failure(IllegalStateException("Video preview failed: ${e.message ?: "Unsupported format"}", e))
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Streams video frames over [startMs..endMs] on a background thread, scaling and rendering
     * each frame onto a 512x512 canvas without holding raw high-res frames in memory.
     */
    suspend fun extractTransformedFrames(
        context: Context,
        uri: Uri,
        config: AnimatedTransformConfig,
        targetFps: Int = 12,
        onProgress: (Float) -> Unit = {}
    ): Result<List<AnimatedFrameBitmap>> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<AnimatedFrameBitmap>()
        try {
            retriever.setDataSource(context, uri)
            val rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (rawDuration <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("Video has zero duration."))
            }

            val startMs = config.startMs.coerceIn(0L, max(0L, rawDuration - 200L))
            val endMs = config.endMs.coerceIn(startMs + 200L, rawDuration)
            val sourceSpanMs = endMs - startMs
            val speed = config.speedMultiplier.coerceIn(0.5f, 2.5f)
            val effectiveOutputMs = (sourceSpanMs / speed).toLong().coerceIn(200L, 9_800L)

            val frameCount = ((effectiveOutputMs / 1000f) * targetFps).toInt().coerceIn(4, 100)
            val frameDurationMs = (effectiveOutputMs / frameCount).toInt().coerceAtLeast(25)

            for (i in 0 until frameCount) {
                coroutineContext.ensureActive()
                val fraction = if (frameCount <= 1) 0f else i.toFloat() / (frameCount - 1).toFloat()
                val sourceTimeMs = startMs + (sourceSpanMs * fraction).toLong()
                val timeUs = sourceTimeMs * 1000L

                val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        STICKER_SIZE,
                        STICKER_SIZE
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }

                if (decoded != null) {
                    val transformed = renderAnimatedFrame(
                        source = decoded,
                        config = config,
                        typefaceResolver = { style, bold, italic ->
                            StickerTypefaceResolver.resolve(context, style, bold, italic)
                        }
                    )
                    decoded.recycle()
                    frames += AnimatedFrameBitmap(transformed, frameDurationMs)
                }
                onProgress((i + 1f) / frameCount.toFloat() * 0.5f)
            }

            if (frames.isEmpty()) {
                Result.failure(IllegalStateException("Could not decode frames from this video codec."))
            } else {
                Result.success(frames)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            frames.forEach { it.bitmap.recycle() }
            throw e
        } catch (e: OutOfMemoryError) {
            frames.forEach { it.bitmap.recycle() }
            Result.failure(IllegalStateException("Insufficient memory to process video.", e))
        } catch (e: Exception) {
            frames.forEach { it.bitmap.recycle() }
            Result.failure(IllegalStateException("Video processing failed: ${e.message ?: "Unsupported format"}", e))
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun renderAnimatedFrame(
        source: Bitmap,
        config: AnimatedTransformConfig,
        typefaceResolver: (StickerFontStyle, Boolean, Boolean) -> android.graphics.Typeface
    ): Bitmap {
        val out = Bitmap.createBitmap(STICKER_SIZE, STICKER_SIZE, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
        }
        val canvas = Canvas(out)

        val scaleBase = if (config.cropCenterFill) {
            max(STICKER_SIZE.toFloat() / source.width, STICKER_SIZE.toFloat() / source.height)
        } else {
            min(STICKER_SIZE.toFloat() / source.width, STICKER_SIZE.toFloat() / source.height)
        }
        val zoom = config.zoom.coerceIn(0.5f, 4f)
        val finalScaleX = scaleBase * zoom * config.stretchX.coerceIn(0.25f, 2.5f)
        val finalScaleY = scaleBase * zoom * config.stretchY.coerceIn(0.25f, 2.5f)

        val matrix = Matrix().apply {
            postTranslate(-source.width / 2f, -source.height / 2f)
            postScale(finalScaleX, finalScaleY)
            postTranslate(
                STICKER_SIZE / 2f + config.offsetX * STICKER_SIZE,
                STICKER_SIZE / 2f + config.offsetY * STICKER_SIZE
            )
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        config.textOverlay?.let { textItem ->
            if (textItem.text.isNotBlank()) {
                StaticStickerRenderer.drawTextOverlay(canvas, textItem, typefaceResolver, STICKER_SIZE)
            }
        }
        out.prepareToDraw()
        return out
    }
}
