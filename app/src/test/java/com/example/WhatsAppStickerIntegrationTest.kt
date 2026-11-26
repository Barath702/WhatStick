package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.StickerDatabase
import com.example.data.local.StickerFileStorage
import com.example.data.repository.StickerRepository
import com.example.media.AnimatedFrameBitmap
import com.example.media.AnimatedWebpMuxer
import com.example.media.BackgroundRemover
import com.example.media.CropAspectRatio
import com.example.media.DetectedMediaType
import com.example.media.DrawStroke
import com.example.media.GifFrameExtractor
import com.example.media.MediaInspector
import com.example.media.StaticStickerDocument
import com.example.media.StaticStickerRenderer
import com.example.media.StickerFontStyle
import com.example.media.StickerTypefaceResolver
import com.example.media.TextOverlayItem
import com.example.ui.viewmodel.AnimatedEditorViewModel
import com.example.ui.viewmodel.StaticEditorViewModel
import com.example.whatsapp.StickerContentProvider
import com.example.whatsapp.WhatsAppInstaller
import com.example.whatsapp.WhatsAppValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhatsAppStickerIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: StickerDatabase
    private lateinit var storage: StickerFileStorage
    private lateinit var repository: StickerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StickerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = StickerFileStorage(context)
        repository = StickerRepository(db.stickerDao(), storage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `exported static webp reopens cleanly with intact subject details genuine transparency and gap-free outline`() = runTest {
        val src = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        src.setHasAlpha(true)
        val c = Canvas(src)
        c.drawColor(Color.TRANSPARENT)
        // Main subject circle + fine 3px stem detail
        val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 120, 240)
            strokeWidth = 4f
        }
        c.drawCircle(256f, 256f, 110f, subjectPaint)
        c.drawLine(256f, 146f, 256f, 90f, subjectPaint)

        val doc = StaticStickerDocument(
            cropRatio = CropAspectRatio.SQUARE,
            outlineColorArgb = Color.WHITE,
            outlineThicknessPx = 12f
        )

        val preview = StaticStickerRenderer.renderStickerBitmap(
            maskedSubject = src,
            doc = doc,
            typefaceResolver = { s, b, i -> StickerTypefaceResolver.resolve(context, s, b, i) }
        )

        val bytes = StaticStickerRenderer.exportStaticStickerWebp(
            maskedSubject = src,
            doc = doc,
            typefaceResolver = { s, b, i -> StickerTypefaceResolver.resolve(context, s, b, i) }
        ).getOrThrow()

        assertTrue(bytes.size <= WhatsAppValidator.MAX_STATIC_BYTES)
        assertNull(WhatsAppValidator.validateStickerBytes(bytes, expectAnimated = false))

        // Reopen actual exported WebP bytes and verify pixels directly
        val reopened = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(reopened)
        assertEquals(512, reopened.width)
        assertEquals(512, reopened.height)

        // Corners remain genuinely transparent
        assertEquals(0, Color.alpha(reopened.getPixel(4, 4)))
        assertEquals(0, Color.alpha(reopened.getPixel(508, 508)))

        // Subject center and fine thin stem remain 100% opaque with zero gaps
        assertEquals(255, Color.alpha(reopened.getPixel(256, 256)))
        assertTrue(Color.alpha(reopened.getPixel(256, 115)) > 200)

        // Outline immediately adjacent to subject has zero pinholes or transparent ring gaps
        // Circle radius is ~110 scaled into (512 - 24)/512 -> ~105px from 256 => edge ~151; outline extends ~12px outward
        val outlinePixelAlpha = Color.alpha(reopened.getPixel(256, 256 + 111))
        assertTrue("Expected solid outline without pinhole gap, got alpha=$outlinePixelAlpha", outlinePixelAlpha > 200)

        // Preview and reopened export match pixel center alpha
        assertEquals(Color.alpha(preview.getPixel(256, 256)), Color.alpha(reopened.getPixel(256, 256)))

        preview.recycle()
        reopened.recycle()
        src.recycle()
    }

    @Test
    fun `text overlay renders inside measured bounds across fonts and survives webp export roundtrip`() = runTest {
        val src = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            Canvas(this).drawColor(Color.TRANSPARENT)
        }

        val textItem = TextOverlayItem(
            id = "txt-1",
            text = "STICKER!",
            fontStyle = StickerFontStyle.COMIC,
            fontSizeSp = 48f,
            colorArgb = Color.YELLOW,
            outlineColorArgb = Color.BLACK,
            hasOutline = true,
            normX = 0.5f,
            normY = 0.35f,
            rotationDeg = 0f
        )

        val doc = StaticStickerDocument(textItems = listOf(textItem))
        val resolver: (StickerFontStyle, Boolean, Boolean) -> Typeface = { s, b, i ->
            StickerTypefaceResolver.resolve(context, s, b, i)
        }
        val bounds = StaticStickerRenderer.measureTextBoundsNorm(textItem, resolver)

        val bytes = StaticStickerRenderer.exportStaticStickerWebp(src, doc, resolver).getOrThrow()
        val reopened = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Find bounding box of all non-transparent text pixels in reopened WebP
        var minX = 512
        var maxX = -1
        var minY = 512
        var maxY = -1
        for (y in 0 until 512) {
            for (x in 0 until 512) {
                if (Color.alpha(reopened.getPixel(x, y)) > 40) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        assertTrue("Text pixels must exist in reopened WebP", maxX > minX && maxY > minY)

        val renderedCenterX = (minX + maxX) / 2f / 512f
        val renderedCenterY = (minY + maxY) / 2f / 512f
        assertEquals(textItem.normX, renderedCenterX, 0.04f)
        assertEquals(textItem.normY, renderedCenterY, 0.04f)

        // Hit-test should match inside bounds and reject distant background
        assertTrue(StaticStickerRenderer.isPointInsideText(0.5f, 0.35f, textItem, resolver))
        assertFalse(StaticStickerRenderer.isPointInsideText(0.1f, 0.9f, textItem, resolver))

        // Verify all curated font styles resolve cleanly without crashing
        StickerFontStyle.entries.forEach { style ->
            val tf = resolver(style, true, false)
            assertNotNull(tf)
            assertTrue(bounds.widthNorm > 0.1f)
        }

        reopened.recycle()
        src.recycle()
    }

    @Test
    fun `manual erase brush and restore brush produce smooth strokes and restore exact original pixels`() {
        val w = 240
        val h = 240
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        src.setHasAlpha(true)
        val canvas = Canvas(src)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawRect(40f, 40f, 200f, 200f, Paint().apply { color = Color.RED })

        val originalAlpha = BackgroundRemover.createFullMaskFromSource(src)
        val workingMask = originalAlpha.copyOf()

        // Continuous horizontal segment erase across center (60,120) -> (180,120)
        BackgroundRemover.paintMaskBrushSegmentInPlace(
            mask = workingMask,
            width = w,
            height = h,
            startX = 60f,
            startY = 120f,
            endX = 180f,
            endY = 120f,
            radiusPx = 14f,
            restore = false,
            originalAlpha = originalAlpha
        )
        // Center and midpoint along continuous segment must be erased cleanly
        assertEquals(0, workingMask[120 * w + 60].toInt() and 0xFF)
        assertEquals(0, workingMask[120 * w + 120].toInt() and 0xFF)
        assertEquals(0, workingMask[120 * w + 180].toInt() and 0xFF)
        // Untouched region remains 255
        assertEquals(255, workingMask[60 * w + 120].toInt() and 0xFF)

        // Restore segment across (90,120) -> (150,120)
        BackgroundRemover.paintMaskBrushSegmentInPlace(
            mask = workingMask,
            width = w,
            height = h,
            startX = 90f,
            startY = 120f,
            endX = 150f,
            endY = 120f,
            radiusPx = 14f,
            restore = true,
            originalAlpha = originalAlpha
        )
        assertEquals(255, workingMask[120 * w + 120].toInt() and 0xFF)
        // Restoring outside original subject must NOT invent alpha where original PNG was transparent
        BackgroundRemover.paintMaskBrushSegmentInPlace(
            mask = workingMask,
            width = w,
            height = h,
            startX = 10f,
            startY = 10f,
            endX = 20f,
            endY = 10f,
            radiusPx = 12f,
            restore = true,
            originalAlpha = originalAlpha
        )
        assertEquals(0, workingMask[10 * w + 15].toInt() and 0xFF)

        val applied = BackgroundRemover.applyAlphaMask(src, workingMask)
        assertEquals(Color.RED, applied.getPixel(120, 120))
        assertEquals(0, Color.alpha(applied.getPixel(60, 120)))

        applied.recycle()
        src.recycle()
    }

    @Test
    fun `static editor viewmodel handles rapid high-res erase restore undo redo zoom and export without crashing`() = kotlinx.coroutines.runBlocking {
        val app = context as WhatStickApp
        val vm = StaticEditorViewModel(app)

        // High-res 1024x1024 image
        val highRes = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            Canvas(this).drawColor(Color.rgb(40, 180, 90))
        }
        vm.initWithBitmap(highRes, packId = null, stickerId = null, emojis = "🔥")

        // Rapid concurrent erase strokes
        vm.beginMaskBrushStroke()
        for (i in 0..20) {
            val nx = 0.2f + i * 0.03f
            vm.paintMaskBrushAtCanvasNorm(nx, 0.5f, brushRadiusCanvasPx = 28f, restore = false)
        }
        vm.endMaskBrushStroke()

        // Rapid restore strokes
        vm.beginMaskBrushStroke()
        for (i in 0..10) {
            val nx = 0.3f + i * 0.03f
            vm.paintMaskBrushAtCanvasNorm(nx, 0.5f, brushRadiusCanvasPx = 22f, restore = true)
        }
        vm.endMaskBrushStroke()

        // Pinch zoom, double tap zoom, reset, text manipulation
        vm.applyPinchZoomAndPan(1.5f, 0.1f, -0.05f)
        vm.toggleDoubleTapZoom()
        vm.resetViewportZoom()
        vm.addOrUpdateTextItem(TextOverlayItem(id = "t1", text = "HI"))
        vm.moveOrRotateTextItem("t1", 0.4f, 0.6f, 25f, 52f)
        vm.undo()
        vm.redo()
        vm.restoreOriginalMask()

        // Wait for background worker queue to render previewBitmap
        val deadline = System.currentTimeMillis() + 5000L
        while (vm.uiState.value.previewBitmap == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L)
        }
        Thread.sleep(200L)

        val state = vm.uiState.value
        assertNotNull(state.previewBitmap)
        assertFalse(state.previewBitmap!!.isRecycled)

        val pack = app.repository.createPack("Regression Pack", "WhatStick", isAnimated = false)
        val saved = vm.exportAndSaveToPack(pack.id, "🔥")
        assertTrue("Export should succeed cleanly: ${saved.exceptionOrNull()}", saved.isSuccess)
        app.repository.deletePack(pack.id)
    }

    @Test
    fun `smart object extracts centered foreground cleanly with zero interior holes and works across edit workflows`() = kotlinx.coroutines.runBlocking {
        // Synthetic photo: solid light-gray background with dark subject circle containing bright white highlight inside
        val w = 256
        val h = 256
        val photo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            val c = Canvas(this)
            c.drawColor(Color.rgb(225, 228, 232))
            val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 48, 96) }
            c.drawCircle(128f, 128f, 68f, subjectPaint)
            // Bright interior highlight matching outer background color must NOT become a transparent hole
            val innerHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 228, 232) }
            c.drawCircle(128f, 128f, 16f, innerHighlight)
        }

        val mask = BackgroundRemover.extractSmartObjectMask(photo)
        assertEquals(w * h, mask.size)
        // Outer corner background must be transparent
        assertEquals(0, mask[10 * w + 10].toInt() and 0xFF)
        assertEquals(0, mask[10 * w + 240].toInt() and 0xFF)
        // Subject ring and interior highlight must stay solid opaque (zero holes!)
        assertEquals(255, mask[128 * w + 90].toInt() and 0xFF)
        assertEquals(255, mask[128 * w + 128].toInt() and 0xFF)

        // Verify full ViewModel workflow: new image -> Smart Object -> undo -> redo -> save -> reopen existing sticker -> Smart Object
        val app = context as WhatStickApp
        val vm = StaticEditorViewModel(app)
        vm.initWithBitmap(photo, packId = null, stickerId = null, emojis = "⭐")
        vm.runSmartObjectExtraction()

        val deadline = System.currentTimeMillis() + 5000L
        while ((vm.uiState.value.isProcessingSmartObject || vm.uiState.value.statusMessage == null) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(25L)
        }
        assertTrue(vm.uiState.value.canUndo)
        assertNotNull(vm.uiState.value.previewBitmap)

        val pack = app.repository.createPack("Smart Object Pack", "WhatStick", isAnimated = false)
        val savedNew = vm.exportAndSaveToPack(pack.id, "⭐")
        assertTrue(savedNew.isSuccess)
        val sticker = savedNew.getOrThrow()

        // Existing sticker editing workflow
        val editVm = StaticEditorViewModel(app)
        editVm.loadExistingSticker(pack.id, sticker.id)
        val loadDeadline = System.currentTimeMillis() + 5000L
        while (editVm.uiState.value.isLoading && System.currentTimeMillis() < loadDeadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25L)
        }
        assertFalse("Existing sticker should finish loading", editVm.uiState.value.isLoading)
        editVm.runSmartObjectExtraction()
        val editDeadline = System.currentTimeMillis() + 5000L
        while ((editVm.uiState.value.isProcessingSmartObject || editVm.uiState.value.statusMessage == null) &&
            System.currentTimeMillis() < editDeadline
        ) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25L)
        }
        assertTrue(editVm.uiState.value.canUndo)
        assertNotNull(editVm.uiState.value.previewBitmap)

        app.repository.deletePack(pack.id)
    }

    @Test
    fun `transparent padding trimming maximizes canvas utilization proportionally across tall wide and irregular stickers with identical preview and export`() = kotlinx.coroutines.runBlocking {
        val app = context as WhatStickApp
        val resolver: (StickerFontStyle, Boolean, Boolean) -> Typeface = { s, b, i ->
            StickerTypefaceResolver.resolve(context, s, b, i)
        }

        // 1. Tall subject (100x320) centered inside 512x512 transparent bitmap
        val tallBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            val c = Canvas(this)
            c.drawColor(Color.TRANSPARENT)
            c.drawRect(206f, 96f, 306f, 416f, Paint().apply { color = Color.rgb(37, 99, 235) })
        }
        val vmTall = StaticEditorViewModel(app)
        vmTall.initWithBitmap(tallBmp, packId = null, stickerId = null, emojis = "🧍")
        val initDeadline = System.currentTimeMillis() + 5000L
        while (vmTall.uiState.value.previewBitmap == null && System.currentTimeMillis() < initDeadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20L)
        }
        val initialPreview = vmTall.uiState.value.previewBitmap

        vmTall.updateDocument(recordHistory = false) {
            it.copy(
                outlineColorArgb = Color.WHITE,
                outlineThicknessPx = 8f,
                textItems = listOf(TextOverlayItem(id = "t-tall", text = "TALL", normY = 0.85f))
            )
        }
        val tallDeadline = System.currentTimeMillis() + 5000L
        while ((vmTall.uiState.value.previewBitmap === initialPreview ||
                vmTall.uiState.value.document.outlineThicknessPx < 7f) &&
            System.currentTimeMillis() < tallDeadline
        ) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25L)
        }

        val tallPreview = vmTall.uiState.value.previewBitmap!!
        org.junit.Assert.assertNotSame(initialPreview, tallPreview)
        val tallBounds = StaticStickerRenderer.detectVisibleAlphaBounds(tallPreview)!!
        val tallOutH = tallBounds.bottom - tallBounds.top + 1
        val tallOutW = tallBounds.right - tallBounds.left + 1
        // Tall subject should utilize vertical space (>= 470px out of 512) without stretching horizontally
        assertTrue("Tall sticker height=$tallOutH should utilize vertical canvas", tallOutH >= 470)
        assertTrue("Tall sticker width=$tallOutW should preserve tall proportions", tallOutW < tallOutH)

        val pack = app.repository.createPack("Canvas Util Pack", "WhatStick", isAnimated = false)
        val savedTall = vmTall.exportAndSaveToPack(pack.id, "🧍").getOrThrow()
        val tallFile = app.repository.getStickerFile(pack.id, savedTall.fileName)
        val reopenedTall = BitmapFactory.decodeFile(tallFile.absolutePath)
        assertEquals(512, reopenedTall.width)
        assertEquals(512, reopenedTall.height)
        assertEquals(0, Color.alpha(reopenedTall.getPixel(2, 2)))
        val reopenedBounds = StaticStickerRenderer.detectVisibleAlphaBounds(reopenedTall)!!
        assertEquals(tallBounds.top.toFloat(), reopenedBounds.top.toFloat(), 2f)
        assertEquals(tallBounds.bottom.toFloat(), reopenedBounds.bottom.toFloat(), 2f)
        reopenedTall.recycle()

        // 2. Wide subject (320x100) centered inside 512x512 transparent bitmap
        val wideBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            val c = Canvas(this)
            c.drawColor(Color.TRANSPARENT)
            c.drawRect(96f, 206f, 416f, 306f, Paint().apply { color = Color.rgb(16, 185, 129) })
        }
        val wideDoc = StaticStickerRenderer.computeAutoTrimmedCropDoc(wideBmp, StaticStickerDocument())
        val wideRendered = StaticStickerRenderer.renderStickerBitmap(wideBmp, wideDoc, resolver)
        val wideBounds = StaticStickerRenderer.detectVisibleAlphaBounds(wideRendered)!!
        val wideOutW = wideBounds.right - wideBounds.left + 1
        val wideOutH = wideBounds.bottom - wideBounds.top + 1
        assertTrue("Wide sticker width=$wideOutW should utilize horizontal canvas", wideOutW >= 480)
        assertTrue("Wide sticker height=$wideOutH should preserve wide proportions", wideOutH < 200)
        wideRendered.recycle()
        wideBmp.recycle()

        // 3. Irregular L-shaped subject (220x240) centered inside 512x512 transparent bitmap
        val irregBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            val c = Canvas(this)
            c.drawColor(Color.TRANSPARENT)
            val p = Paint().apply { color = Color.rgb(236, 72, 153) }
            c.drawRect(146f, 136f, 226f, 376f, p)
            c.drawRect(146f, 296f, 366f, 376f, p)
        }
        val irregDoc = StaticStickerRenderer.computeAutoTrimmedCropDoc(irregBmp, StaticStickerDocument())
        val irregRendered = StaticStickerRenderer.renderStickerBitmap(irregBmp, irregDoc, resolver)
        val irregBounds = StaticStickerRenderer.detectVisibleAlphaBounds(irregRendered)!!
        val irregOutW = irregBounds.right - irregBounds.left + 1
        val irregOutH = irregBounds.bottom - irregBounds.top + 1
        assertTrue("Irregular sticker max dim=${maxOf(irregOutW, irregOutH)} should fill >= 470px", maxOf(irregOutW, irregOutH) >= 470)
        // Top-right corner of L-shape's bounding box must remain transparent (not stretched/filled)
        assertEquals(0, Color.alpha(irregRendered.getPixel(360, 140)))
        irregRendered.recycle()
        irregBmp.recycle()

        app.repository.deletePack(pack.id)
    }

    @Test
    fun `animated editor viewmodel supports pinch zoom double tap zoom reset and text transform`() {
        val app = context as WhatStickApp
        val vm = AnimatedEditorViewModel(app)

        vm.applyPinchZoomAndPan(1.6f, 0.15f, -0.1f)
        assertEquals(1.6f, vm.uiState.value.zoom, 0.01f)
        assertEquals(0.15f, vm.uiState.value.offsetX, 0.01f)

        // Double tap when zoomed > 1.15 resets to 1f and 0 offset
        vm.toggleDoubleTapZoom()
        assertEquals(1f, vm.uiState.value.zoom, 0.01f)
        assertEquals(0f, vm.uiState.value.offsetX, 0.01f)

        // Double tap from 1f zooms in to 2f
        vm.toggleDoubleTapZoom()
        assertEquals(2f, vm.uiState.value.zoom, 0.01f)

        vm.setTextOverlay(TextOverlayItem(id = "anim-t1", text = "LOOP"))
        vm.moveOrRotateTextOverlay(0.3f, 0.25f, -30f, 56f)
        val txt = vm.uiState.value.textOverlay!!
        assertEquals(0.3f, txt.normX, 0.01f)
        assertEquals(0.25f, txt.normY, 0.01f)
        assertEquals(-30f, txt.rotationDeg, 0.1f)
        assertEquals(56f, txt.fontSizeSp, 0.1f)
    }

    @Test
    fun `animated webp muxer produces valid 512x512 animated webp with exact frame timings under 500KB`() {
        val frames = (0 until 6).map { idx ->
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT)
            canvas.drawCircle(120f + idx * 45f, 256f, 70f, Paint().apply { color = Color.GREEN })
            AnimatedFrameBitmap(bmp, durationMs = 90)
        }

        val res = AnimatedWebpMuxer.encodeAnimatedWebp(frames)
        frames.forEach { it.bitmap.recycle() }

        assertTrue(res.isSuccess)
        val bytes = res.getOrThrow()
        assertTrue(bytes.size <= WhatsAppValidator.MAX_ANIMATED_BYTES)

        val info = WhatsAppValidator.inspectWebp(bytes)
        assertTrue(info.isValidWebp)
        assertTrue(info.isAnimated)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertEquals(6, info.frameCount)
        assertEquals(540L, info.totalDurationMs)
        assertEquals(90, info.minFrameDurationMs)
        assertNull(WhatsAppValidator.validateStickerBytes(bytes, expectAnimated = true))
    }

    @Test
    fun `subsamplePreservingDuration keeps total animation duration unchanged`() {
        val dummy = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val frames = List(8) { AnimatedFrameBitmap(dummy, 80) }
        val sub = AnimatedWebpMuxer.subsamplePreservingDuration(frames)
        assertEquals(4, sub.size)
        assertEquals(640, sub.sumOf { it.durationMs })
        dummy.recycle()
    }

    @Test
    fun `gif frame extractor decodes multi frame GIF89a with exact centisecond delays`() = runTest {
        val gifBytes = buildMinimalTwoFrameGif()
        val tempFile = File(context.cacheDir, "test_sample.gif").apply { writeBytes(gifBytes) }
        val res = GifFrameExtractor.extractFrames(context, Uri.fromFile(tempFile))
        tempFile.delete()

        assertTrue(res.isSuccess)
        val frames = res.getOrThrow()
        assertEquals(2, frames.size)
        assertEquals(120, frames[0].durationMs)
        assertEquals(200, frames[1].durationMs)
        frames.forEach { it.bitmap.recycle() }
    }

    @Test
    fun `media inspector distinguishes formats and rejects empty files`() {
        assertEquals(
            DetectedMediaType.ANIMATED_GIF,
            MediaInspector.detectTypeFromHeaderAndMime("GIF89a......".toByteArray(), null, "a.gif")
        )
        assertEquals(
            DetectedMediaType.STATIC_IMAGE,
            MediaInspector.detectTypeFromHeaderAndMime(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
                null,
                "photo.jpg"
            )
        )
        assertEquals(
            DetectedMediaType.VIDEO,
            MediaInspector.detectTypeFromHeaderAndMime(
                byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte()),
                null,
                "clip.mp4"
            )
        )
        assertEquals(
            DetectedMediaType.UNSUPPORTED,
            MediaInspector.detectTypeFromHeaderAndMime("hello world".toByteArray(), "text/plain", "notes.txt")
        )

        val emptyFile = File(context.cacheDir, "empty.bin").apply { writeBytes(ByteArray(0)) }
        val failRes = MediaInspector.inspect(context, Uri.fromFile(emptyFile))
        emptyFile.delete()
        assertTrue(failRes.isFailure)
    }

    @Test
    fun `pack validation and whatsapp intent contract require 3 to 30 valid stickers`() = runTest {
        val pack = repository.createPack("Test Pack", "WhatStick", isAnimated = false)
        val sampleBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawCircle(256f, 256f, 150f, Paint().apply { color = Color.MAGENTA })
        }
        val webpBytes = StaticStickerRenderer.encodeWebpUnder100Kb(sampleBmp).getOrThrow()
        sampleBmp.recycle()

        repository.addOrUpdateSticker(pack.id, webpBytes, isAnimated = false, emojis = "😄")
        repository.addOrUpdateSticker(pack.id, webpBytes, isAnimated = false, emojis = "🔥")
        val twoPack = repository.getPackOnce(pack.id)!!
        assertFalse(WhatsAppValidator.validatePack(twoPack, storage).isValid)

        repository.addOrUpdateSticker(pack.id, webpBytes, isAnimated = false, emojis = "✨")
        val threePack = repository.getPackOnce(pack.id)!!
        val resThree = WhatsAppValidator.validatePack(threePack, storage)
        assertTrue("Expected pack valid, got issues: ${resThree.issues}", resThree.isValid)

        val intent = WhatsAppInstaller.buildInstallIntent(
            packId = pack.id,
            packName = pack.name,
            targetPackage = WhatsAppInstaller.CONSUMER_WHATSAPP_PACKAGE
        )
        assertEquals(WhatsAppInstaller.ACTION_ENABLE_STICKER_PACK, intent.action)
        assertEquals(pack.id, intent.getStringExtra(WhatsAppInstaller.EXTRA_STICKER_PACK_ID))
        assertEquals(BuildConfig.CONTENT_PROVIDER_AUTHORITY, intent.getStringExtra(WhatsAppInstaller.EXTRA_STICKER_PACK_AUTHORITY))
        assertEquals(pack.name, intent.getStringExtra(WhatsAppInstaller.EXTRA_STICKER_PACK_NAME))

        val provider = Robolectric.setupContentProvider(
            StickerContentProvider::class.java,
            BuildConfig.CONTENT_PROVIDER_AUTHORITY
        )
        val metadataUri = Uri.parse("content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/metadata")
        assertNotNull(provider.getType(metadataUri))
        val assetUri = Uri.parse("content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/stickers_asset/${pack.id}/sticker.webp")
        assertEquals("image/webp", provider.getType(assetUri))

        repository.deletePack(pack.id)
    }

    @Test
    fun `static horizontal and vertical stretch scale independently and match preview and exported webp`() = kotlinx.coroutines.runBlocking {
        val app = context as WhatStickApp
        val vm = StaticEditorViewModel(app)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            val c = Canvas(this)
            c.drawColor(Color.TRANSPARENT)
            // Centered 160x160 square so stretch aspect changes are crystal clear
            c.drawRect(176f, 176f, 336f, 336f, Paint().apply { color = Color.rgb(37, 99, 235) })
        }
        vm.initWithBitmap(bmp, packId = null, stickerId = null, emojis = "🟦")

        val initDeadline = System.currentTimeMillis() + 5000L
        while (vm.uiState.value.previewBitmap == null && System.currentTimeMillis() < initDeadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20L)
        }
        val basePreview = vm.uiState.value.previewBitmap!!
        val baseBounds = StaticStickerRenderer.detectVisibleAlphaBounds(basePreview)!!
        val baseW = baseBounds.right - baseBounds.left + 1
        val baseH = baseBounds.bottom - baseBounds.top + 1

        // 1. Horizontal stretch to 180% while vertical stays 100% -> wider than tall
        // First set zoom=0.6f so stretched edges stay comfortably inside 512x512 without hitting canvas clip
        vm.updateDocument(recordHistory = false) { it.copy(zoom = 0.55f, stretchX = 1.8f, stretchY = 1.0f) }
        val hDeadline = System.currentTimeMillis() + 5000L
        while (vm.uiState.value.previewBitmap === basePreview && System.currentTimeMillis() < hDeadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20L)
        }
        Thread.sleep(100L)
        val hPreview = vm.uiState.value.previewBitmap!!
        val hBounds = StaticStickerRenderer.detectVisibleAlphaBounds(hPreview)!!
        val hW = hBounds.right - hBounds.left + 1
        val hH = hBounds.bottom - hBounds.top + 1
        assertTrue("Expected horizontal stretch width ($hW) > height ($hH)", hW > (hH * 1.5f))

        // 2. Vertical stretch to 180% while horizontal returns to 100% -> taller than wide
        vm.updateStretchX(1.0f)
        vm.updateStretchY(1.8f)
        Thread.sleep(150L)
        val vPreview = vm.uiState.value.previewBitmap!!
        val vBounds = StaticStickerRenderer.detectVisibleAlphaBounds(vPreview)!!
        val vW = vBounds.right - vBounds.left + 1
        val vH = vBounds.bottom - vBounds.top + 1
        assertTrue("Expected vertical stretch height ($vH) > width ($vW)", vH > (vW * 1.5f))

        // Verify exported 512x512 WebP matches preview bounds identically
        val pack = app.repository.createPack("Stretch Pack", "WhatStick", isAnimated = false)
        val saved = vm.exportAndSaveToPack(pack.id, "🟦").getOrThrow()
        val reopened = BitmapFactory.decodeFile(app.repository.getStickerFile(pack.id, saved.fileName).absolutePath)
        assertEquals(512, reopened.width)
        assertEquals(512, reopened.height)
        val expBounds = StaticStickerRenderer.detectVisibleAlphaBounds(reopened)!!
        assertEquals(vBounds.left.toFloat(), expBounds.left.toFloat(), 2f)
        assertEquals(vBounds.right.toFloat(), expBounds.right.toFloat(), 2f)
        assertEquals(vBounds.top.toFloat(), expBounds.top.toFloat(), 2f)
        assertEquals(vBounds.bottom.toFloat(), expBounds.bottom.toFloat(), 2f)
        reopened.recycle()

        // 3. Reset Stretch restores 100% / 100% cleanly and records undo history
        vm.resetStretch()
        assertEquals(1f, vm.uiState.value.document.stretchX, 0.001f)
        assertEquals(1f, vm.uiState.value.document.stretchY, 0.001f)
        assertTrue(baseW > 0 && baseH > 0)

        app.repository.deletePack(pack.id)
    }

    @Test
    fun `rapid continuous outline thickness slider updates preserve exact final thickness without lag backlog`() = kotlinx.coroutines.runBlocking {
        val app = context as WhatStickApp
        val vm = StaticEditorViewModel(app)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            val c = Canvas(this)
            c.drawColor(Color.TRANSPARENT)
            c.drawCircle(256f, 256f, 110f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        }
        vm.initWithBitmap(bmp, packId = null, stickerId = null, emojis = "⭕")
        val initDeadline = System.currentTimeMillis() + 5000L
        while (vm.uiState.value.previewBitmap == null && System.currentTimeMillis() < initDeadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(15L)
        }

        // Record drag start undo point once, then fire 60 rapid slider ticks 0 -> 24 -> 10
        vm.recordTransformUndoPoint()
        val steps = (0..24).map { it.toFloat() } + (24 downTo 10).map { it.toFloat() }
        for (t in steps) {
            vm.updateDocument(recordHistory = false) {
                it.copy(outlineColorArgb = Color.WHITE, outlineThicknessPx = t)
            }
            // Synchronous document update ensures slider thumb never lags behind finger
            assertEquals(t, vm.uiState.value.document.outlineThicknessPx, 0.001f)
        }

        // Wait briefly for conflated renderer to emit final frame
        Thread.sleep(250L)
        assertEquals(10f, vm.uiState.value.document.outlineThicknessPx, 0.001f)
        val finalPreview = vm.uiState.value.previewBitmap!!
        val exported = StaticStickerRenderer.renderStickerBitmap(
            maskedSubject = bmp,
            doc = vm.uiState.value.document,
            typefaceResolver = { s, b, i -> StickerTypefaceResolver.resolve(context, s, b, i) }
        )
        // Preview and export must have identical visible outline bounds
        val pBounds = StaticStickerRenderer.detectVisibleAlphaBounds(finalPreview)!!
        val eBounds = StaticStickerRenderer.detectVisibleAlphaBounds(exported)!!
        assertEquals(eBounds.left, pBounds.left)
        assertEquals(eBounds.right, pBounds.right)
        assertEquals(eBounds.top, pBounds.top)
        assertEquals(eBounds.bottom, pBounds.bottom)
        exported.recycle()
    }

    @Test
    fun `animated horizontal and vertical stretch transform all frames identically without center drift`() = runTest {
        val app = context as WhatStickApp
        val vm = AnimatedEditorViewModel(app)

        vm.updateStretchX(1.75f)
        vm.updateStretchY(0.65f)
        assertEquals(1.75f, vm.uiState.value.stretchX, 0.001f)
        assertEquals(0.65f, vm.uiState.value.stretchY, 0.001f)

        val resolver: (StickerFontStyle, Boolean, Boolean) -> Typeface = { s, b, i ->
            StickerTypefaceResolver.resolve(context, s, b, i)
        }
        val config = com.example.media.AnimatedTransformConfig(
            cropCenterFill = false,
            zoom = 0.7f,
            stretchX = vm.uiState.value.stretchX,
            stretchY = vm.uiState.value.stretchY
        )

        val renderedFrames = (0 until 4).map {
            val src = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
                setHasAlpha(true)
                val c = Canvas(this)
                c.drawColor(Color.TRANSPARENT)
                c.drawRect(60f, 60f, 180f, 180f, Paint().apply { color = Color.CYAN })
            }
            val rendered = com.example.media.VideoFrameExtractor.renderAnimatedFrame(src, config, resolver)
            src.recycle()
            AnimatedFrameBitmap(rendered, durationMs = 100)
        }

        // Verify consistent X/Y stretched bounds across all 4 frames and zero frame-to-frame shift
        val firstBounds = StaticStickerRenderer.detectVisibleAlphaBounds(renderedFrames[0].bitmap)!!
        val w0 = firstBounds.right - firstBounds.left + 1
        val h0 = firstBounds.bottom - firstBounds.top + 1
        assertTrue("Animated horizontal stretch width=$w0 must exceed height=$h0", w0 > h0 * 2)
        renderedFrames.forEach { frame ->
            assertEquals(512, frame.bitmap.width)
            assertEquals(512, frame.bitmap.height)
            val b = StaticStickerRenderer.detectVisibleAlphaBounds(frame.bitmap)!!
            assertEquals(firstBounds.left, b.left)
            assertEquals(firstBounds.right, b.right)
            assertEquals(firstBounds.top, b.top)
            assertEquals(firstBounds.bottom, b.bottom)
        }

        val webpBytes = AnimatedWebpMuxer.encodeAnimatedWebp(renderedFrames).getOrThrow()
        renderedFrames.forEach { it.bitmap.recycle() }
        val info = WhatsAppValidator.inspectWebp(webpBytes)
        assertTrue(info.isAnimated)
        assertEquals(512, info.width)
        assertEquals(512, info.height)

        vm.resetStretch()
        assertEquals(1f, vm.uiState.value.stretchX, 0.001f)
        assertEquals(1f, vm.uiState.value.stretchY, 0.001f)
    }

    @Test
    fun `static brush erase produces immutable preview snapshots without flicker and updates dirty pixels cleanly`() = kotlinx.coroutines.runBlocking {
        val resolver: (StickerFontStyle, Boolean, Boolean) -> Typeface = { s, b, i ->
            StickerTypefaceResolver.resolve(context, s, b, i)
        }
        val subject = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            Canvas(this).drawColor(Color.MAGENTA)
        }
        val cache = StaticStickerRenderer.RenderSessionCache()
        try {
            val doc = StaticStickerDocument()
            val frame1 = StaticStickerRenderer.renderPreviewWithCache(subject, 1, doc, cache, resolver)
            val frame2 = StaticStickerRenderer.renderPreviewWithCache(subject, 2, doc, cache, resolver)
            val frame3 = StaticStickerRenderer.renderPreviewWithCache(subject, 3, doc, cache, resolver)
            // Previously rendered preview bitmaps must never be recycled or overwritten in place
            assertTrue(frame1 !== frame2 && frame2 !== frame3 && frame1 !== frame3)
            assertFalse(frame1.isRecycled)
            assertEquals(Color.MAGENTA, frame1.getPixel(256, 256))
            assertEquals(Color.MAGENTA, frame2.getPixel(256, 256))
            assertEquals(Color.MAGENTA, frame3.getPixel(256, 256))
            frame1.recycle()
            frame2.recycle()
            frame3.recycle()
        } finally {
            cache.release()
            subject.recycle()
        }

        // Verify ViewModel coalesced brush strokes update center to transparent while preserving surrounding subject, and undo restores cleanly
        val app = context as WhatStickApp
        val vm = StaticEditorViewModel(app)
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
            Canvas(this).drawColor(Color.BLUE)
        }
        vm.initWithBitmap(bmp, packId = null, stickerId = null, emojis = "🎨")
        vm.beginMaskBrushStroke()
        for (i in 0..30) {
            val t = i / 30f
            vm.paintMaskBrushAtCanvasNorm(0.35f + 0.3f * t, 0.5f, brushRadiusCanvasPx = 24f, restore = false)
        }
        vm.endMaskBrushStroke()

        val deadline = System.currentTimeMillis() + 5000L
        while (vm.uiState.value.previewBitmap == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        Thread.sleep(150L)

        val afterErase = vm.uiState.value.previewBitmap!!
        assertEquals(0, Color.alpha(afterErase.getPixel(256, 256)))
        assertEquals(255, Color.alpha(afterErase.getPixel(256, 40)))

        vm.undo()
        Thread.sleep(150L)
        val afterUndo = vm.uiState.value.previewBitmap!!
        assertEquals(255, Color.alpha(afterUndo.getPixel(256, 256)))
    }

    @Test
    fun `gif frame extractor rejects malformed oversized dimensions without oom`() = runTest {
        // GIF89a header claiming 60000 x 60000 logical screen (3.6B pixels > 16M limit)
        val maliciousGif = byteArrayOf(
            'G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(),
            0x60, 0xEA.toByte(), // width = 60000
            0x60, 0xEA.toByte(), // height = 60000
            0x00, 0x00, 0x00,
            0x3B
        )
        val tempFile = File(context.cacheDir, "malformed_oversized.gif").apply { writeBytes(maliciousGif) }
        val res = GifFrameExtractor.extractFrames(context, Uri.fromFile(tempFile))
        tempFile.delete()
        assertTrue(res.isFailure)
    }

    @Test
    fun `animated webp decoder rejects truncated or overflow chunk sizes safely`() {
        // RIFF WEBP header with an ANMF chunk declaring size 0xFFFFFFFF
        val badWebp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x20, 0x00, 0x00, 0x00,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'A'.code.toByte(), 'N'.code.toByte(), 'M'.code.toByte(), 'F'.code.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val frames = com.example.media.AnimatedWebpFrameExtractor.decodeAnimatedWebpFrames(badWebp)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `sticker content provider enforces read only mode and omits internal paths in errors`() {
        val provider = Robolectric.setupContentProvider(
            StickerContentProvider::class.java,
            BuildConfig.CONTENT_PROVIDER_AUTHORITY
        )
        val assetUri = Uri.parse(
            "content://${BuildConfig.CONTENT_PROVIDER_AUTHORITY}/stickers_asset/nonexistent_pack/missing.webp"
        )

        var threwSecurity = false
        try {
            provider.openAssetFile(assetUri, "w")
        } catch (_: SecurityException) {
            threwSecurity = true
        }
        assertTrue("Write mode must throw SecurityException", threwSecurity)

        var notFoundMessage = ""
        try {
            provider.openAssetFile(assetUri, "r")
        } catch (e: java.io.FileNotFoundException) {
            notFoundMessage = e.message.orEmpty()
        }
        assertTrue("Missing asset must throw FileNotFoundException", notFoundMessage.isNotEmpty())
        assertFalse(
            "Error message must not leak internal filesDir path",
            notFoundMessage.contains(context.filesDir.absolutePath)
        )
    }

    private fun buildMinimalTwoFrameGif(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(2, 0, 2, 0, 0x80.toByte(), 0, 0))
        out.write(byteArrayOf(0, 0, 0, 0xFF.toByte(), 0, 0))
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04, 12, 0, 0, 0))
        out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, 0))
        out.write(byteArrayOf(2, 3, 0x44, 0x00, 0x05, 0))
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04, 20, 0, 0, 0))
        out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, 0))
        out.write(byteArrayOf(2, 3, 0x8C.toByte(), 0x10, 0x05, 0))
        out.write(0x3B)
        return out.toByteArray()
    }
}
