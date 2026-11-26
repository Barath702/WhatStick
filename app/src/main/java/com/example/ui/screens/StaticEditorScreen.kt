package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BorderOuter
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.StickerPackWithStickers
import com.example.media.CropAspectRatio
import com.example.media.DrawStroke
import com.example.media.StaticStickerDocument
import com.example.media.StaticStickerRenderer
import com.example.media.StickerTypefaceResolver
import com.example.media.TextOverlayItem
import com.example.ui.components.CheckerboardBackground
import com.example.ui.components.PackPickerSaveDialog
import com.example.ui.components.TextOverlayEditorDialog
import com.example.ui.components.whatStickFilterChipBorder
import com.example.ui.components.whatStickFilterChipColors
import com.example.ui.viewmodel.StaticEditorViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class StaticToolTab(val label: String, val icon: ImageVector) {
    TRANSFORM("Crop", Icons.Default.Crop),
    ERASER("Erase", Icons.Default.CleaningServices),
    BACKGROUND("Backdrop", Icons.Default.FormatColorFill),
    OUTLINE("Outline", Icons.Default.BorderOuter),
    TEXT("Text", Icons.Default.TextFields),
    DRAW("Draw", Icons.Default.Brush)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaticEditorScreen(
    viewModel: StaticEditorViewModel,
    packs: List<StickerPackWithStickers>,
    initialTargetPackId: String?,
    onCreatePack: suspend (name: String, publisher: String, isAnimated: Boolean) -> String,
    onSavedToPack: (packId: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var activeTool by remember { mutableStateOf(StaticToolTab.TRANSFORM) }
    var isChatPreview by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedSuccessPackId by remember { mutableStateOf<String?>(null) }

    // Manual Erase / Restore brush state
    var restoreBrushMode by remember { mutableStateOf(false) }
    var maskBrushSize by remember { mutableFloatStateOf(24f) }

    // Draw / Eraser stroke tool state
    var drawColorArgb by remember { mutableIntStateOf(Color(0xFFEF4444).toArgb()) }
    var drawBrushSize by remember { mutableFloatStateOf(14f) }
    var drawEraserMode by remember { mutableStateOf(false) }
    val activeDrawPoints = remember { mutableStateListOf<Pair<Float, Float>>() }

    // Text overlay editor state
    var editingTextItem by remember { mutableStateOf<TextOverlayItem?>(null) }
    var showTextDialog by remember { mutableStateOf(false) }
    var selectedTextId by remember { mutableStateOf<String?>(null) }

    val latestDoc by rememberUpdatedState(uiState.document)
    val latestSelectedTextId by rememberUpdatedState(selectedTextId)

    BackHandler { onBack() }

    LaunchedEffect(uiState.statusMessage, uiState.errorMessage) {
        val msg = uiState.errorMessage ?: uiState.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sticker Studio", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("static_editor_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = uiState.canUndo,
                        modifier = Modifier.testTag("static_editor_undo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = uiState.canRedo,
                        modifier = Modifier.testTag("static_editor_redo_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(
                        onClick = { isChatPreview = !isChatPreview },
                        modifier = Modifier.testTag("static_editor_preview_button")
                    ) {
                        Icon(
                            if (isChatPreview) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle chat preview"
                        )
                    }
                    Button(
                        onClick = { showSaveDialog = true },
                        enabled = !uiState.isLoading && !uiState.isExporting && uiState.previewBitmap != null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("static_editor_save_button")
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Central Canvas Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else if (isChatPreview) {
                    WhatsAppChatPreviewCard(previewBitmap = uiState.previewBitmap)
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(24.dp)
                            )
                    ) {
                        val canvasSizePx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                        CheckerboardBackground(modifier = Modifier.fillMaxSize(), cellSize = 14.dp)

                        uiState.previewBitmap?.let { bmp ->
                            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Sticker editing canvas",
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.Medium,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Live active freehand stroke preview
                        if (activeDrawPoints.isNotEmpty()) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val path = Path()
                                val first = activeDrawPoints.first()
                                path.moveTo(first.first * size.width, first.second * size.height)
                                for (i in 1 until activeDrawPoints.size) {
                                    val pt = activeDrawPoints[i]
                                    path.lineTo(pt.first * size.width, pt.second * size.height)
                                }
                                drawPath(
                                    path = path,
                                    color = if (drawEraserMode) Color.White.copy(alpha = 0.6f) else Color(drawColorArgb),
                                    style = Stroke(
                                        width = drawBrushSize * (size.width / 512f),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }

                        // Selected text bounding box + handles overlay (editor-only, never exported)
                        val selectedTextItem = if (activeTool == StaticToolTab.TEXT) {
                            uiState.document.textItems.firstOrNull { it.id == selectedTextId }
                        } else null

                        val selectionColor = MaterialTheme.colorScheme.primary
                        val handleFillColor = MaterialTheme.colorScheme.onPrimary

                        if (selectedTextItem != null) {
                            val bounds = remember(selectedTextItem) {
                                StaticStickerRenderer.measureTextBoundsNorm(
                                    item = selectedTextItem,
                                    typefaceResolver = { style, bold, italic ->
                                        StickerTypefaceResolver.resolve(context, style, bold, italic)
                                    }
                                )
                            }
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val cx = bounds.centerX * size.width
                                val cy = bounds.centerY * size.height
                                val w = bounds.widthNorm * size.width
                                val h = bounds.heightNorm * size.height
                                val left = cx - w / 2f
                                val top = cy - h / 2f

                                withTransform({
                                    rotate(degrees = bounds.rotationDeg, pivot = Offset(cx, cy))
                                }) {
                                    drawRect(
                                        color = selectionColor,
                                        topLeft = Offset(left, top),
                                        size = Size(w, h),
                                        style = Stroke(
                                            width = 2.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                                        )
                                    )
                                    val corners = listOf(
                                        Offset(left, top),
                                        Offset(left + w, top),
                                        Offset(left, top + h),
                                        Offset(left + w, top + h)
                                    )
                                    corners.forEachIndexed { idx, pt ->
                                        val radius = if (idx == 3) 7.dp.toPx() else 4.5.dp.toPx()
                                        drawCircle(color = selectionColor, radius = radius, center = pt)
                                        drawCircle(color = handleFillColor, radius = radius * 0.55f, center = pt)
                                    }
                                }
                            }
                        }

                        // Unified non-interfering gesture overlay per active tool
                        val gestureModifier = when (activeTool) {
                            StaticToolTab.DRAW -> {
                                Modifier.pointerInput(drawColorArgb, drawBrushSize, drawEraserMode) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val h = size.height.toFloat().coerceAtLeast(1f)
                                        var drawingSingle = false
                                        var pinching = false
                                        val startX = (down.position.x / w).coerceIn(0f, 1f)
                                        val startY = (down.position.y / h).coerceIn(0f, 1f)

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val pressedCount = event.changes.count { it.pressed }
                                            if (pressedCount == 0) {
                                                if (drawingSingle && activeDrawPoints.isNotEmpty()) {
                                                    viewModel.addDrawStroke(
                                                        DrawStroke(
                                                            points = activeDrawPoints.toList(),
                                                            colorArgb = drawColorArgb,
                                                            widthPx = drawBrushSize,
                                                            isEraser = drawEraserMode
                                                        )
                                                    )
                                                }
                                                activeDrawPoints.clear()
                                                break
                                            }
                                            if (pressedCount >= 2) {
                                                if (drawingSingle) {
                                                    activeDrawPoints.clear()
                                                    drawingSingle = false
                                                }
                                                pinching = true
                                                val zoom = event.calculateZoom()
                                                val pan = event.calculatePan()
                                                if (zoom != 1f || pan != Offset.Zero) {
                                                    viewModel.applyPinchZoomAndPan(
                                                        zoomDelta = zoom,
                                                        panDeltaNormX = pan.x / w,
                                                        panDeltaNormY = pan.y / h
                                                    )
                                                }
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            } else if (!pinching) {
                                                val ch = event.changes.firstOrNull { it.pressed } ?: continue
                                                if (!drawingSingle) {
                                                    drawingSingle = true
                                                    activeDrawPoints.clear()
                                                    activeDrawPoints += startX to startY
                                                }
                                                activeDrawPoints += (ch.position.x / w).coerceIn(0f, 1f) to
                                                    (ch.position.y / h).coerceIn(0f, 1f)
                                                ch.consume()
                                            }
                                        }
                                    }
                                }
                            }

                            StaticToolTab.ERASER -> {
                                Modifier.pointerInput(restoreBrushMode, maskBrushSize) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val h = size.height.toFloat().coerceAtLeast(1f)
                                        var brushing = false
                                        var pinching = false
                                        val downX = (down.position.x / w).coerceIn(0f, 1f)
                                        val downY = (down.position.y / h).coerceIn(0f, 1f)

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val pressedCount = event.changes.count { it.pressed }
                                            if (pressedCount == 0) {
                                                if (!brushing && !pinching) {
                                                    viewModel.beginMaskBrushStroke()
                                                    viewModel.paintMaskBrushAtCanvasNorm(
                                                        downX,
                                                        downY,
                                                        maskBrushSize,
                                                        restoreBrushMode
                                                    )
                                                }
                                                if (brushing || !pinching) {
                                                    viewModel.endMaskBrushStroke()
                                                }
                                                break
                                            }
                                            if (pressedCount >= 2) {
                                                if (brushing) {
                                                    viewModel.endMaskBrushStroke()
                                                    brushing = false
                                                }
                                                pinching = true
                                                val zoom = event.calculateZoom()
                                                val pan = event.calculatePan()
                                                if (zoom != 1f || pan != Offset.Zero) {
                                                    viewModel.applyPinchZoomAndPan(
                                                        zoomDelta = zoom,
                                                        panDeltaNormX = pan.x / w,
                                                        panDeltaNormY = pan.y / h
                                                    )
                                                }
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            } else if (!pinching) {
                                                val ch = event.changes.firstOrNull { it.pressed } ?: continue
                                                if (!brushing) {
                                                    brushing = true
                                                    viewModel.beginMaskBrushStroke()
                                                    viewModel.paintMaskBrushAtCanvasNorm(
                                                        downX,
                                                        downY,
                                                        maskBrushSize,
                                                        restoreBrushMode
                                                    )
                                                }
                                                if (ch.positionChanged()) {
                                                    viewModel.paintMaskBrushAtCanvasNorm(
                                                        (ch.position.x / w).coerceIn(0f, 1f),
                                                        (ch.position.y / h).coerceIn(0f, 1f),
                                                        maskBrushSize,
                                                        restoreBrushMode
                                                    )
                                                    ch.consume()
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            StaticToolTab.TEXT -> {
                                Modifier
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = { pos ->
                                                val nx = (pos.x / canvasSizePx).coerceIn(0f, 1f)
                                                val ny = (pos.y / canvasSizePx).coerceIn(0f, 1f)
                                                val hit = latestDoc.textItems.asReversed().firstOrNull { item ->
                                                    StaticStickerRenderer.isPointInsideText(
                                                        normX = nx,
                                                        normY = ny,
                                                        item = item,
                                                        typefaceResolver = { style, bold, italic ->
                                                            StickerTypefaceResolver.resolve(context, style, bold, italic)
                                                        }
                                                    )
                                                }
                                                selectedTextId = hit?.id
                                            },
                                            onDoubleTap = { pos ->
                                                val nx = (pos.x / canvasSizePx).coerceIn(0f, 1f)
                                                val ny = (pos.y / canvasSizePx).coerceIn(0f, 1f)
                                                val hit = latestDoc.textItems.asReversed().firstOrNull { item ->
                                                    StaticStickerRenderer.isPointInsideText(
                                                        normX = nx,
                                                        normY = ny,
                                                        item = item,
                                                        typefaceResolver = { style, bold, italic ->
                                                            StickerTypefaceResolver.resolve(context, style, bold, italic)
                                                        }
                                                    )
                                                }
                                                if (hit != null) {
                                                    selectedTextId = hit.id
                                                    editingTextItem = hit
                                                    showTextDialog = true
                                                } else {
                                                    viewModel.toggleDoubleTapZoom()
                                                }
                                            }
                                        )
                                    }
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val nx = (down.position.x / canvasSizePx).coerceIn(0f, 1f)
                                            val ny = (down.position.y / canvasSizePx).coerceIn(0f, 1f)

                                            // Check if pointer started on an existing text element or the currently selected handle
                                            val hitItem = latestDoc.textItems.asReversed().firstOrNull { item ->
                                                StaticStickerRenderer.isPointInsideText(
                                                    normX = nx,
                                                    normY = ny,
                                                    item = item,
                                                    typefaceResolver = { style, bold, italic ->
                                                        StickerTypefaceResolver.resolve(context, style, bold, italic)
                                                    }
                                                )
                                            }
                                            val activeId = hitItem?.id ?: latestSelectedTextId
                                            val activeItem = activeId?.let { id ->
                                                latestDoc.textItems.firstOrNull { it.id == id }
                                            }

                                            // Check if single-finger drag started near bottom-right corner handle
                                            val cornerHandleMode = if (activeItem != null) {
                                                val b = StaticStickerRenderer.measureTextBoundsNorm(
                                                    item = activeItem,
                                                    typefaceResolver = { style, bold, italic ->
                                                        StickerTypefaceResolver.resolve(context, style, bold, italic)
                                                    }
                                                )
                                                val rad = Math.toRadians(b.rotationDeg.toDouble())
                                                val lx = b.widthNorm * 0.5f
                                                val ly = b.heightNorm * 0.5f
                                                val handleX = b.centerX + (lx * cos(rad) - ly * sin(rad)).toFloat()
                                                val handleY = b.centerY + (lx * sin(rad) + ly * cos(rad)).toFloat()
                                                hypot((nx - handleX).toDouble(), (ny - handleY).toDouble()) <= 0.08
                                            } else false

                                            if (hitItem != null) {
                                                selectedTextId = hitItem.id
                                            }

                                            var undoRecorded = false
                                            var prevDist = if (activeItem != null) {
                                                hypot(
                                                    ((nx - activeItem.normX) * canvasSizePx).toDouble(),
                                                    ((ny - activeItem.normY) * canvasSizePx).toDouble()
                                                ).toFloat().coerceAtLeast(10f)
                                            } else 10f
                                            var prevAngle = if (activeItem != null) {
                                                Math.toDegrees(
                                                    atan2(
                                                        ((ny - activeItem.normY) * canvasSizePx).toDouble(),
                                                        ((nx - activeItem.normX) * canvasSizePx).toDouble()
                                                    )
                                                ).toFloat()
                                            } else 0f

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.changes.none { it.pressed }) break

                                                val targetId = latestSelectedTextId
                                                val targetItem = targetId?.let { id ->
                                                    latestDoc.textItems.firstOrNull { it.id == id }
                                                }
                                                val pressedCount = event.changes.count { it.pressed }

                                                if (targetItem != null && (hitItem != null || pressedCount >= 2 || cornerHandleMode)) {
                                                    val zoom = event.calculateZoom()
                                                    val pan = event.calculatePan()
                                                    val rot = event.calculateRotation()
                                                    val ch = event.changes.firstOrNull { it.pressed }

                                                    if (cornerHandleMode && pressedCount == 1 && ch != null && ch.positionChanged()) {
                                                        if (!undoRecorded) {
                                                            viewModel.recordTransformUndoPoint()
                                                            undoRecorded = true
                                                        }
                                                        val cxPx = targetItem.normX * canvasSizePx
                                                        val cyPx = targetItem.normY * canvasSizePx
                                                        val newDist = hypot(
                                                            (ch.position.x - cxPx).toDouble(),
                                                            (ch.position.y - cyPx).toDouble()
                                                        ).toFloat().coerceAtLeast(10f)
                                                        val newAngle = Math.toDegrees(
                                                            atan2(
                                                                (ch.position.y - cyPx).toDouble(),
                                                                (ch.position.x - cxPx).toDouble()
                                                            )
                                                        ).toFloat()
                                                        val scaleFactor = (newDist / prevDist).coerceIn(0.8f, 1.25f)
                                                        val deltaAngle = newAngle - prevAngle
                                                        prevDist = newDist
                                                        prevAngle = newAngle
                                                        viewModel.moveOrRotateTextItem(
                                                            id = targetItem.id,
                                                            normX = targetItem.normX,
                                                            normY = targetItem.normY,
                                                            rotationDeg = targetItem.rotationDeg + deltaAngle,
                                                            fontSizeSp = targetItem.fontSizeSp * scaleFactor
                                                        )
                                                        ch.consume()
                                                    } else if (zoom != 1f || abs(rot) > 0.01f || pan != Offset.Zero) {
                                                        if (!undoRecorded) {
                                                            viewModel.recordTransformUndoPoint()
                                                            undoRecorded = true
                                                        }
                                                        viewModel.moveOrRotateTextItem(
                                                            id = targetItem.id,
                                                            normX = targetItem.normX + pan.x / canvasSizePx,
                                                            normY = targetItem.normY + pan.y / canvasSizePx,
                                                            rotationDeg = targetItem.rotationDeg + rot,
                                                            fontSizeSp = targetItem.fontSizeSp * zoom
                                                        )
                                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                    }
                                                } else if (targetItem == null && pressedCount >= 2) {
                                                    // Only zoom/pan underlying image when no text is selected
                                                    val zoom = event.calculateZoom()
                                                    val pan = event.calculatePan()
                                                    if (zoom != 1f || pan != Offset.Zero) {
                                                        viewModel.applyPinchZoomAndPan(
                                                            zoomDelta = zoom,
                                                            panDeltaNormX = pan.x / canvasSizePx,
                                                            panDeltaNormY = pan.y / canvasSizePx
                                                        )
                                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                    }
                                                }
                                            }
                                        }
                                    }
                            }

                            else -> {
                                // Crop, Backdrop, Outline: pinch-to-zoom, pan, and double-tap zoom
                                Modifier
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onDoubleTap = { viewModel.toggleDoubleTapZoom() }
                                        )
                                    }
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            viewModel.applyPinchZoomAndPan(
                                                zoomDelta = zoom,
                                                panDeltaNormX = pan.x / canvasSizePx,
                                                panDeltaNormY = pan.y / canvasSizePx
                                            )
                                        }
                                    }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize().then(gestureModifier))

                        // Subtle zoom / reset indicator badge when zoomed or panned
                        if (abs(uiState.document.zoom - 1f) > 0.05f ||
                            abs(uiState.document.offsetX) > 0.02f ||
                            abs(uiState.document.offsetY) > 0.02f
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(50))
                                    .clickable { viewModel.resetViewportZoom() }
                                    .testTag("static_zoom_reset_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = String.format(java.util.Locale.US, "%.1fx • Reset", uiState.document.zoom),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        if (uiState.isExporting) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                        Text("Optimizing 512×512 WebP...")
                                    }
                                }
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Contextual Tool Controls Bar
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (activeTool) {
                            StaticToolTab.TRANSFORM -> {
                                TransformToolPanel(
                                    doc = uiState.document,
                                    srcWidth = uiState.sourceWidth,
                                    srcHeight = uiState.sourceHeight,
                                    onApplyRatio = { ratio ->
                                        viewModel.updateDocument(recordHistory = true) { doc ->
                                            applyCropRatioToDocument(doc, ratio, uiState.sourceWidth, uiState.sourceHeight)
                                        }
                                    },
                                    onDragStart = { viewModel.recordTransformUndoPoint() },
                                    onRotationChange = { deg ->
                                        viewModel.updateDocument(recordHistory = false) { it.copy(rotationDeg = deg) }
                                    },
                                    onRotate90 = {
                                        viewModel.updateDocument(recordHistory = true) {
                                            it.copy(rotationDeg = (it.rotationDeg + 90f) % 360f)
                                        }
                                    },
                                    onFreeCropInsetChange = { inset ->
                                        viewModel.updateDocument(recordHistory = false) {
                                            it.copy(
                                                cropRatio = CropAspectRatio.FREE,
                                                cropLeft = inset,
                                                cropTop = inset,
                                                cropRight = 1f - inset,
                                                cropBottom = 1f - inset
                                            )
                                        }
                                    },
                                    onStretchXChange = { sx -> viewModel.updateStretchX(sx) },
                                    onStretchYChange = { sy -> viewModel.updateStretchY(sy) },
                                    onResetStretch = { viewModel.resetStretch() },
                                    onReset = {
                                        viewModel.updateDocument(recordHistory = true) {
                                            it.copy(
                                                cropRatio = CropAspectRatio.SQUARE,
                                                cropLeft = 0f,
                                                cropTop = 0f,
                                                cropRight = 1f,
                                                cropBottom = 1f,
                                                zoom = 1f,
                                                stretchX = 1f,
                                                stretchY = 1f,
                                                offsetX = 0f,
                                                offsetY = 0f,
                                                rotationDeg = 0f
                                            )
                                        }
                                    }
                                )
                            }

                            StaticToolTab.ERASER -> {
                                EraserToolPanel(
                                    restoreBrushMode = restoreBrushMode,
                                    brushSize = maskBrushSize,
                                    isProcessingSmartObject = uiState.isProcessingSmartObject,
                                    onSmartObjectClick = { viewModel.runSmartObjectExtraction() },
                                    onSelectEraseBrush = { restoreBrushMode = false },
                                    onSelectRestoreBrush = { restoreBrushMode = true },
                                    onBrushSizeChange = { maskBrushSize = it },
                                    onResetMask = { viewModel.restoreOriginalMask() }
                                )
                            }

                            StaticToolTab.BACKGROUND -> {
                                BackgroundToolPanel(
                                    selectedColorArgb = uiState.document.backgroundColorArgb,
                                    onSelectColor = { argb ->
                                        viewModel.updateDocument(recordHistory = true) {
                                            it.copy(backgroundColorArgb = argb)
                                        }
                                    }
                                )
                            }

                            StaticToolTab.OUTLINE -> {
                                OutlineToolPanel(
                                    thickness = uiState.document.outlineThicknessPx,
                                    colorArgb = uiState.document.outlineColorArgb,
                                    onDragStart = { viewModel.recordTransformUndoPoint() },
                                    onThicknessChange = { t ->
                                        viewModel.updateDocument(recordHistory = false) {
                                            it.copy(outlineThicknessPx = t)
                                        }
                                    },
                                    onColorChange = { c ->
                                        viewModel.updateDocument(recordHistory = true) {
                                            it.copy(outlineColorArgb = c)
                                        }
                                    }
                                )
                            }

                            StaticToolTab.TEXT -> {
                                val selectedItem = uiState.document.textItems.firstOrNull { it.id == selectedTextId }
                                TextToolPanel(
                                    items = uiState.document.textItems,
                                    selectedItem = selectedItem,
                                    onAddClick = {
                                        editingTextItem = null
                                        showTextDialog = true
                                    },
                                    onSelectItem = { item -> selectedTextId = item.id },
                                    onEditItem = { item ->
                                        selectedTextId = item.id
                                        editingTextItem = item
                                        showTextDialog = true
                                    },
                                    onDeleteItem = { id ->
                                        if (selectedTextId == id) selectedTextId = null
                                        viewModel.deleteTextItem(id)
                                    },
                                    onDragStart = { viewModel.recordTransformUndoPoint() },
                                    onResizeSelected = { newSize ->
                                        selectedItem?.let { item ->
                                            viewModel.moveOrRotateTextItem(
                                                id = item.id,
                                                normX = item.normX,
                                                normY = item.normY,
                                                rotationDeg = item.rotationDeg,
                                                fontSizeSp = newSize
                                            )
                                        }
                                    },
                                    onRotateSelected = { newAngle ->
                                        selectedItem?.let { item ->
                                            viewModel.moveOrRotateTextItem(
                                                id = item.id,
                                                normX = item.normX,
                                                normY = item.normY,
                                                rotationDeg = newAngle,
                                                fontSizeSp = item.fontSizeSp
                                            )
                                        }
                                    }
                                )
                            }

                            StaticToolTab.DRAW -> {
                                DrawToolPanel(
                                    colorArgb = drawColorArgb,
                                    brushSize = drawBrushSize,
                                    isEraser = drawEraserMode,
                                    onColorSelected = {
                                        drawEraserMode = false
                                        drawColorArgb = it
                                    },
                                    onToggleEraser = { drawEraserMode = !drawEraserMode },
                                    onBrushSizeChange = { drawBrushSize = it },
                                    onClearStrokes = {
                                        viewModel.updateDocument(recordHistory = true) {
                                            it.copy(strokes = emptyList())
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Tool Tab Selector Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StaticToolTab.entries.forEach { tab ->
                            val selected = activeTool == tab
                            FilterChip(
                                selected = selected,
                                onClick = { activeTool = tab },
                                colors = whatStickFilterChipColors(),
                                border = whatStickFilterChipBorder(selected),
                                label = { Text(tab.label) },
                                leadingIcon = {
                                    Icon(tab.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                modifier = Modifier.testTag("static_tool_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTextDialog) {
        TextOverlayEditorDialog(
            initialItem = editingTextItem,
            onDismiss = { showTextDialog = false },
            onConfirm = { newItem ->
                viewModel.addOrUpdateTextItem(newItem)
                selectedTextId = newItem.id
                showTextDialog = false
            }
        )
    }

    if (showSaveDialog) {
        PackPickerSaveDialog(
            packs = packs,
            isAnimated = false,
            initialPackId = uiState.editingPackId ?: initialTargetPackId,
            initialEmojis = uiState.existingEmojis,
            onDismiss = { showSaveDialog = false },
            onCreateNewPackAndSave = { name, publisher, emojis ->
                showSaveDialog = false
                scope.launch {
                    val packId = onCreatePack(name, publisher, false)
                    val res = viewModel.exportAndSaveToPack(packId, emojis)
                    res.onSuccess { savedSuccessPackId = packId }
                }
            },
            onSelectPackAndSave = { packId, emojis ->
                showSaveDialog = false
                scope.launch {
                    val res = viewModel.exportAndSaveToPack(packId, emojis)
                    res.onSuccess { savedSuccessPackId = packId }
                }
            }
        )
    }

    savedSuccessPackId?.let { packId ->
        AlertDialog(
            onDismissRequest = { savedSuccessPackId = null },
            title = { Text("Sticker Saved!") },
            text = {
                Text("Your 512×512 WebP sticker has been saved and validated for WhatsApp.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        savedSuccessPackId = null
                        onSavedToPack(packId)
                    },
                    modifier = Modifier.testTag("open_pack_after_save_button")
                ) {
                    Text("View Pack")
                }
            },
            dismissButton = {
                TextButton(onClick = { savedSuccessPackId = null }) {
                    Text("Keep Editing")
                }
            }
        )
    }
}

@Composable
private fun WhatsAppChatPreviewCard(previewBitmap: Bitmap?) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFEFEAE2),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .widthIn(max = 380.dp)
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "WhatsApp Chat Preview",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF475569)
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text(
                    "Check out this sticker!",
                    color = Color(0xFF1E293B),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .size(164.dp)
            ) {
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Sticker in chat preview",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun TransformToolPanel(
    doc: StaticStickerDocument,
    srcWidth: Int,
    srcHeight: Int,
    onApplyRatio: (CropAspectRatio) -> Unit,
    onDragStart: () -> Unit,
    onRotationChange: (Float) -> Unit,
    onRotate90: () -> Unit,
    onFreeCropInsetChange: (Float) -> Unit,
    onStretchXChange: (Float) -> Unit,
    onStretchYChange: (Float) -> Unit,
    onResetStretch: () -> Unit,
    onReset: () -> Unit
) {
    var rotationDragging by remember { mutableStateOf(false) }
    var localRotation by remember { mutableFloatStateOf(doc.rotationDeg) }
    var lastSentRotation by remember { mutableFloatStateOf(doc.rotationDeg) }
    LaunchedEffect(doc.rotationDeg) {
        if (!rotationDragging || abs(doc.rotationDeg - lastSentRotation) > 0.01f) {
            localRotation = doc.rotationDeg
            lastSentRotation = doc.rotationDeg
            rotationDragging = false
        }
    }

    var cropDragging by remember { mutableStateOf(false) }
    var localCrop by remember { mutableFloatStateOf(doc.cropLeft.coerceIn(0f, 0.4f)) }
    var lastSentCrop by remember { mutableFloatStateOf(doc.cropLeft.coerceIn(0f, 0.4f)) }
    LaunchedEffect(doc.cropLeft) {
        val clamped = doc.cropLeft.coerceIn(0f, 0.4f)
        if (!cropDragging || abs(clamped - lastSentCrop) > 0.002f) {
            localCrop = clamped
            lastSentCrop = clamped
            cropDragging = false
        }
    }

    var stretchXDragging by remember { mutableStateOf(false) }
    var localStretchX by remember { mutableFloatStateOf(doc.stretchX.coerceIn(0.25f, 2.5f)) }
    var lastSentStretchX by remember { mutableFloatStateOf(doc.stretchX.coerceIn(0.25f, 2.5f)) }
    LaunchedEffect(doc.stretchX) {
        val clamped = doc.stretchX.coerceIn(0.25f, 2.5f)
        if (!stretchXDragging || abs(clamped - lastSentStretchX) > 0.002f) {
            localStretchX = clamped
            lastSentStretchX = clamped
            stretchXDragging = false
        }
    }

    var stretchYDragging by remember { mutableStateOf(false) }
    var localStretchY by remember { mutableFloatStateOf(doc.stretchY.coerceIn(0.25f, 2.5f)) }
    var lastSentStretchY by remember { mutableFloatStateOf(doc.stretchY.coerceIn(0.25f, 2.5f)) }
    LaunchedEffect(doc.stretchY) {
        val clamped = doc.stretchY.coerceIn(0.25f, 2.5f)
        if (!stretchYDragging || abs(clamped - lastSentStretchY) > 0.002f) {
            localStretchY = clamped
            lastSentStretchY = clamped
            stretchYDragging = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CropAspectRatio.entries.forEach { ratio ->
                val selected = doc.cropRatio == ratio
                FilterChip(
                    selected = selected,
                    onClick = { onApplyRatio(ratio) },
                    colors = whatStickFilterChipColors(),
                    border = whatStickFilterChipBorder(selected),
                    label = { Text(ratio.label) }
                )
            }
            AssistChip(
                onClick = onRotate90,
                label = { Text("90°") }
            )
            AssistChip(
                onClick = onReset,
                label = { Text("Reset") },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Rotate ${localRotation.roundToInt()}°",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = localRotation,
                onValueChange = { deg ->
                    if (!rotationDragging) {
                        rotationDragging = true
                        onDragStart()
                    }
                    localRotation = deg
                    lastSentRotation = deg
                    onRotationChange(deg)
                },
                onValueChangeFinished = { rotationDragging = false },
                valueRange = -180f..180f,
                modifier = Modifier.weight(1f)
            )
        }
        if (doc.cropRatio == CropAspectRatio.FREE || (srcWidth > 0 && srcHeight > 0)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Crop trim", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = localCrop,
                    onValueChange = { inset ->
                        if (!cropDragging) {
                            cropDragging = true
                            onDragStart()
                        }
                        localCrop = inset
                        lastSentCrop = inset
                        onFreeCropInsetChange(inset)
                    },
                    onValueChangeFinished = { cropDragging = false },
                    valueRange = 0f..0.4f,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Horizontal Stretch",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = localStretchX,
                onValueChange = { sx ->
                    if (!stretchXDragging) {
                        stretchXDragging = true
                        onDragStart()
                    }
                    localStretchX = sx
                    lastSentStretchX = sx
                    onStretchXChange(sx)
                },
                onValueChangeFinished = { stretchXDragging = false },
                valueRange = 0.25f..2.5f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("static_horizontal_stretch_slider")
            )
            Text(
                "${(localStretchX * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Vertical Stretch",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = localStretchY,
                onValueChange = { sy ->
                    if (!stretchYDragging) {
                        stretchYDragging = true
                        onDragStart()
                    }
                    localStretchY = sy
                    lastSentStretchY = sy
                    onStretchYChange(sy)
                },
                onValueChangeFinished = { stretchYDragging = false },
                valueRange = 0.25f..2.5f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("static_vertical_stretch_slider")
            )
            Text(
                "${(localStretchY * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Pinch to zoom, drag to pan, double-tap to zoom in or reset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onResetStretch,
                modifier = Modifier.testTag("static_reset_stretch_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset Stretch")
            }
        }
    }
}

@Composable
private fun EraserToolPanel(
    restoreBrushMode: Boolean,
    brushSize: Float,
    isProcessingSmartObject: Boolean,
    onSmartObjectClick: () -> Unit,
    onSelectEraseBrush: () -> Unit,
    onSelectRestoreBrush: () -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onResetMask: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onSmartObjectClick,
                enabled = !isProcessingSmartObject,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("smart_object_button")
            ) {
                if (isProcessingSmartObject) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("Smart Object")
            }
            FilterChip(
                selected = !restoreBrushMode,
                onClick = onSelectEraseBrush,
                colors = whatStickFilterChipColors(),
                border = whatStickFilterChipBorder(!restoreBrushMode),
                label = { Text("Erase Brush") },
                leadingIcon = {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("eraser_erase_brush_chip")
            )
            FilterChip(
                selected = restoreBrushMode,
                onClick = onSelectRestoreBrush,
                colors = whatStickFilterChipColors(),
                border = whatStickFilterChipBorder(restoreBrushMode),
                label = { Text("Restore Brush") },
                leadingIcon = {
                    Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("eraser_restore_brush_chip")
            )
            OutlinedButton(
                onClick = onResetMask,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("eraser_reset_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset")
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Brush ${brushSize.toInt()}px", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = brushSize,
                onValueChange = onBrushSizeChange,
                valueRange = 6f..64f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("eraser_brush_size_slider")
            )
        }
        Text(
            "Tap Smart Object to isolate the foreground, or drag to erase/restore. Pinch with two fingers to zoom.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BackgroundToolPanel(
    selectedColorArgb: Int?,
    onSelectColor: (Int?) -> Unit
) {
    val swatches = listOf(
        Color.White,
        Color.Black,
        Color(0xFFDBEAFE),
        Color(0xFFD1FAE5),
        Color(0xFFEDE9FE),
        Color(0xFFFCE7F3),
        Color(0xFFFFEDD5),
        Color(0xFF2563EB),
        Color(0xFFEF4444)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selectedColorArgb == null,
            onClick = { onSelectColor(null) },
            colors = whatStickFilterChipColors(),
            border = whatStickFilterChipBorder(selectedColorArgb == null),
            label = { Text("Transparent") },
            modifier = Modifier.testTag("backdrop_transparent_chip")
        )
        swatches.forEach { color ->
            val argb = color.toArgb()
            val selected = selectedColorArgb == argb
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
                    .clickable { onSelectColor(argb) },
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected backdrop",
                        tint = if (color == Color.White || color.red > 0.8f) Color.Black else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OutlineToolPanel(
    thickness: Float,
    colorArgb: Int,
    onDragStart: () -> Unit,
    onThicknessChange: (Float) -> Unit,
    onColorChange: (Int) -> Unit
) {
    val colors = listOf(
        Color.White,
        Color.Black,
        Color(0xFF3B82F6),
        Color(0xFFEC4899),
        Color(0xFF10B981),
        Color(0xFFF59E0B),
        Color(0xFF8B5CF6)
    )
    var isDragging by remember { mutableStateOf(false) }
    var localThickness by remember { mutableFloatStateOf(thickness.coerceIn(0f, 24f)) }
    var lastSentThickness by remember { mutableFloatStateOf(thickness.coerceIn(0f, 24f)) }
    LaunchedEffect(thickness) {
        val clamped = thickness.coerceIn(0f, 24f)
        if (!isDragging || abs(clamped - lastSentThickness) > 0.01f) {
            localThickness = clamped
            lastSentThickness = clamped
            isDragging = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Border ${localThickness.roundToInt()}px",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = localThickness,
                onValueChange = { t ->
                    if (!isDragging) {
                        isDragging = true
                        onDragStart()
                    }
                    localThickness = t
                    lastSentThickness = t
                    onThicknessChange(t)
                },
                onValueChangeFinished = { isDragging = false },
                valueRange = 0f..24f,
                modifier = Modifier
                    .weight(1f)
                    .testTag("outline_thickness_slider")
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            colors.forEach { c ->
                val argb = c.toArgb()
                val selected = colorArgb == argb
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                        .clickable { onColorChange(argb) }
                )
            }
        }
    }
}

@Composable
private fun TextToolPanel(
    items: List<TextOverlayItem>,
    selectedItem: TextOverlayItem?,
    onAddClick: () -> Unit,
    onSelectItem: (TextOverlayItem) -> Unit,
    onEditItem: (TextOverlayItem) -> Unit,
    onDeleteItem: (String) -> Unit,
    onDragStart: () -> Unit,
    onResizeSelected: (Float) -> Unit,
    onRotateSelected: (Float) -> Unit
) {
    var sizeDragging by remember { mutableStateOf(false) }
    var localSize by remember(selectedItem?.id) {
        mutableFloatStateOf((selectedItem?.fontSizeSp ?: 42f).coerceIn(14f, 110f))
    }
    var lastSentSize by remember(selectedItem?.id) {
        mutableFloatStateOf((selectedItem?.fontSizeSp ?: 42f).coerceIn(14f, 110f))
    }
    LaunchedEffect(selectedItem?.fontSizeSp) {
        val target = (selectedItem?.fontSizeSp ?: 42f).coerceIn(14f, 110f)
        if (!sizeDragging || abs(target - lastSentSize) > 0.05f) {
            localSize = target
            lastSentSize = target
            sizeDragging = false
        }
    }

    var rotDragging by remember { mutableStateOf(false) }
    var localRot by remember(selectedItem?.id) {
        mutableFloatStateOf((selectedItem?.rotationDeg ?: 0f).coerceIn(-180f, 180f))
    }
    var lastSentRot by remember(selectedItem?.id) {
        mutableFloatStateOf((selectedItem?.rotationDeg ?: 0f).coerceIn(-180f, 180f))
    }
    LaunchedEffect(selectedItem?.rotationDeg) {
        val target = (selectedItem?.rotationDeg ?: 0f).coerceIn(-180f, 180f)
        if (!rotDragging || abs(target - lastSentRot) > 0.05f) {
            localRot = target
            lastSentRot = target
            rotDragging = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onAddClick,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("add_text_overlay_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Text")
            }
            if (selectedItem != null) {
                OutlinedButton(
                    onClick = { onEditItem(selectedItem) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("edit_selected_text_button")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Style & Font")
                }
            }
            items.forEach { item ->
                val isSelected = selectedItem?.id == item.id
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (isSelected) onEditItem(item) else onSelectItem(item)
                    },
                    colors = whatStickFilterChipColors(),
                    border = whatStickFilterChipBorder(isSelected),
                    label = { Text(item.text.take(14)) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove text",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onDeleteItem(item.id) }
                        )
                    }
                )
            }
        }
        if (selectedItem != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Size ${localSize.roundToInt()}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = localSize,
                    onValueChange = { newSize ->
                        if (!sizeDragging) {
                            sizeDragging = true
                            onDragStart()
                        }
                        localSize = newSize
                        lastSentSize = newSize
                        onResizeSelected(newSize)
                    },
                    onValueChangeFinished = { sizeDragging = false },
                    valueRange = 14f..110f,
                    modifier = Modifier.weight(1f)
                )
                Text("${localRot.roundToInt()}°", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = localRot,
                    onValueChange = { newAngle ->
                        if (!rotDragging) {
                            rotDragging = true
                            onDragStart()
                        }
                        localRot = newAngle
                        lastSentRot = newAngle
                        onRotateSelected(newAngle)
                    },
                    onValueChangeFinished = { rotDragging = false },
                    valueRange = -180f..180f,
                    modifier = Modifier.weight(1f)
                )
            }
        } else if (items.isNotEmpty()) {
            Text(
                "Tap any text on the canvas to select, drag to move, pinch to resize, or drag its corner handle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawToolPanel(
    colorArgb: Int,
    brushSize: Float,
    isEraser: Boolean,
    onColorSelected: (Int) -> Unit,
    onToggleEraser: () -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onClearStrokes: () -> Unit
) {
    val palette = listOf(
        Color(0xFFEF4444),
        Color(0xFF3B82F6),
        Color(0xFF10B981),
        Color(0xFFF59E0B),
        Color(0xFF8B5CF6),
        Color.White,
        Color.Black
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = isEraser,
                onClick = onToggleEraser,
                colors = whatStickFilterChipColors(),
                border = whatStickFilterChipBorder(isEraser),
                label = { Text("Eraser") },
                leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            palette.forEach { c ->
                val argb = c.toArgb()
                val selected = !isEraser && colorArgb == argb
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(argb) }
                )
            }
            TextButton(onClick = onClearStrokes) { Text("Clear") }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Brush ${brushSize.toInt()}px", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = brushSize,
                onValueChange = onBrushSizeChange,
                valueRange = 4f..44f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun applyCropRatioToDocument(
    doc: StaticStickerDocument,
    ratio: CropAspectRatio,
    srcW: Int,
    srcH: Int
): StaticStickerDocument {
    val targetRatio = ratio.ratio ?: return doc.copy(cropRatio = ratio)
    if (srcW <= 0 || srcH <= 0) return doc.copy(cropRatio = ratio)
    val srcRatio = srcW.toFloat() / srcH.toFloat()
    return if (srcRatio > targetRatio) {
        val normW = (targetRatio / srcRatio).coerceIn(0.1f, 1f)
        val margin = (1f - normW) / 2f
        doc.copy(cropRatio = ratio, cropLeft = margin, cropRight = 1f - margin, cropTop = 0f, cropBottom = 1f)
    } else {
        val normH = (srcRatio / targetRatio).coerceIn(0.1f, 1f)
        val margin = (1f - normH) / 2f
        doc.copy(cropRatio = ratio, cropLeft = 0f, cropRight = 1f, cropTop = margin, cropBottom = 1f - margin)
    }
}
