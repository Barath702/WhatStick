package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.WhatStickApp
import com.example.data.model.StickerEntity
import com.example.media.BackgroundRemover
import com.example.media.BitmapLoader
import com.example.media.DrawStroke
import com.example.media.StaticStickerDocument
import com.example.media.StaticStickerRenderer
import com.example.media.StickerTypefaceResolver
import com.example.media.TextOverlayItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface MaskBrushCommand {
    data object BeginStroke : MaskBrushCommand
    data class PaintPoint(
        val normX: Float,
        val normY: Float,
        val brushRadiusCanvasPx: Float,
        val restore: Boolean
    ) : MaskBrushCommand
    data object EndStroke : MaskBrushCommand
}

private data class EditorSnapshot(
    val alphaMask: ByteArray,
    val document: StaticStickerDocument
)

data class StaticEditorUiState(
    val isLoading: Boolean = true,
    val isProcessingSmartObject: Boolean = false,
    val isExporting: Boolean = false,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val previewBitmap: Bitmap? = null,
    val document: StaticStickerDocument = StaticStickerDocument(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val editingPackId: String? = null,
    val editingStickerId: String? = null,
    val existingEmojis: String = "😄"
)

@OptIn(ExperimentalCoroutinesApi::class)
class StaticEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WhatStickApp
    private val repository = app.repository

    private val _uiState = MutableStateFlow(StaticEditorUiState())
    val uiState: StateFlow<StaticEditorUiState> = _uiState.asStateFlow()

    /**
     * Single-threaded serialized worker guarantees mask mutations, bitmap pixel updates,
     * preview rendering, undo/redo, and WebP export never race or touch a recycled bitmap.
     */
    private val workerDispatcher = Dispatchers.Default.limitedParallelism(1)

    private var sourceBitmap: Bitmap? = null
    private var originalAlphaMask: ByteArray? = null
    private var currentAlphaMask: ByteArray? = null
    private var maskedSubjectBitmap: Bitmap? = null
    private var scratchPixels: IntArray = IntArray(0)
    private var subjectVersion: Int = 0
    private val renderCache = StaticStickerRenderer.RenderSessionCache()

    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private val previewTrigger = Channel<Unit>(Channel.CONFLATED)
    private val pendingBrushCommands = ConcurrentLinkedQueue<MaskBrushCommand>()
    private val brushFlushScheduled = AtomicBoolean(false)
    private var pendingBrushUndoSaved = false
    private var lastBrushSrcPoint: Pair<Float, Float>? = null

    init {
        viewModelScope.launch(workerDispatcher) {
            for (ignored in previewTrigger) {
                val subject = maskedSubjectBitmap ?: continue
                if (subject.isRecycled) continue
                val doc = _uiState.value.document
                val rendered = renderPreviewNowLocked(subject, doc)
                _uiState.update { it.copy(previewBitmap = rendered) }
            }
        }
    }

    fun loadNewImage(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val res = BitmapLoader.loadWorkingBitmap(app, uri, maxDimension = 1024)
            res.onSuccess { bmp ->
                initWithBitmap(bmp, packId = null, stickerId = null, emojis = "😄")
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Unable to load selected image."
                    )
                }
            }
        }
    }

    fun loadExistingSticker(packId: String, stickerId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val sticker = repository.getStickerById(stickerId)
            if (sticker == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Sticker not found.") }
                return@launch
            }
            val file = repository.getStickerFile(packId, sticker.fileName)
            val res = BitmapLoader.loadWorkingBitmapFromFile(file, maxDimension = 1024)
            res.onSuccess { bmp ->
                initWithBitmap(bmp, packId = packId, stickerId = stickerId, emojis = sticker.emojis)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to open sticker.")
                }
            }
        }
    }

    internal suspend fun initWithBitmap(
        bmp: Bitmap,
        packId: String?,
        stickerId: String?,
        emojis: String
    ) {
        var initialDoc = StaticStickerDocument()
        withContext(workerDispatcher) {
            val prevSource = sourceBitmap
            val prevMasked = maskedSubjectBitmap
            sourceBitmap = bmp
            val alpha = BackgroundRemover.createFullMaskFromSource(bmp)
            originalAlphaMask = alpha
            currentAlphaMask = alpha.copyOf()
            scratchPixels = IntArray(bmp.width * bmp.height)
            val working = BackgroundRemover.applyAlphaMask(bmp, alpha)
            maskedSubjectBitmap = working
            subjectVersion++
            renderCache.invalidateSubject()
            initialDoc = StaticStickerRenderer.computeAutoTrimmedCropDoc(working, initialDoc)
            undoStack.clear()
            redoStack.clear()
            pendingBrushCommands.clear()
            brushFlushScheduled.set(false)
            pendingBrushUndoSaved = false
            lastBrushSrcPoint = null
            if (prevSource != null && prevSource !== bmp) prevSource.recycle()
            prevMasked?.recycle()
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                sourceWidth = bmp.width,
                sourceHeight = bmp.height,
                document = initialDoc,
                canUndo = false,
                canRedo = false,
                editingPackId = packId,
                editingStickerId = stickerId,
                existingEmojis = emojis,
                errorMessage = null
            )
        }
        schedulePreviewRender()
    }

    fun restoreOriginalMask() {
        viewModelScope.launch(workerDispatcher) {
            drainBrushCommandsLocked(emitPendingPreview = false)
            val src = sourceBitmap ?: return@launch
            val orig = originalAlphaMask ?: return@launch
            val working = maskedSubjectBitmap ?: return@launch
            if (src.isRecycled || working.isRecycled) return@launch

            pushUndoSnapshotLocked()
            val restored = orig.copyOf()
            currentAlphaMask = restored
            BackgroundRemover.applyAlphaMaskInto(src, restored, scratchPixels, working)
            subjectVersion++
            renderCache.invalidateSubject()
            val rendered = renderPreviewNowLocked(working, _uiState.value.document)
            _uiState.update {
                it.copy(
                    previewBitmap = rendered,
                    statusMessage = "Original image restored.",
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            }
        }
    }

    fun runSmartObjectExtraction() {
        viewModelScope.launch(workerDispatcher) {
            drainBrushCommandsLocked(emitPendingPreview = false)
            val src = sourceBitmap ?: return@launch
            val working = maskedSubjectBitmap ?: return@launch
            if (src.isRecycled || working.isRecycled) return@launch

            _uiState.update { it.copy(isProcessingSmartObject = true, errorMessage = null) }
            pushUndoSnapshotLocked()

            val smartMask = BackgroundRemover.extractSmartObjectMask(src)
            currentAlphaMask = smartMask
            BackgroundRemover.applyAlphaMaskInto(src, smartMask, scratchPixels, working)
            subjectVersion++
            renderCache.invalidateSubject()
            val baseDoc = _uiState.value.document
            val updatedDoc = if (baseDoc.strokes.isEmpty()) {
                StaticStickerRenderer.computeAutoTrimmedCropDoc(working, baseDoc)
            } else {
                baseDoc
            }
            val rendered = renderPreviewNowLocked(working, updatedDoc)

            _uiState.update {
                it.copy(
                    isProcessingSmartObject = false,
                    document = updatedDoc,
                    previewBitmap = rendered,
                    statusMessage = "Smart Object isolated subject.",
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            }
        }
    }

    fun beginMaskBrushStroke() {
        enqueueBrushCommand(MaskBrushCommand.BeginStroke)
    }

    fun endMaskBrushStroke() {
        enqueueBrushCommand(MaskBrushCommand.EndStroke)
    }

    fun paintMaskBrushAtCanvasNorm(
        canvasNormX: Float,
        canvasNormY: Float,
        brushRadiusCanvasPx: Float,
        restore: Boolean
    ) {
        enqueueBrushCommand(
            MaskBrushCommand.PaintPoint(
                normX = canvasNormX,
                normY = canvasNormY,
                brushRadiusCanvasPx = brushRadiusCanvasPx,
                restore = restore
            )
        )
    }

    private fun enqueueBrushCommand(cmd: MaskBrushCommand) {
        pendingBrushCommands.add(cmd)
        if (brushFlushScheduled.compareAndSet(false, true)) {
            viewModelScope.launch(workerDispatcher) {
                drainBrushCommandsLocked(emitPendingPreview = true)
            }
        }
    }

    private fun drainBrushCommandsLocked(emitPendingPreview: Boolean) {
        while (true) {
            brushFlushScheduled.set(false)
            var paintedAny = false
            while (true) {
                val cmd = pendingBrushCommands.poll() ?: break
                when (cmd) {
                    MaskBrushCommand.BeginStroke -> {
                        if (paintedAny && emitPendingPreview) {
                            emitBrushPreviewLocked()
                            paintedAny = false
                        }
                        handleBeginBrushStrokeLocked()
                    }
                    is MaskBrushCommand.PaintPoint -> {
                        if (applyBrushPaintPointLocked(cmd)) {
                            paintedAny = true
                        }
                    }
                    MaskBrushCommand.EndStroke -> {
                        if (paintedAny && emitPendingPreview) {
                            emitBrushPreviewLocked()
                            paintedAny = false
                        }
                        handleEndBrushStrokeLocked(emitPreview = emitPendingPreview)
                    }
                }
            }
            if (paintedAny && emitPendingPreview) {
                emitBrushPreviewLocked()
            }
            if (pendingBrushCommands.isEmpty()) break
            if (!brushFlushScheduled.compareAndSet(false, true)) break
        }
    }

    private fun handleBeginBrushStrokeLocked() {
        if (!pendingBrushUndoSaved) {
            pushUndoSnapshotLocked()
            pendingBrushUndoSaved = true
            lastBrushSrcPoint = null
            _uiState.update {
                it.copy(
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            }
        }
    }

    private fun applyBrushPaintPointLocked(cmd: MaskBrushCommand.PaintPoint): Boolean {
        val src = sourceBitmap ?: return false
        val mask = currentAlphaMask ?: return false
        val working = maskedSubjectBitmap ?: return false
        if (src.isRecycled || working.isRecycled) return false

        if (!pendingBrushUndoSaved) {
            pushUndoSnapshotLocked()
            pendingBrushUndoSaved = true
        }

        val doc = _uiState.value.document
        val params = StaticStickerRenderer.computeSubjectTransform(src.width, src.height, doc)
        val (srcX, srcY) = StaticStickerRenderer.canvasNormToSourcePixel(
            normX = cmd.normX,
            normY = cmd.normY,
            srcWidth = src.width,
            srcHeight = src.height,
            doc = doc
        )
        val safeScale = params.scale.coerceAtLeast(0.01f)
        val radiusInSrc = (cmd.brushRadiusCanvasPx / safeScale).coerceIn(2f, 250f)

        val prev = lastBrushSrcPoint
        val startX = prev?.first ?: srcX
        val startY = prev?.second ?: srcY
        lastBrushSrcPoint = srcX to srcY

        BackgroundRemover.paintMaskBrushSegmentAndUpdateBitmap(
            mask = mask,
            width = src.width,
            height = src.height,
            startX = startX,
            startY = startY,
            endX = srcX,
            endY = srcY,
            radiusPx = radiusInSrc,
            restore = cmd.restore,
            originalAlpha = originalAlphaMask,
            sourceBitmap = src,
            scratchPixels = scratchPixels,
            targetBitmap = working
        )
        return true
    }

    private fun emitBrushPreviewLocked() {
        val working = maskedSubjectBitmap ?: return
        if (working.isRecycled) return
        subjectVersion++
        renderCache.invalidateSubject()
        val rendered = renderPreviewNowLocked(working, _uiState.value.document)
        _uiState.update {
            it.copy(
                previewBitmap = rendered,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    private fun handleEndBrushStrokeLocked(emitPreview: Boolean) {
        pendingBrushUndoSaved = false
        lastBrushSrcPoint = null

        val working = maskedSubjectBitmap ?: return
        if (working.isRecycled) return
        val doc = _uiState.value.document
        // Auto-fit once all 4 sides around the current crop window have substantial unused transparent space
        if (doc.strokes.isEmpty() && doc.textItems.isEmpty() &&
            kotlin.math.abs(doc.zoom - 1f) < 0.02f &&
            kotlin.math.abs(doc.offsetX) < 0.02f &&
            kotlin.math.abs(doc.offsetY) < 0.02f
        ) {
            val bounds = StaticStickerRenderer.detectVisibleAlphaBounds(working) ?: return
            val w = working.width.toFloat().coerceAtLeast(1f)
            val h = working.height.toFloat().coerceAtLeast(1f)
            val bLeft = bounds.left / w
            val bRight = (bounds.right + 1) / w
            val bTop = bounds.top / h
            val bBottom = (bounds.bottom + 1) / h
            val cropSpanW = (doc.cropRight - doc.cropLeft).coerceAtLeast(0.05f)
            val cropSpanH = (doc.cropBottom - doc.cropTop).coerceAtLeast(0.05f)
            val relPadL = (bLeft - doc.cropLeft) / cropSpanW
            val relPadR = (doc.cropRight - bRight) / cropSpanW
            val relPadT = (bTop - doc.cropTop) / cropSpanH
            val relPadB = (doc.cropBottom - bBottom) / cropSpanH
            if (relPadL > 0.12f && relPadR > 0.12f && relPadT > 0.12f && relPadB > 0.12f) {
                val fittedDoc = StaticStickerRenderer.computeAutoTrimmedCropDoc(working, doc)
                if (fittedDoc != doc) {
                    if (emitPreview) {
                        val rendered = renderPreviewNowLocked(working, fittedDoc)
                        _uiState.update { it.copy(document = fittedDoc, previewBitmap = rendered) }
                    } else {
                        _uiState.update { it.copy(document = fittedDoc) }
                    }
                }
            }
        }
    }

    fun applyPinchZoomAndPan(
        zoomDelta: Float,
        panDeltaNormX: Float,
        panDeltaNormY: Float
    ) {
        updateDocument(recordHistory = false) { doc ->
            doc.copy(
                zoom = (doc.zoom * zoomDelta).coerceIn(0.4f, 5.0f),
                offsetX = (doc.offsetX + panDeltaNormX).coerceIn(-0.75f, 0.75f),
                offsetY = (doc.offsetY + panDeltaNormY).coerceIn(-0.75f, 0.75f)
            )
        }
    }

    fun toggleDoubleTapZoom() {
        updateDocument(recordHistory = true) { doc ->
            if (doc.zoom > 1.15f) {
                doc.copy(zoom = 1f, offsetX = 0f, offsetY = 0f)
            } else {
                doc.copy(zoom = 2f)
            }
        }
    }

    fun resetViewportZoom() {
        updateDocument(recordHistory = true) { doc ->
            doc.copy(zoom = 1f, offsetX = 0f, offsetY = 0f)
        }
    }

    fun updateStretchX(stretchX: Float) {
        val clamped = stretchX.coerceIn(0.25f, 2.5f)
        updateDocument(recordHistory = false) { it.copy(stretchX = clamped) }
    }

    fun updateStretchY(stretchY: Float) {
        val clamped = stretchY.coerceIn(0.25f, 2.5f)
        updateDocument(recordHistory = false) { it.copy(stretchY = clamped) }
    }

    fun resetStretch() {
        updateDocument(recordHistory = true) { it.copy(stretchX = 1f, stretchY = 1f) }
    }

    fun updateDocument(
        recordHistory: Boolean = true,
        transform: (StaticStickerDocument) -> StaticStickerDocument
    ) {
        var prevDoc: StaticStickerDocument? = null
        _uiState.update { current ->
            prevDoc = current.document
            current.copy(document = transform(current.document))
        }
        val capturedPrev = prevDoc
        if (recordHistory && capturedPrev != null) {
            viewModelScope.launch(workerDispatcher) {
                pushUndoSnapshotWithDocLocked(capturedPrev)
                _uiState.update {
                    it.copy(
                        canUndo = undoStack.isNotEmpty(),
                        canRedo = redoStack.isNotEmpty()
                    )
                }
            }
        }
        schedulePreviewRender()
    }

    fun addDrawStroke(stroke: DrawStroke) {
        if (stroke.points.isEmpty()) return
        updateDocument(recordHistory = true) { doc ->
            doc.copy(strokes = doc.strokes + stroke)
        }
    }

    fun addOrUpdateTextItem(item: TextOverlayItem) {
        updateDocument(recordHistory = true) { doc ->
            val existingIdx = doc.textItems.indexOfFirst { it.id == item.id }
            val updated = if (existingIdx >= 0) {
                doc.textItems.toMutableList().apply { set(existingIdx, item) }
            } else {
                doc.textItems + item
            }
            doc.copy(textItems = updated)
        }
    }

    fun moveOrRotateTextItem(id: String, normX: Float, normY: Float, rotationDeg: Float, fontSizeSp: Float) {
        val currentDoc = _uiState.value.document
        val normAngle = ((((rotationDeg + 180f) % 360f) + 360f) % 360f) - 180f
        val updated = currentDoc.textItems.map { item ->
            if (item.id == id) {
                item.copy(
                    normX = normX.coerceIn(0.04f, 0.96f),
                    normY = normY.coerceIn(0.04f, 0.96f),
                    rotationDeg = normAngle,
                    fontSizeSp = fontSizeSp.coerceIn(14f, 110f)
                )
            } else item
        }
        val newDoc = currentDoc.copy(textItems = updated)
        _uiState.update { it.copy(document = newDoc) }
        schedulePreviewRender()
    }

    fun recordTransformUndoPoint() {
        val docAtStart = _uiState.value.document
        viewModelScope.launch(workerDispatcher) {
            pushUndoSnapshotWithDocLocked(docAtStart)
            _uiState.update {
                it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
            }
        }
    }

    fun deleteTextItem(id: String) {
        updateDocument(recordHistory = true) { doc ->
            doc.copy(textItems = doc.textItems.filterNot { it.id == id })
        }
    }

    fun undo() {
        viewModelScope.launch(workerDispatcher) {
            drainBrushCommandsLocked(emitPendingPreview = false)
            val snap = undoStack.removeLastOrNull() ?: return@launch
            val mask = currentAlphaMask ?: return@launch
            val src = sourceBitmap ?: return@launch
            val working = maskedSubjectBitmap ?: return@launch
            if (src.isRecycled || working.isRecycled) return@launch

            redoStack.addLast(EditorSnapshot(mask.copyOf(), _uiState.value.document))
            val restoredMask = snap.alphaMask.copyOf()
            currentAlphaMask = restoredMask
            BackgroundRemover.applyAlphaMaskInto(src, restoredMask, scratchPixels, working)
            subjectVersion++
            renderCache.invalidateSubject()
            val rendered = renderPreviewNowLocked(working, snap.document)
            _uiState.update {
                it.copy(
                    document = snap.document,
                    previewBitmap = rendered,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            }
        }
    }

    fun redo() {
        viewModelScope.launch(workerDispatcher) {
            drainBrushCommandsLocked(emitPendingPreview = false)
            val snap = redoStack.removeLastOrNull() ?: return@launch
            val mask = currentAlphaMask ?: return@launch
            val src = sourceBitmap ?: return@launch
            val working = maskedSubjectBitmap ?: return@launch
            if (src.isRecycled || working.isRecycled) return@launch

            undoStack.addLast(EditorSnapshot(mask.copyOf(), _uiState.value.document))
            val restoredMask = snap.alphaMask.copyOf()
            currentAlphaMask = restoredMask
            BackgroundRemover.applyAlphaMaskInto(src, restoredMask, scratchPixels, working)
            subjectVersion++
            renderCache.invalidateSubject()
            val rendered = renderPreviewNowLocked(working, snap.document)
            _uiState.update {
                it.copy(
                    document = snap.document,
                    previewBitmap = rendered,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty()
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    suspend fun exportAndSaveToPack(
        packId: String,
        emojis: String
    ): Result<StickerEntity> {
        _uiState.update { it.copy(isExporting = true, errorMessage = null) }
        val snapshotSubject: Bitmap? = withContext(workerDispatcher) {
            drainBrushCommandsLocked(emitPendingPreview = false)
            val subject = maskedSubjectBitmap
            if (subject != null && !subject.isRecycled) {
                subject.copy(Bitmap.Config.ARGB_8888, false).also { it.setHasAlpha(true) }
            } else {
                null
            }
        }
        if (snapshotSubject == null) {
            _uiState.update { it.copy(isExporting = false) }
            return Result.failure(IllegalStateException("Sticker canvas is not ready."))
        }

        val encoded = try {
            StaticStickerRenderer.exportStaticStickerWebp(
                maskedSubject = snapshotSubject,
                doc = _uiState.value.document,
                typefaceResolver = { style, bold, italic ->
                    StickerTypefaceResolver.resolve(app, style, bold, italic)
                }
            )
        } finally {
            snapshotSubject.recycle()
        }

        val saveResult = encoded.fold(
            onSuccess = { webpBytes ->
                val existingId = if (_uiState.value.editingPackId == packId) {
                    _uiState.value.editingStickerId
                } else null
                repository.addOrUpdateSticker(
                    packId = packId,
                    webpBytes = webpBytes,
                    isAnimated = false,
                    emojis = emojis,
                    durationMs = 0L,
                    existingStickerId = existingId
                )
            },
            onFailure = { Result.failure(it) }
        )
        _uiState.update {
            it.copy(
                isExporting = false,
                errorMessage = saveResult.exceptionOrNull()?.message
            )
        }
        return saveResult
    }

    private fun pushUndoSnapshotLocked() {
        pushUndoSnapshotWithDocLocked(_uiState.value.document)
    }

    private fun pushUndoSnapshotWithDocLocked(doc: StaticStickerDocument) {
        val mask = currentAlphaMask ?: return
        if (undoStack.size >= 25) undoStack.removeFirst()
        undoStack.addLast(EditorSnapshot(mask.copyOf(), doc))
        redoStack.clear()
    }

    private fun renderPreviewNowLocked(
        subject: Bitmap,
        doc: StaticStickerDocument
    ): Bitmap {
        return StaticStickerRenderer.renderPreviewWithCache(
            maskedSubject = subject,
            subjectVersion = subjectVersion,
            doc = doc,
            cache = renderCache,
            typefaceResolver = { style, bold, italic ->
                StickerTypefaceResolver.resolve(app, style, bold, italic)
            }
        )
    }

    private fun schedulePreviewRender() {
        previewTrigger.trySend(Unit)
    }

    override fun onCleared() {
        previewTrigger.close()
        super.onCleared()
        CoroutineScope(workerDispatcher).launch {
            renderCache.release()
            sourceBitmap?.recycle()
            maskedSubjectBitmap?.recycle()
            sourceBitmap = null
            maskedSubjectBitmap = null
        }
    }
}
