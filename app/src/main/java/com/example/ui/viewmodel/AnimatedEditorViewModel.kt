package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.WhatStickApp
import com.example.data.model.StickerEntity
import com.example.media.AnimatedFrameBitmap
import com.example.media.AnimatedTransformConfig
import com.example.media.AnimatedWebpFrameExtractor
import com.example.media.AnimatedWebpMuxer
import com.example.media.DetectedMediaType
import com.example.media.GifFrameExtractor
import com.example.media.StickerTypefaceResolver
import com.example.media.TextOverlayItem
import com.example.media.VideoFrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class AnimatedEditorUiState(
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val mediaType: DetectedMediaType = DetectedMediaType.ANIMATED_GIF,
    val sourceDurationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 3000L,
    val speedMultiplier: Float = 1f,
    val cropCenterFill: Boolean = true,
    val zoom: Float = 1f,
    val stretchX: Float = 1f,
    val stretchY: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val textOverlay: TextOverlayItem? = null,
    val timelineThumbnails: List<Bitmap> = emptyList(),
    val previewFrames: List<AnimatedFrameBitmap> = emptyList(),
    val isPlaying: Boolean = true,
    val errorMessage: String? = null
) {
    val effectiveOutputDurationMs: Long
        get() = ((trimEndMs - trimStartMs).coerceAtLeast(100L) / speedMultiplier).toLong()
}

class AnimatedEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WhatStickApp
    private val repository = app.repository

    private val _uiState = MutableStateFlow(AnimatedEditorUiState())
    val uiState: StateFlow<AnimatedEditorUiState> = _uiState.asStateFlow()

    private var currentUri: Uri? = null
    private var rawDecodedFrames: List<AnimatedFrameBitmap> = emptyList()
    private var videoPreviewJob: Job? = null

    fun loadMedia(uri: Uri, type: DetectedMediaType) {
        currentUri = uri
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, mediaType = type, errorMessage = null)
            }
            when (type) {
                DetectedMediaType.ANIMATED_GIF -> loadDecodedSequence(
                    GifFrameExtractor.extractFrames(app, uri),
                    type
                )
                DetectedMediaType.ANIMATED_WEBP -> loadDecodedSequence(
                    AnimatedWebpFrameExtractor.extractFrames(app, uri),
                    type
                )
                DetectedMediaType.VIDEO -> loadVideo(uri)
                else -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Unsupported animated format.")
                }
            }
        }
    }

    private fun loadDecodedSequence(
        res: Result<List<AnimatedFrameBitmap>>,
        type: DetectedMediaType
    ) {
        res.onSuccess { frames ->
            rawDecodedFrames.forEach { it.bitmap.recycle() }
            rawDecodedFrames = frames
            val totalMs = frames.sumOf { it.durationMs.toLong() }.coerceAtLeast(200L)
            val initialEnd = min(totalMs, 9_500L)
            val thumbStep = max(1, frames.size / 8)
            val thumbs = frames.filterIndexed { i, _ -> i % thumbStep == 0 }.take(8).map { it.bitmap }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    mediaType = type,
                    sourceDurationMs = totalMs,
                    trimStartMs = 0L,
                    trimEndMs = initialEnd,
                    timelineThumbnails = thumbs,
                    previewFrames = frames,
                    errorMessage = null
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(isLoading = false, errorMessage = err.message ?: "Failed to decode animation.")
            }
        }
    }

    private suspend fun loadVideo(uri: Uri) {
        val metaRes = VideoFrameExtractor.inspectVideo(app, uri)
        metaRes.onSuccess { meta ->
            val initialEnd = min(meta.durationMs, 6_000L)
            val thumbs = VideoFrameExtractor.extractPreviewThumbnails(app, uri, meta.durationMs, count = 8)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    mediaType = DetectedMediaType.VIDEO,
                    sourceDurationMs = meta.durationMs,
                    trimStartMs = 0L,
                    trimEndMs = initialEnd,
                    timelineThumbnails = thumbs,
                    previewFrames = emptyList(),
                    errorMessage = null
                )
            }
            refreshVideoPreviewFrames(debounceMs = 0L)
        }.onFailure { err ->
            _uiState.update {
                it.copy(isLoading = false, errorMessage = err.message ?: "Failed to open video.")
            }
        }
    }

    fun updateTrim(startMs: Long, endMs: Long) {
        val state = _uiState.value
        val maxSpanForSpeed = (9_800L * state.speedMultiplier).toLong()
        val clampedStart = startMs.coerceIn(0L, max(0L, state.sourceDurationMs - 200L))
        val clampedEnd = endMs.coerceIn(
            clampedStart + 200L,
            min(state.sourceDurationMs, clampedStart + maxSpanForSpeed)
        )
        _uiState.update { it.copy(trimStartMs = clampedStart, trimEndMs = clampedEnd) }
        if (state.mediaType == DetectedMediaType.VIDEO) {
            refreshVideoPreviewFrames(debounceMs = 90L)
        }
    }

    fun setSpeed(speed: Float) {
        val state = _uiState.value
        val newSpeed = speed.coerceIn(0.5f, 2.0f)
        val maxSpan = (9_800L * newSpeed).toLong()
        val clampedEnd = min(state.trimEndMs, state.trimStartMs + maxSpan)
            .coerceAtLeast(min(state.sourceDurationMs, state.trimStartMs + 200L))
        _uiState.update { it.copy(speedMultiplier = newSpeed, trimEndMs = clampedEnd) }
    }

    fun setCropCenterFill(fill: Boolean) {
        _uiState.update { it.copy(cropCenterFill = fill) }
    }

    fun updateZoom(zoom: Float) {
        _uiState.update { it.copy(zoom = zoom.coerceIn(0.6f, 4.0f)) }
    }

    fun updateStretchX(stretchX: Float) {
        _uiState.update { it.copy(stretchX = stretchX.coerceIn(0.25f, 2.5f)) }
    }

    fun updateStretchY(stretchY: Float) {
        _uiState.update { it.copy(stretchY = stretchY.coerceIn(0.25f, 2.5f)) }
    }

    fun resetStretch() {
        _uiState.update { it.copy(stretchX = 1f, stretchY = 1f) }
    }

    fun applyPinchZoomAndPan(zoomDelta: Float, panNormX: Float, panNormY: Float) {
        _uiState.update {
            it.copy(
                zoom = (it.zoom * zoomDelta).coerceIn(0.6f, 4.0f),
                offsetX = (it.offsetX + panNormX).coerceIn(-0.6f, 0.6f),
                offsetY = (it.offsetY + panNormY).coerceIn(-0.6f, 0.6f)
            )
        }
    }

    fun toggleDoubleTapZoom() {
        _uiState.update {
            if (it.zoom > 1.15f) {
                it.copy(zoom = 1f, offsetX = 0f, offsetY = 0f)
            } else {
                it.copy(zoom = 2f)
            }
        }
    }

    fun updateOffset(dx: Float, dy: Float) {
        _uiState.update {
            it.copy(
                offsetX = (it.offsetX + dx).coerceIn(-0.6f, 0.6f),
                offsetY = (it.offsetY + dy).coerceIn(-0.6f, 0.6f)
            )
        }
    }

    fun resetTransform() {
        _uiState.update {
            it.copy(
                zoom = 1f,
                stretchX = 1f,
                stretchY = 1f,
                offsetX = 0f,
                offsetY = 0f
            )
        }
    }

    fun setTextOverlay(item: TextOverlayItem?) {
        _uiState.update { it.copy(textOverlay = item) }
    }

    fun moveOrRotateTextOverlay(
        normX: Float,
        normY: Float,
        rotationDeg: Float,
        fontSizeSp: Float
    ) {
        val normAngle = ((((rotationDeg + 180f) % 360f) + 360f) % 360f) - 180f
        _uiState.update { state ->
            val curr = state.textOverlay ?: return@update state
            state.copy(
                textOverlay = curr.copy(
                    normX = normX.coerceIn(0.05f, 0.95f),
                    normY = normY.coerceIn(0.05f, 0.95f),
                    rotationDeg = normAngle,
                    fontSizeSp = fontSizeSp.coerceIn(14f, 110f)
                )
            )
        }
    }

    fun togglePlaying() {
        _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    private fun refreshVideoPreviewFrames(debounceMs: Long = 0L) {
        val uri = currentUri ?: return
        val state = _uiState.value
        if (state.mediaType != DetectedMediaType.VIDEO) return
        videoPreviewJob?.cancel()
        videoPreviewJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val latest = _uiState.value
            val preview = VideoFrameExtractor.extractRawPreviewFrames(
                context = app,
                uri = uri,
                startMs = latest.trimStartMs,
                endMs = latest.trimEndMs,
                targetFps = 6
            )
            preview.onSuccess { frames ->
                _uiState.update { it.copy(previewFrames = frames) }
            }
        }
    }

    suspend fun exportAndSaveToPack(
        packId: String,
        emojis: String
    ): Result<StickerEntity> {
        val uri = currentUri
            ?: return Result.failure(IllegalStateException("No animated media loaded."))
        val state = _uiState.value
        _uiState.update { it.copy(isExporting = true, exportProgress = 0.05f, errorMessage = null) }

        val config = AnimatedTransformConfig(
            startMs = state.trimStartMs,
            endMs = state.trimEndMs,
            speedMultiplier = state.speedMultiplier,
            cropCenterFill = state.cropCenterFill,
            zoom = state.zoom,
            stretchX = state.stretchX,
            stretchY = state.stretchY,
            offsetX = state.offsetX,
            offsetY = state.offsetY,
            textOverlay = state.textOverlay
        )

        val framesResult: Result<List<AnimatedFrameBitmap>> = if (state.mediaType == DetectedMediaType.VIDEO) {
            VideoFrameExtractor.extractTransformedFrames(
                context = app,
                uri = uri,
                config = config,
                targetFps = 12,
                onProgress = { p -> _uiState.update { it.copy(exportProgress = p) } }
            )
        } else {
            withContext(Dispatchers.Default) {
                renderTrimmedSequenceFrames(rawDecodedFrames, config)
            }
        }

        val webpResult = framesResult.fold(
            onSuccess = { renderedFrames ->
                try {
                    withContext(Dispatchers.Default) {
                        AnimatedWebpMuxer.encodeAnimatedWebp(renderedFrames) { p ->
                            _uiState.update { it.copy(exportProgress = 0.5f + p * 0.48f) }
                        }
                    }
                } finally {
                    renderedFrames.forEach { it.bitmap.recycle() }
                }
            },
            onFailure = { Result.failure(it) }
        )

        val finalRes = webpResult.fold(
            onSuccess = { webpBytes ->
                repository.addOrUpdateSticker(
                    packId = packId,
                    webpBytes = webpBytes,
                    isAnimated = true,
                    emojis = emojis,
                    durationMs = state.effectiveOutputDurationMs
                )
            },
            onFailure = { Result.failure(it) }
        )

        _uiState.update {
            it.copy(
                isExporting = false,
                exportProgress = 0f,
                errorMessage = finalRes.exceptionOrNull()?.message
            )
        }
        return finalRes
    }

    private fun renderTrimmedSequenceFrames(
        sourceFrames: List<AnimatedFrameBitmap>,
        config: AnimatedTransformConfig
    ): Result<List<AnimatedFrameBitmap>> {
        if (sourceFrames.isEmpty()) {
            return Result.failure(IllegalArgumentException("No animation frames available."))
        }
        val selected = mutableListOf<AnimatedFrameBitmap>()
        var cursorMs = 0L
        val speed = config.speedMultiplier.coerceIn(0.5f, 2.0f)

        for (frame in sourceFrames) {
            val frameEnd = cursorMs + frame.durationMs
            if (frameEnd > config.startMs && cursorMs < config.endMs) {
                val rendered = VideoFrameExtractor.renderAnimatedFrame(
                    source = frame.bitmap,
                    config = config,
                    typefaceResolver = { style, bold, italic ->
                        StickerTypefaceResolver.resolve(app, style, bold, italic)
                    }
                )
                val adjustedDuration = (frame.durationMs / speed).toInt().coerceAtLeast(16)
                selected += AnimatedFrameBitmap(rendered, adjustedDuration)
            }
            cursorMs = frameEnd
        }
        if (selected.isEmpty()) {
            val rendered = VideoFrameExtractor.renderAnimatedFrame(
                source = sourceFrames.first().bitmap,
                config = config,
                typefaceResolver = { style, bold, italic ->
                    StickerTypefaceResolver.resolve(app, style, bold, italic)
                }
            )
            selected += AnimatedFrameBitmap(rendered, 100)
        }
        return Result.success(selected)
    }

    override fun onCleared() {
        super.onCleared()
        rawDecodedFrames.forEach { it.bitmap.recycle() }
        _uiState.value.timelineThumbnails.forEach { it.recycle() }
        _uiState.value.previewFrames.forEach { it.bitmap.recycle() }
    }
}
