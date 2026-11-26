package com.example.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import com.example.whatsapp.WhatsAppValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class CropAspectRatio(val label: String, val ratio: Float?) {
    SQUARE("1:1", 1f),
    LANDSCAPE("4:3", 4f / 3f),
    PORTRAIT("3:4", 3f / 4f),
    FREE("Free", null)
}

enum class StickerFontStyle(val label: String, val previewSample: String) {
    SANS("Default", "Clean Sans"),
    COMIC("Bold", "BOLD POP"),
    DISPLAY("Rounded", "Smooth Round"),
    SCRIPT("Handwritten", "Handwritten"),
    SERIF("Serif", "Classic Serif"),
    MONO("Modern", "MODERN")
}

enum class StickerTextAlign {
    LEFT, CENTER, RIGHT
}

data class TextOverlayItem(
    val id: String,
    val text: String,
    val fontStyle: StickerFontStyle = StickerFontStyle.SANS,
    val fontSizeSp: Float = 42f,
    val colorArgb: Int = Color.WHITE,
    val outlineColorArgb: Int = Color.BLACK,
    val hasOutline: Boolean = true,
    val isBold: Boolean = true,
    val isItalic: Boolean = false,
    val alignment: StickerTextAlign = StickerTextAlign.CENTER,
    val normX: Float = 0.5f,
    val normY: Float = 0.78f,
    val rotationDeg: Float = 0f
)

data class TextBoundsNorm(
    val centerX: Float,
    val centerY: Float,
    val widthNorm: Float,
    val heightNorm: Float,
    val rotationDeg: Float
)

data class DrawStroke(
    val points: List<Pair<Float, Float>>, // normalized 0..1 on 512x512 canvas
    val colorArgb: Int,
    val widthPx: Float,
    val isEraser: Boolean = false
)

data class StaticStickerDocument(
    val cropRatio: CropAspectRatio = CropAspectRatio.SQUARE,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val zoom: Float = 1f,
    val stretchX: Float = 1f,
    val stretchY: Float = 1f,
    val offsetX: Float = 0f, // normalized to 512 canvas
    val offsetY: Float = 0f,
    val rotationDeg: Float = 0f,
    val backgroundColorArgb: Int? = null, // null = transparent
    val outlineColorArgb: Int = Color.WHITE,
    val outlineThicknessPx: Float = 0f,
    val strokes: List<DrawStroke> = emptyList(),
    val textItems: List<TextOverlayItem> = emptyList()
)

data class SubjectTransformParams(
    val srcCropLeft: Int,
    val srcCropTop: Int,
    val srcCropWidth: Int,
    val srcCropHeight: Int,
    val scaleX: Float,
    val scaleY: Float,
    val centerX: Float,
    val centerY: Float,
    val rotationDeg: Float
) {
    val scale: Float get() = min(scaleX, scaleY)
}

data class AlphaContentBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

object StaticStickerRenderer {

    const val CANVAS_SIZE = 512

