package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.StickerPackWithStickers
import com.example.media.AnimatedTransformConfig
import com.example.media.DetectedMediaType
import com.example.media.StaticStickerRenderer
import com.example.media.StickerTypefaceResolver
import com.example.media.VideoFrameExtractor
import com.example.ui.components.CheckerboardBackground
import com.example.ui.components.PackPickerSaveDialog
import com.example.ui.components.TextOverlayEditorDialog
import com.example.ui.components.whatStickFilterChipBorder
import com.example.ui.components.whatStickFilterChipColors
import com.example.ui.viewmodel.AnimatedEditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class AnimatedToolTab(val label: String) {
    TRIM("Trim & Speed"),
    CROP("Crop & Zoom"),
    TEXT("Text")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedEditorScreen(
    viewModel: AnimatedEditorViewModel,
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

    var activeTab by remember { mutableStateOf(AnimatedToolTab.TRIM) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedPackId by remember { mutableStateOf<String?>(null) }
    var currentFrameIdx by remember { mutableIntStateOf(0) }
    var renderedPreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
    var textSelected by remember { mutableStateOf(true) }

    val latestTextOverlay by rememberUpdatedState(uiState.textOverlay)

    BackHandler { onBack() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    val activeRange = remember(
        uiState.previewFrames,
        uiState.mediaType,
        uiState.trimStartMs,
        uiState.trimEndMs
    ) {
        val frames = uiState.previewFrames
        if (frames.isEmpty()) {
            emptyList()
        } else if (uiState.mediaType == DetectedMediaType.VIDEO) {
            frames
        } else {
            val selected = mutableListOf<com.example.media.AnimatedFrameBitmap>()
            var acc = 0L
            for (f in frames) {
                val end = acc + f.durationMs
                if (end > uiState.trimStartMs && acc < uiState.trimEndMs) {
                    selected += f
                }
                acc = end
            }
            selected.ifEmpty { listOf(frames.first()) }
        }
    }

    // Advance playback frame index independently so transform/stretch/text changes never reset to frame 0
    LaunchedEffect(activeRange, uiState.isPlaying, uiState.speedMultiplier) {
        if (activeRange.isEmpty() || !uiState.isPlaying) return@LaunchedEffect
        var idx = currentFrameIdx % activeRange.size
        while (isActive) {
            currentFrameIdx = idx
            val frame = activeRange[idx]
            val delayMs = (frame.durationMs / uiState.speedMultiplier).toLong().coerceIn(20L, 1000L)
            delay(delayMs)
            idx = (idx + 1) % activeRange.size
        }
    }

    // Render current frame immediately whenever frame index or transform/stretch/text config changes
    LaunchedEffect(
        activeRange,
        currentFrameIdx,
        uiState.trimStartMs,
        uiState.trimEndMs,
        uiState.speedMultiplier,
        uiState.cropCenterFill,
        uiState.zoom,
        uiState.stretchX,
        uiState.stretchY,
        uiState.offsetX,
        uiState.offsetY,
        uiState.textOverlay
    ) {
        if (activeRange.isEmpty()) return@LaunchedEffect
        val frame = activeRange[currentFrameIdx % activeRange.size]
        if (frame.bitmap.isRecycled) return@LaunchedEffect
        val config = AnimatedTransformConfig(
            startMs = uiState.trimStartMs,
            endMs = uiState.trimEndMs,
            speedMultiplier = uiState.speedMultiplier,
            cropCenterFill = uiState.cropCenterFill,
            zoom = uiState.zoom,
            stretchX = uiState.stretchX,
            stretchY = uiState.stretchY,
            offsetX = uiState.offsetX,
            offsetY = uiState.offsetY,
            textOverlay = uiState.textOverlay
        )
        val bmp = withContext(Dispatchers.Default) {
            if (frame.bitmap.isRecycled) {
                null
            } else {
                VideoFrameExtractor.renderAnimatedFrame(
                    source = frame.bitmap,
                    config = config,
                    typefaceResolver = { style, bold, italic ->
                        StickerTypefaceResolver.resolve(context, style, bold, italic)
                    }
                )
            }
        }
        if (bmp != null) {
            renderedPreviewBmp = bmp
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.mediaType == DetectedMediaType.VIDEO) "Video Sticker" else "GIF Sticker",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("animated_editor_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.togglePlaying() },
                        modifier = Modifier.testTag("animated_editor_play_pause_button")
                    ) {
                        Icon(
                            if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause preview" else "Play preview"
                        )
                    }
                    Button(
                        onClick = { showSaveDialog = true },
                        enabled = !uiState.isLoading && !uiState.isExporting && uiState.sourceDurationMs > 0L,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("animated_editor_save_button")
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
            // Central 512x512 Animated Preview Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isLoading) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Decoding frames on device...", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .widthIn(max = 380.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(24.dp)
                            )
                    ) {
                        val sizePx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                        CheckerboardBackground(modifier = Modifier.fillMaxSize(), cellSize = 14.dp)
                        renderedPreviewBmp?.let { bmp ->
                            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Animated sticker preview",
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.Medium,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Text selection border when in Text tab and overlay is selected
                        val activeText = if (activeTab == AnimatedToolTab.TEXT && textSelected) {
                            uiState.textOverlay
                        } else null
                        val selectionColor = MaterialTheme.colorScheme.primary
                        val handleFill = MaterialTheme.colorScheme.onPrimary

                        if (activeText != null) {
                            val bounds = remember(activeText) {
                                StaticStickerRenderer.measureTextBoundsNorm(
                                    item = activeText,
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
                                    listOf(
                                        Offset(left, top),
                                        Offset(left + w, top),
                                        Offset(left, top + h),
                                        Offset(left + w, top + h)
                                    ).forEach { pt ->
                                        drawCircle(color = selectionColor, radius = 5.dp.toPx(), center = pt)
                                        drawCircle(color = handleFill, radius = 2.5.dp.toPx(), center = pt)
                                    }
                                }
                            }
                        }

                        // Gesture handler: Text drag/pinch/rotate in TEXT tab, pinch-zoom & pan elsewhere
                        val gestureModifier = if (activeTab == AnimatedToolTab.TEXT) {
                            Modifier
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { pos ->
                                            val item = latestTextOverlay
                                            if (item != null) {
                                                val nx = (pos.x / sizePx).coerceIn(0f, 1f)
                                                val ny = (pos.y / sizePx).coerceIn(0f, 1f)
                                                textSelected = StaticStickerRenderer.isPointInsideText(
                                                    normX = nx,
                                                    normY = ny,
                                                    item = item,
                                                    typefaceResolver = { s, b, i ->
                                                        StickerTypefaceResolver.resolve(context, s, b, i)
                                                    }
                                                )
                                            }
                                        },
                                        onDoubleTap = { pos ->
                                            val item = latestTextOverlay
                                            val nx = (pos.x / sizePx).coerceIn(0f, 1f)
                                            val ny = (pos.y / sizePx).coerceIn(0f, 1f)
                                            val hit = item != null && StaticStickerRenderer.isPointInsideText(
                                                normX = nx,
                                                normY = ny,
                                                item = item,
                                                typefaceResolver = { s, b, i ->
                                                    StickerTypefaceResolver.resolve(context, s, b, i)
                                                }
                                            )
                                            if (hit) {
                                                textSelected = true
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
                                        val item = latestTextOverlay
                                        val nx = (down.position.x / sizePx).coerceIn(0f, 1f)
                                        val ny = (down.position.y / sizePx).coerceIn(0f, 1f)
                                        val hit = item != null && StaticStickerRenderer.isPointInsideText(
                                            normX = nx,
                                            normY = ny,
                                            item = item,
                                            typefaceResolver = { s, b, i ->
                                                StickerTypefaceResolver.resolve(context, s, b, i)
                                            }
                                        )
                                        if (hit) textSelected = true

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.none { it.pressed }) break
                                            val current = latestTextOverlay
                                            val pressedCount = event.changes.count { it.pressed }
                                            val zoom = event.calculateZoom()
                                            val pan = event.calculatePan()
                                            val rot = event.calculateRotation()
                                            if (current != null && textSelected && (hit || pressedCount >= 2)) {
                                                if (zoom != 1f || abs(rot) > 0.01f || pan != Offset.Zero) {
                                                    viewModel.moveOrRotateTextOverlay(
                                                        normX = current.normX + pan.x / sizePx,
                                                        normY = current.normY + pan.y / sizePx,
                                                        rotationDeg = current.rotationDeg + rot,
                                                        fontSizeSp = current.fontSizeSp * zoom
                                                    )
                                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                }
                                            } else if (pressedCount >= 2 || !textSelected) {
                                                if (zoom != 1f || pan != Offset.Zero) {
                                                    viewModel.applyPinchZoomAndPan(
                                                        zoomDelta = zoom,
                                                        panNormX = pan.x / sizePx,
                                                        panNormY = pan.y / sizePx
                                                    )
                                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                                }
                                            }
                                        }
                                    }
                                }
                        } else {
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
                                            panNormX = pan.x / sizePx,
                                            panNormY = pan.y / sizePx
                                        )
                                    }
                                }
                        }

                        Box(modifier = Modifier.fillMaxSize().then(gestureModifier))

                        // Zoom reset pill when zoomed or panned
                        if (abs(uiState.zoom - 1f) > 0.05f ||
                            abs(uiState.offsetX) > 0.02f ||
                            abs(uiState.offsetY) > 0.02f
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(50))
                                    .clickable { viewModel.resetTransform() }
                                    .testTag("animated_zoom_reset_badge")
                            ) {
                                Text(
                                    text = String.format(java.util.Locale.US, "%.1fx • Reset", uiState.zoom),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        // Duration pill badge
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = String.format(
                                    java.util.Locale.US,
                                    "%.1fs • %.1fx",
                                    uiState.effectiveOutputDurationMs / 1000f,
                                    uiState.speedMultiplier
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Bottom Editing Controls (Clean, uncluttered — no AI Cutout banner)
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (activeTab) {
                            AnimatedToolTab.TRIM -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (uiState.timelineThumbnails.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                        ) {
                                            Row(modifier = Modifier.fillMaxSize()) {
                                                uiState.timelineThumbnails.forEach { thumb ->
                                                    Image(
                                                        bitmap = thumb.asImageBitmap(),
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxHeight()
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    val maxDur = uiState.sourceDurationMs.coerceAtLeast(500L).toFloat()
                                    RangeSlider(
                                        value = uiState.trimStartMs.toFloat()..uiState.trimEndMs.toFloat(),
                                        onValueChange = { range ->
                                            viewModel.updateTrim(range.start.toLong(), range.endInclusive.toLong())
                                        },
                                        valueRange = 0f..maxDur,
                                        modifier = Modifier.testTag("animated_trim_slider")
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            String.format(
                                                java.util.Locale.US,
                                                "Trim: %.1fs – %.1fs (Output: %.1fs / 10.0s max)",
                                                uiState.trimStartMs / 1000f,
                                                uiState.trimEndMs / 1000f,
                                                uiState.effectiveOutputDurationMs / 1000f
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Speed,
                                            contentDescription = "Playback speed",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { spd ->
                                            val selected = uiState.speedMultiplier == spd
                                            FilterChip(
                                                selected = selected,
                                                onClick = { viewModel.setSpeed(spd) },
                                                colors = whatStickFilterChipColors(),
                                                border = whatStickFilterChipBorder(selected),
                                                label = { Text("${spd}x") },
                                                modifier = Modifier.testTag("speed_chip_${spd}")
                                            )
                                        }
                                    }
                                }
                            }

                            AnimatedToolTab.CROP -> {
                                var zoomDragging by remember { mutableStateOf(false) }
                                var localZoom by remember { mutableFloatStateOf(uiState.zoom.coerceIn(0.6f, 4.0f)) }
                                var lastSentZoom by remember { mutableFloatStateOf(uiState.zoom.coerceIn(0.6f, 4.0f)) }
                                LaunchedEffect(uiState.zoom) {
                                    val clamped = uiState.zoom.coerceIn(0.6f, 4.0f)
                                    if (!zoomDragging || abs(clamped - lastSentZoom) > 0.005f) {
                                        localZoom = clamped
                                        lastSentZoom = clamped
                                        zoomDragging = false
                                    }
                                }

                                var stretchXDragging by remember { mutableStateOf(false) }
                                var localStretchX by remember { mutableFloatStateOf(uiState.stretchX.coerceIn(0.25f, 2.5f)) }
                                var lastSentStretchX by remember { mutableFloatStateOf(uiState.stretchX.coerceIn(0.25f, 2.5f)) }
                                LaunchedEffect(uiState.stretchX) {
                                    val clamped = uiState.stretchX.coerceIn(0.25f, 2.5f)
                                    if (!stretchXDragging || abs(clamped - lastSentStretchX) > 0.002f) {
                                        localStretchX = clamped
                                        lastSentStretchX = clamped
                                        stretchXDragging = false
                                    }
                                }

                                var stretchYDragging by remember { mutableStateOf(false) }
                                var localStretchY by remember { mutableFloatStateOf(uiState.stretchY.coerceIn(0.25f, 2.5f)) }
                                var lastSentStretchY by remember { mutableFloatStateOf(uiState.stretchY.coerceIn(0.25f, 2.5f)) }
                                LaunchedEffect(uiState.stretchY) {
                                    val clamped = uiState.stretchY.coerceIn(0.25f, 2.5f)
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
                                        FilterChip(
                                            selected = uiState.cropCenterFill,
                                            onClick = { viewModel.setCropCenterFill(true) },
                                            colors = whatStickFilterChipColors(),
                                            border = whatStickFilterChipBorder(uiState.cropCenterFill),
                                            label = { Text("Fill Square") }
                                        )
                                        FilterChip(
                                            selected = !uiState.cropCenterFill,
                                            onClick = { viewModel.setCropCenterFill(false) },
                                            colors = whatStickFilterChipColors(),
                                            border = whatStickFilterChipBorder(!uiState.cropCenterFill),
                                            label = { Text("Fit Whole Frame") }
                                        )
                                        OutlinedButton(onClick = { viewModel.resetTransform() }) {
                                            Text("Reset")
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            "Zoom ${String.format(java.util.Locale.US, "%.1fx", localZoom)}",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Slider(
                                            value = localZoom,
                                            onValueChange = { z ->
                                                zoomDragging = true
                                                localZoom = z
                                                lastSentZoom = z
                                                viewModel.updateZoom(z)
                                            },
                                            onValueChangeFinished = { zoomDragging = false },
                                            valueRange = 0.6f..4.0f,
                                            modifier = Modifier.weight(1f)
                                        )
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
                                                stretchXDragging = true
                                                localStretchX = sx
                                                lastSentStretchX = sx
                                                viewModel.updateStretchX(sx)
                                            },
                                            onValueChangeFinished = { stretchXDragging = false },
                                            valueRange = 0.25f..2.5f,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("animated_horizontal_stretch_slider")
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
                                                stretchYDragging = true
                                                localStretchY = sy
                                                lastSentStretchY = sy
                                                viewModel.updateStretchY(sy)
                                            },
                                            onValueChangeFinished = { stretchYDragging = false },
                                            valueRange = 0.25f..2.5f,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("animated_vertical_stretch_slider")
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
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = { viewModel.resetStretch() },
                                            modifier = Modifier.testTag("animated_reset_stretch_button")
                                        ) {
                                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Reset Stretch")
                                        }
                                    }
                                }
                            }

                            AnimatedToolTab.TEXT -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = { showTextDialog = true },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.testTag("animated_add_text_button")
                                        ) {
                                            Icon(
                                                if (uiState.textOverlay == null) Icons.Default.Add else Icons.Default.Edit,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (uiState.textOverlay == null) "Add Text" else "Edit Text")
                                        }
                                        if (uiState.textOverlay != null) {
                                            OutlinedButton(onClick = { viewModel.setTextOverlay(null) }) {
                                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Remove")
                                            }
                                        }
                                    }
                                    uiState.textOverlay?.let { item ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("Size ${item.fontSizeSp.toInt()}", style = MaterialTheme.typography.labelSmall)
                                            Slider(
                                                value = item.fontSizeSp.coerceIn(14f, 110f),
                                                onValueChange = { newSize ->
                                                    viewModel.moveOrRotateTextOverlay(
                                                        normX = item.normX,
                                                        normY = item.normY,
                                                        rotationDeg = item.rotationDeg,
                                                        fontSizeSp = newSize
                                                    )
                                                },
                                                valueRange = 14f..110f,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text("${item.rotationDeg.toInt()}°", style = MaterialTheme.typography.labelSmall)
                                            Slider(
                                                value = item.rotationDeg.coerceIn(-180f, 180f),
                                                onValueChange = { newAngle ->
                                                    viewModel.moveOrRotateTextOverlay(
                                                        normX = item.normX,
                                                        normY = item.normY,
                                                        rotationDeg = newAngle,
                                                        fontSizeSp = item.fontSizeSp
                                                    )
                                                },
                                                valueRange = -180f..180f,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AnimatedToolTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            FilterChip(
                                selected = selected,
                                onClick = { activeTab = tab },
                                colors = whatStickFilterChipColors(),
                                border = whatStickFilterChipBorder(selected),
                                label = { Text(tab.label) },
                                leadingIcon = {
                                    val icon = when (tab) {
                                        AnimatedToolTab.TRIM -> Icons.Default.ContentCut
                                        AnimatedToolTab.CROP -> Icons.Default.Crop
                                        AnimatedToolTab.TEXT -> Icons.Default.TextFields
                                    }
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.testTag("animated_tool_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }
    }

    if (uiState.isExporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Encoding Animated WebP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Optimizing frames, timing, and file size (< 500 KB) locally on your device...",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = { uiState.exportProgress.coerceIn(0.05f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
    }

    if (showTextDialog) {
        TextOverlayEditorDialog(
            initialItem = uiState.textOverlay,
            onDismiss = { showTextDialog = false },
            onConfirm = { item ->
                viewModel.setTextOverlay(item)
                textSelected = true
                showTextDialog = false
            }
        )
    }

    if (showSaveDialog) {
        PackPickerSaveDialog(
            packs = packs,
            isAnimated = true,
            initialPackId = initialTargetPackId,
            onDismiss = { showSaveDialog = false },
            onCreateNewPackAndSave = { name, publisher, emojis ->
                showSaveDialog = false
                scope.launch {
                    val packId = onCreatePack(name, publisher, true)
                    val res = viewModel.exportAndSaveToPack(packId, emojis)
                    res.onSuccess { savedPackId = packId }
                }
            },
            onSelectPackAndSave = { packId, emojis ->
                showSaveDialog = false
                scope.launch {
                    val res = viewModel.exportAndSaveToPack(packId, emojis)
                    res.onSuccess { savedPackId = packId }
                }
            }
        )
    }

    savedPackId?.let { packId ->
        AlertDialog(
            onDismissRequest = { savedPackId = null },
            title = { Text("Animated Sticker Saved!") },
            text = { Text("Your 512×512 animated WebP sticker is ready in your pack.") },
            confirmButton = {
                Button(onClick = {
                    savedPackId = null
                    onSavedToPack(packId)
                }) {
                    Text("View Pack")
                }
            },
            dismissButton = {
                TextButton(onClick = { savedPackId = null }) { Text("Keep Editing") }
            }
        )
    }
}