    fun detectVisibleAlphaBounds(
        bitmap: Bitmap,
        alphaThreshold: Int = 8
    ): AlphaContentBounds? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val a = (pixels[row + x] ushr 24) and 0xFF
                if (a > alphaThreshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX >= minX && maxY >= minY) {
            AlphaContentBounds(minX, minY, maxX, maxY)
        } else {
            null
        }
    }

    fun computeAutoTrimmedCropDoc(
        bitmap: Bitmap,
        currentDoc: StaticStickerDocument,
        minPaddingFraction: Float = 0.04f
    ): StaticStickerDocument {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 2 || h <= 2) return currentDoc
        val bounds = detectVisibleAlphaBounds(bitmap) ?: return currentDoc

        val padLeftFrac = bounds.left.toFloat() / w.toFloat()
        val padRightFrac = (w - 1 - bounds.right).toFloat() / w.toFloat()
        val padTopFrac = bounds.top.toFloat() / h.toFloat()
        val padBottomFrac = (h - 1 - bounds.bottom).toFloat() / h.toFloat()

        val maxPad = max(max(padLeftFrac, padRightFrac), max(padTopFrac, padBottomFrac))
        if (maxPad <= minPaddingFraction) return currentDoc

        var cropL = (bounds.left.toFloat() / w.toFloat()).coerceIn(0f, 0.94f)
        var cropT = (bounds.top.toFloat() / h.toFloat()).coerceIn(0f, 0.94f)
        var cropR = ((bounds.right + 1).toFloat() / w.toFloat()).coerceIn(cropL + 0.05f, 1f)
        var cropB = ((bounds.bottom + 1).toFloat() / h.toFloat()).coerceIn(cropT + 0.05f, 1f)

        if (cropR - cropL < 0.05f) {
            val mid = ((cropL + cropR) * 0.5f).coerceIn(0.03f, 0.97f)
            cropL = (mid - 0.025f).coerceAtLeast(0f)
            cropR = (mid + 0.025f).coerceAtMost(1f)
        }
        if (cropB - cropT < 0.05f) {
            val mid = ((cropT + cropB) * 0.5f).coerceIn(0.03f, 0.97f)
            cropT = (mid - 0.025f).coerceAtLeast(0f)
            cropB = (mid + 0.025f).coerceAtMost(1f)
        }

        return currentDoc.copy(
            cropRatio = CropAspectRatio.FREE,
            cropLeft = cropL,
            cropTop = cropT,
            cropRight = cropR,
            cropBottom = cropB,
            zoom = 1f,
            offsetX = 0f,
            offsetY = 0f
        )
    }

    fun computeSubjectTransform(
        srcWidth: Int,
        srcHeight: Int,
        doc: StaticStickerDocument
    ): SubjectTransformParams {
        val sx = (doc.cropLeft.coerceIn(0f, 0.95f) * srcWidth).toInt().coerceIn(0, max(0, srcWidth - 1))
        val sy = (doc.cropTop.coerceIn(0f, 0.95f) * srcHeight).toInt().coerceIn(0, max(0, srcHeight - 1))
        val sw = ((doc.cropRight.coerceIn(0.05f, 1f) - doc.cropLeft.coerceIn(0f, 0.95f)) * srcWidth)
            .toInt().coerceIn(1, max(1, srcWidth - sx))
        val sh = ((doc.cropBottom.coerceIn(0.05f, 1f) - doc.cropTop.coerceIn(0f, 0.95f)) * srcHeight)
            .toInt().coerceIn(1, max(1, srcHeight - sy))

        val isTrimmedContent = doc.cropLeft > 0.005f || doc.cropTop > 0.005f ||
            doc.cropRight < 0.995f || doc.cropBottom < 0.995f
        // Preserve full edge-to-edge fit for uncropped opaque photos, keep clean balanced margin on trimmed cutouts & outlines
        val outlineMargin = when {
            doc.outlineThicknessPx > 0.5f -> doc.outlineThicknessPx.coerceIn(0f, 24f) + 2f
            isTrimmedContent -> 12f
            else -> 0f
        }
        val avail = (CANVAS_SIZE - outlineMargin * 2f).coerceAtLeast(64f)
        val fitScale = min(avail / sw.toFloat(), avail / sh.toFloat())
        val baseScale = fitScale * doc.zoom.coerceIn(0.25f, 5f)
        val scaleX = baseScale * doc.stretchX.coerceIn(0.25f, 2.5f)
        val scaleY = baseScale * doc.stretchY.coerceIn(0.25f, 2.5f)

        return SubjectTransformParams(
            srcCropLeft = sx,
            srcCropTop = sy,
            srcCropWidth = sw,
            srcCropHeight = sh,
            scaleX = scaleX,
            scaleY = scaleY,
            centerX = CANVAS_SIZE / 2f + doc.offsetX * CANVAS_SIZE,
            centerY = CANVAS_SIZE / 2f + doc.offsetY * CANVAS_SIZE,
            rotationDeg = doc.rotationDeg
        )
    }

    fun canvasNormToSourcePixel(
        normX: Float,
        normY: Float,
        srcWidth: Int,
        srcHeight: Int,
        doc: StaticStickerDocument
    ): Pair<Float, Float> {
        val params = computeSubjectTransform(srcWidth, srcHeight, doc)
        val px = normX * CANVAS_SIZE - params.centerX
        val py = normY * CANVAS_SIZE - params.centerY
        val rad = Math.toRadians(-params.rotationDeg.toDouble())
        val rx = (px * cos(rad) - py * kotlin.math.sin(rad)).toFloat()
        val ry = (px * kotlin.math.sin(rad) + py * cos(rad)).toFloat()
        val safeScaleX = params.scaleX.coerceAtLeast(0.01f)
        val safeScaleY = params.scaleY.coerceAtLeast(0.01f)
        val srcX = params.srcCropLeft + params.srcCropWidth / 2f + rx / safeScaleX
        val srcY = params.srcCropTop + params.srcCropHeight / 2f + ry / safeScaleY
        return srcX to srcY
    }

    private data class SubjectLayerKey(
        val subjectVersion: Int,
        val cropLeft: Float,
        val cropTop: Float,
        val cropRight: Float,
        val cropBottom: Float,
        val zoom: Float,
        val stretchX: Float,
        val stretchY: Float,
        val offsetX: Float,
        val offsetY: Float,
        val rotationDeg: Float,
        val outlineMargin: Float
    )

    class RenderSessionCache {
        private val pixelCount = CANVAS_SIZE * CANVAS_SIZE
        internal val subjectLayer: Bitmap = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
        }
        internal val outlineLayer: Bitmap = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(true)
        }

        internal val subjectPixels = IntArray(pixelCount)
        internal val outlinePixels = IntArray(pixelCount)
        internal val distSq = FloatArray(pixelCount)
        internal val edtF = FloatArray(CANVAS_SIZE)
        internal val edtD = FloatArray(CANVAS_SIZE)
        internal val edtV = IntArray(CANVAS_SIZE)
        internal val edtZ = FloatArray(CANVAS_SIZE + 1)

        internal val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        internal val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        internal val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        internal val strokePath = Path()
        internal val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        internal val bgRect = RectF()

        internal var lastSubjectKey: Any? = null
        internal var hasDistField: Boolean = false
        internal var released: Boolean = false

        fun invalidateSubject() {
            lastSubjectKey = null
            hasDistField = false
        }

        fun release() {
            if (released) return
            released = true
            subjectLayer.recycle()
            outlineLayer.recycle()
        }
    }

    fun renderStickerBitmap(
        maskedSubject: Bitmap,
        doc: StaticStickerDocument,
        typefaceResolver: (StickerFontStyle, Boolean, Boolean) -> Typeface
    ): Bitmap {
        val out = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        val cache = RenderSessionCache()
        try {
            renderIntoBitmap(
                target = out,
                maskedSubject = maskedSubject,
                subjectVersion = 1,
                doc = doc,
                cache = cache,
                typefaceResolver = typefaceResolver
            )
        } finally {
            cache.release()
        }
        return out
    }

    fun renderPreviewWithCache(
        maskedSubject: Bitmap,
        subjectVersion: Int,
        doc: StaticStickerDocument,
        cache: RenderSessionCache,
        typefaceResolver: (StickerFontStyle, Boolean, Boolean) -> Typeface
    ): Bitmap {
        val out = Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        renderIntoBitmap(
            target = out,
            maskedSubject = maskedSubject,
            subjectVersion = subjectVersion,
            doc = doc,
            cache = cache,
            typefaceResolver = typefaceResolver
        )
        out.prepareToDraw()
        return out
    }

    private fun renderIntoBitmap(
        target: Bitmap,
        maskedSubject: Bitmap,
        subjectVersion: Int,
        doc: StaticStickerDocument,
        cache: RenderSessionCache,
        typefaceResolver: (StickerFontStyle, Boolean, Boolean) -> Typeface
    ) {
        val canvas = Canvas(target)

        // Optional solid background inside sticker bounds
        doc.backgroundColorArgb?.let { bgColor ->
            cache.bgPaint.color = bgColor
            val pad = if (doc.outlineThicknessPx > 0.5f) 16f else 0f
            val corner = if (pad > 0f) 48f else 36f
            cache.bgRect.set(pad, pad, CANVAS_SIZE - pad, CANVAS_SIZE - pad)
            canvas.drawRoundRect(cache.bgRect, corner, corner, cache.bgPaint)
        }

        val isTrimmedContent = doc.cropLeft > 0.005f || doc.cropTop > 0.005f ||
            doc.cropRight < 0.995f || doc.cropBottom < 0.995f
        val outlineMargin = when {
            doc.outlineThicknessPx > 0.5f -> doc.outlineThicknessPx.coerceIn(0f, 24f) + 2f
            isTrimmedContent -> 12f
            else -> 0f
        }
        val key = SubjectLayerKey(
            subjectVersion = subjectVersion,
            cropLeft = doc.cropLeft,
            cropTop = doc.cropTop,
            cropRight = doc.cropRight,
            cropBottom = doc.cropBottom,
            zoom = doc.zoom,
            stretchX = doc.stretchX,
            stretchY = doc.stretchY,
            offsetX = doc.offsetX,
            offsetY = doc.offsetY,
            rotationDeg = doc.rotationDeg,
            outlineMargin = outlineMargin
        )

        if (cache.lastSubjectKey != key) {
            renderTransformedSubjectLayerInto(maskedSubject, doc, cache.subjectLayer, cache.bitmapPaint)
            cache.lastSubjectKey = key
            cache.hasDistField = false
        }

        // Apply gap-free anti-aliased outline using O(W*H) Euclidean distance transform
        if (doc.outlineThicknessPx > 0.5f) {
            if (!cache.hasDistField) {
                computeAlphaDistanceFieldLocked(cache)
                cache.hasDistField = true
            }
            fillOutlineFromDistanceField(cache, doc.outlineColorArgb, doc.outlineThicknessPx)
            canvas.drawBitmap(cache.outlineLayer, 0f, 0f, cache.bitmapPaint)
        }

        // Draw subject layer cleanly with anti-aliasing and filtering
        canvas.drawBitmap(cache.subjectLayer, 0f, 0f, cache.bitmapPaint)

        // Draw freehand strokes (supporting eraser strokes too via saveLayer)
        if (doc.strokes.isNotEmpty()) {
            val layerSave = canvas.saveLayer(0f, 0f, CANVAS_SIZE.toFloat(), CANVAS_SIZE.toFloat(), null)
            val strokePaint = cache.strokePaint
            val path = cache.strokePath
            for (stroke in doc.strokes) {
                if (stroke.points.isEmpty()) continue
                strokePaint.strokeWidth = stroke.widthPx
                if (stroke.isEraser) {
                    strokePaint.xfermode = cache.clearXfermode
                    strokePaint.color = Color.TRANSPARENT
                } else {
                    strokePaint.xfermode = null
                    strokePaint.color = stroke.colorArgb
                }
                if (stroke.points.size == 1) {
                    val p = stroke.points.first()
                    canvas.drawPoint(p.first * CANVAS_SIZE, p.second * CANVAS_SIZE, strokePaint)
                } else {
                    path.reset()
                    val first = stroke.points.first()
                    path.moveTo(first.first * CANVAS_SIZE, first.second * CANVAS_SIZE)
                    for (i in 1 until stroke.points.size) {
                        val prev = stroke.points[i - 1]
                        val curr = stroke.points[i]
                        val midX = ((prev.first + curr.first) * 0.5f) * CANVAS_SIZE
                        val midY = ((prev.second + curr.second) * 0.5f) * CANVAS_SIZE
                        path.quadTo(
                            prev.first * CANVAS_SIZE,
                            prev.second * CANVAS_SIZE,
                            midX,
                            midY
                        )
                    }
                    val last = stroke.points.last()
                    path.lineTo(last.first * CANVAS_SIZE, last.second * CANVAS_SIZE)
                    canvas.drawPath(path, strokePaint)
                }
            }
            canvas.restoreToCount(layerSave)
        }

        // Draw text overlays using unified coordinate & font metrics system
        for (item in doc.textItems) {
            if (item.text.isBlank()) continue
            drawTextOverlay(canvas, item, typefaceResolver, CANVAS_SIZE)
        }
    }

    suspend fun exportStaticStickerWebp(
        maskedSubject: Bitmap,
        doc: StaticStickerDocument,
        typefaceResolver: (StickerFontStyle, Boolean, Boolean) -> Typeface
    ): Result<ByteArray> = withContext(Dispatchers.Default) {
        val rendered = renderStickerBitmap(maskedSubject, doc, typefaceResolver)
        try {
            encodeWebpUnder100Kb(rendered)
        } finally {
            rendered.recycle()
        }
    }

    fun encodeWebpUnder100Kb(bitmap: Bitmap): Result<ByteArray> {
        val scaled = if (bitmap.width == CANVAS_SIZE && bitmap.height == CANVAS_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, CANVAS_SIZE, CANVAS_SIZE, true)
        }
        scaled.setHasAlpha(true)
        try {
            // 1. Try Lossless WebP first — preserves 100% exact edge alpha with zero compression artifacts
            val losslessOut = ByteArrayOutputStream()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, losslessOut)
            } else {
                @Suppress("DEPRECATION")
                scaled.compress(Bitmap.CompressFormat.WEBP, 100, losslessOut)
            }
            val losslessBytes = losslessOut.toByteArray()
            if (losslessBytes.size <= WhatsAppValidator.MAX_STATIC_BYTES) {
                val err = WhatsAppValidator.validateStickerBytes(losslessBytes, expectAnimated = false)
                if (err == null) return Result.success(losslessBytes)
            }

            // 2. High-fidelity adaptive lossy WebP (fine steps down from 96) preserving smooth alpha
            var bestBytes: ByteArray? = null
            val qualities = intArrayOf(96, 92, 88, 84, 78, 72, 65, 56, 46, 36, 26)
            for (q in qualities) {
                val baos = ByteArrayOutputStream()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, baos)
                } else {
                    @Suppress("DEPRECATION")
                    scaled.compress(Bitmap.CompressFormat.WEBP, q, baos)
                }
                val bytes = baos.toByteArray()
                bestBytes = bytes
                if (bytes.size <= WhatsAppValidator.MAX_STATIC_BYTES) {
                    val err = WhatsAppValidator.validateStickerBytes(bytes, expectAnimated = false)
                    if (err == null) return Result.success(bytes)
                }
            }

            val finalBytes = bestBytes
                ?: return Result.failure(IllegalStateException("Failed to compress WebP."))
            val validationError = WhatsAppValidator.validateStickerBytes(finalBytes, expectAnimated = false)
            return if (validationError == null) {
                Result.success(finalBytes)
            } else {
                Result.failure(IllegalStateException(validationError))
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun renderTransformedSubjectLayerInto(
        source: Bitmap,
        doc: StaticStickerDocument,
        targetLayer: Bitmap,
        paint: Paint
    ) {
        targetLayer.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(targetLayer)
        val params = computeSubjectTransform(source.width, source.height, doc)
        val srcRect = Rect(
            params.srcCropLeft,
            params.srcCropTop,
            params.srcCropLeft + params.srcCropWidth,
            params.srcCropTop + params.srcCropHeight
        )
        val dstRect = RectF(
            -params.srcCropWidth / 2f,
            -params.srcCropHeight / 2f,
            params.srcCropWidth / 2f,
            params.srcCropHeight / 2f
        )
        canvas.save()
        canvas.translate(params.centerX, params.centerY)
        canvas.rotate(params.rotationDeg)
        canvas.scale(params.scaleX, params.scaleY)
        canvas.drawBitmap(source, srcRect, dstRect, paint)
        canvas.restore()
    }

    /**
     * Exact linear-time O(W*H) 2D Euclidean Distance Transform (Felzenszwalb & Huttenlocher)
     * over the 512x512 subject alpha channel for smooth, pinhole-free sticker outlines.
     */
    private fun computeAlphaDistanceFieldLocked(cache: RenderSessionCache) {
        val w = CANVAS_SIZE
        val h = CANVAS_SIZE
        val inf = 2_000_000f
        val pixels = cache.subjectPixels
        val distSq = cache.distSq
        val f = cache.edtF
        val dOut = cache.edtD
        val v = cache.edtV
        val z = cache.edtZ

        cache.subjectLayer.getPixels(pixels, 0, w, 0, 0, w, h)

        // Pass 1: Horizontal 1D EDT along rows
        for (y in 0 until h) {
            val row = y * w
            var hasFg = false
            for (x in 0 until w) {
                val a = (pixels[row + x] ushr 24) and 0xFF
                if (a >= 128) {
                    f[x] = 0f
                    hasFg = true
                } else if (a >= 40) {
                    val edge = (128f - a) / 88f
                    f[x] = edge * edge
                    hasFg = true
                } else {
                    f[x] = inf
                }
            }
            if (!hasFg) {
                for (x in 0 until w) distSq[row + x] = inf
            } else {
                edt1d(f, w, dOut, v, z)
                System.arraycopy(dOut, 0, distSq, row, w)
            }
        }

        // Pass 2: Vertical 1D EDT along columns
        for (x in 0 until w) {
            var hasFinite = false
            for (y in 0 until h) {
                val valSq = distSq[y * w + x]
                f[y] = valSq
                if (valSq < inf * 0.5f) hasFinite = true
            }
            if (hasFinite) {
                edt1d(f, h, dOut, v, z)
                for (y in 0 until h) {
                    distSq[y * w + x] = dOut[y]
                }
            }
        }
    }

    private fun edt1d(
        f: FloatArray,
        n: Int,
        dOut: FloatArray,
        v: IntArray,
        z: FloatArray
    ) {
        var k = 0
        v[0] = 0
        z[0] = Float.NEGATIVE_INFINITY
        z[1] = Float.POSITIVE_INFINITY
        for (q in 1 until n) {
            val fq = f[q]
            var vk = v[k]
            var s = ((fq + q * q) - (f[vk] + vk * vk)) / (2f * (q - vk))
            while (s <= z[k]) {
                k--
                vk = v[k]
                s = ((fq + q * q) - (f[vk] + vk * vk)) / (2f * (q - vk))
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = Float.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) {
                k++
            }
            val vk = v[k]
            val dx = q - vk
            dOut[q] = dx * dx + f[vk]
        }
    }

    private fun fillOutlineFromDistanceField(
        cache: RenderSessionCache,
        outlineColor: Int,
        thicknessPx: Float
    ) {
        val maxR = thicknessPx.coerceIn(0.5f, 24f)
        val outerR = maxR + 0.75f
        val outerRSq = outerR * outerR
        val innerR = (maxR - 0.75f).coerceAtLeast(0f)
        val innerRSq = innerR * innerR
        val rgb = outlineColor and 0x00FFFFFF
        val opaqueColor = (0xFF shl 24) or rgb
        val distSq = cache.distSq
        val out = cache.outlinePixels

        for (i in out.indices) {
            val d2 = distSq[i]
            out[i] = when {
                d2 > outerRSq -> 0
                d2 <= innerRSq -> opaqueColor
                else -> {
                    val cov = ((outerR - sqrt(d2)) / 1.5f).coerceIn(0f, 1f)
                    val a = (cov * 255f + 0.5f).toInt().coerceIn(0, 255)
                    (a shl 24) or rgb
                }
            }
        }
        cache.outlineLayer.setPixels(out, 0, CANVAS_SIZE, 0, 0, CANVAS_SIZE, CANVAS_SIZE)
    }

    fun measureTextBoundsNorm(
        item: TextOverlayItem,
        typefaceResolver: (StickerFontStyle, Boolean, Boolean) -> Typeface,
        canvasSize: Int = CANVAS_SIZE
    ): TextBoundsNorm {
        val tf = typefaceResolver(item.fontStyle, item.isBold, item.isItalic)
        val textSizePx = item.fontSizeSp * (canvasSize / 320f)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            typeface = tf
        }
        val lines = item.text.split("\n")
        val fm = fillPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.12f
        val blockHeight = (lines.size - 1) * lineHeight + (fm.descent - fm.ascent)
        val maxLineWidth = lines.maxOfOrNull { fillPaint.measureText(it) }?.coerceAtLeast(textSizePx * 0.8f)
            ?: textSizePx
        val outlinePad = if (item.hasOutline) max(4f, textSizePx * 0.14f) else 4f
        val pad = 12f + outlinePad

        return TextBoundsNorm(
            centerX = item.normX,
            centerY = item.normY,
            widthNorm = ((maxLineWidth + pad * 2f) / canvasSize.toFloat()).coerceIn(0.08f, 0.96f),
            heightNorm = ((blockHeight + pad * 2f) / canvasSize.toFloat()).coerceIn(0.06f, 0.96f),
            rotationDeg = item.rotationDeg
        )
    }

    fun isPointInsideText(
        normX: Float,
        normY: Float,
        item: TextOverlayItem,
        typefaceResolver: (StickerFontStyle, Boolean, Boolean) -> Typeface,
        canvasSize: Int = CANVAS_SIZE
    ): Boolean {
        val bounds = measureTextBoundsNorm(item, typefaceResolver, canvasSize)
        val dx = normX - bounds.centerX
        val dy = normY - bounds.centerY
        val rad = Math.toRadians(-bounds.rotationDeg.toDouble())
        val lx = (dx * cos(rad) - dy * sin(rad)).toFloat()
        val ly = (dx * sin(rad) + dy * cos(rad)).toFloat()
        val halfW = bounds.widthNorm * 0.5f + 0.03f
        val halfH = bounds.heightNorm * 0.5f + 0.03f
        return kotlin.math.abs(lx) <= halfW && kotlin.math.abs(ly) <= halfH
    }

    fun drawTextOverlay(
        canvas: Canvas,
        item: TextOverlayItem,
        typefaceResolver: (StickerFontStyle, Boolean, Boolean) -> Typeface,
        canvasSize: Int = CANVAS_SIZE
    ) {
        val tf = typefaceResolver(item.fontStyle, item.isBold, item.isItalic)
        val textSizePx = item.fontSizeSp * (canvasSize / 320f)
        val align = when (item.alignment) {
            StickerTextAlign.LEFT -> Paint.Align.LEFT
            StickerTextAlign.CENTER -> Paint.Align.CENTER
            StickerTextAlign.RIGHT -> Paint.Align.RIGHT
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.colorArgb
            textSize = textSizePx
            typeface = tf
            textAlign = align
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.outlineColorArgb
            textSize = textSizePx
            typeface = tf
            textAlign = align
            style = Paint.Style.STROKE
            strokeWidth = max(3f, textSizePx * 0.14f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        val lines = item.text.split("\n")
        val fm = fillPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.12f
        val maxLineWidth = lines.maxOfOrNull { fillPaint.measureText(it) } ?: 0f

        val xDraw = when (item.alignment) {
            StickerTextAlign.LEFT -> -maxLineWidth / 2f
            StickerTextAlign.CENTER -> 0f
            StickerTextAlign.RIGHT -> maxLineWidth / 2f
        }
        // Exact vertical centering around (0, 0) matching FontMetrics ascent & descent
        val firstBaselineY = -((lines.size - 1) * lineHeight + fm.ascent + fm.descent) / 2f

        val cx = item.normX * canvasSize
        val cy = item.normY * canvasSize

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(item.rotationDeg)
        var baselineY = firstBaselineY
        for (line in lines) {
            if (item.hasOutline) {
                canvas.drawText(line, xDraw, baselineY, strokePaint)
            }
            canvas.drawText(line, xDraw, baselineY, fillPaint)
            baselineY += lineHeight
        }
        canvas.restore()
    }
}
