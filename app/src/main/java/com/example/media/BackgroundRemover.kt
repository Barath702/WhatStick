package com.example.media

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin offline alpha-mask utilities for Smart Object extraction, Erase Brush, and Restore Brush.
 * Preserves full original RGB + alpha data cleanly with zero AI/ML dependencies and smooth anti-aliased transitions.
 */
object BackgroundRemover {

    fun createFullMaskFromSource(source: Bitmap): ByteArray {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val alpha = ByteArray(w * h)
        for (i in pixels.indices) {
            alpha[i] = ((pixels[i] ushr 24) and 0xFF).toByte()
        }
        return alpha
    }

    fun applyAlphaMask(source: Bitmap, mask: ByteArray): Bitmap {
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        val pixels = IntArray(w * h)
        applyAlphaMaskInto(source, mask, pixels, out)
        return out
    }

    fun applyAlphaMaskInto(
        source: Bitmap,
        mask: ByteArray,
        scratchPixels: IntArray,
        target: Bitmap
    ) {
        val w = source.width
        val h = source.height
        source.getPixels(scratchPixels, 0, w, 0, 0, w, h)
        val count = min(scratchPixels.size, mask.size)
        for (i in 0 until count) {
            val alpha = mask[i].toInt() and 0xFF
            scratchPixels[i] = (scratchPixels[i] and 0x00FFFFFF) or (alpha shl 24)
        }
        target.setHasAlpha(true)
        target.setPixels(scratchPixels, 0, w, 0, 0, w, h)
    }

    fun paintMaskBrush(
        mask: ByteArray,
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        radiusPx: Float,
        restore: Boolean,
        originalAlpha: ByteArray? = null
    ): ByteArray {
        val updated = mask.copyOf()
        paintMaskBrushSegmentInPlace(
            mask = updated,
            width = width,
            height = height,
            startX = centerX,
            startY = centerY,
            endX = centerX,
            endY = centerY,
            radiusPx = radiusPx,
            restore = restore,
            originalAlpha = originalAlpha
        )
        return updated
    }

    /**
     * Paints a smooth anti-aliased capsule segment from (startX, startY) to (endX, endY)
     * directly into [mask] without compounding dabs or leaving jagged gaps.
     */
    fun paintMaskBrushSegmentInPlace(
        mask: ByteArray,
        width: Int,
        height: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        radiusPx: Float,
        restore: Boolean,
        originalAlpha: ByteArray? = null
    ) {
        paintMaskBrushSegmentInternal(
            mask = mask,
            width = width,
            height = height,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            radiusPx = radiusPx,
            restore = restore,
            originalAlpha = originalAlpha,
            sourceBitmap = null,
            scratchPixels = null,
            targetBitmap = null
        )
    }

    /**
     * Paints a smooth anti-aliased capsule segment into [mask] and refreshes only the affected
     * dirty rectangle inside [targetBitmap] from [sourceBitmap], avoiding full-bitmap copies.
     */
    fun paintMaskBrushSegmentAndUpdateBitmap(
        mask: ByteArray,
        width: Int,
        height: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        radiusPx: Float,
        restore: Boolean,
        originalAlpha: ByteArray?,
        sourceBitmap: Bitmap,
        scratchPixels: IntArray,
        targetBitmap: Bitmap
    ) {
        paintMaskBrushSegmentInternal(
            mask = mask,
            width = width,
            height = height,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            radiusPx = radiusPx,
            restore = restore,
            originalAlpha = originalAlpha,
            sourceBitmap = sourceBitmap,
            scratchPixels = scratchPixels,
            targetBitmap = targetBitmap
        )
    }

    private fun paintMaskBrushSegmentInternal(
        mask: ByteArray,
        width: Int,
        height: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        radiusPx: Float,
        restore: Boolean,
        originalAlpha: ByteArray?,
        sourceBitmap: Bitmap?,
        scratchPixels: IntArray?,
        targetBitmap: Bitmap?
    ) {
        if (width <= 0 || height <= 0 || mask.size < width * height) return
        val r = radiusPx.coerceAtLeast(1.5f)
        val rSq = r * r

        val minX = max(0, (min(startX, endX) - r - 1f).toInt())
        val maxX = min(width - 1, (max(startX, endX) + r + 1f).toInt())
        val minY = max(0, (min(startY, endY) - r - 1f).toInt())
        val maxY = min(height - 1, (max(startY, endY) + r + 1f).toInt())
        if (minX > maxX || minY > maxY) return

        val segDx = endX - startX
        val segDy = endY - startY
        val segLenSq = segDx * segDx + segDy * segDy
        val innerRatio = 0.65f

        for (y in minY..maxY) {
            val py = y.toFloat()
            val rowOffset = y * width
            for (x in minX..maxX) {
                val px = x.toFloat()
                val t = if (segLenSq <= 1e-4f) {
                    0f
                } else {
                    (((px - startX) * segDx + (py - startY) * segDy) / segLenSq).coerceIn(0f, 1f)
                }
                val projX = startX + t * segDx
                val projY = startY + t * segDy
                val dx = px - projX
                val dy = py - projY
                val dSq = dx * dx + dy * dy

                if (dSq <= rSq) {
                    val normDist = (sqrt(dSq) / r).coerceIn(0f, 1f)
                    val strength = if (normDist <= innerRatio) {
                        1f
                    } else {
                        val edgeT = ((1f - normDist) / (1f - innerRatio)).coerceIn(0f, 1f)
                        // Smoothstep falloff for artifact-free anti-aliased transitions
                        edgeT * edgeT * (3f - 2f * edgeT)
                    }
                    val idx = rowOffset + x
                    val current = mask[idx].toInt() and 0xFF
                    val target = if (restore) {
                        (originalAlpha?.getOrNull(idx)?.toInt() ?: 255) and 0xFF
                    } else {
                        0
                    }
                    val blended = if (restore) {
                        max(current, (current + (target - current) * strength).toInt()).coerceIn(0, target)
                    } else {
                        min(current, (current + (target - current) * strength).toInt()).coerceIn(0, 255)
                    }
                    mask[idx] = blended.toByte()
                }
            }
        }

        if (sourceBitmap != null && targetBitmap != null && scratchPixels != null) {
            val rectW = maxX - minX + 1
            val rectH = maxY - minY + 1
            val regionCount = rectW * rectH
            if (regionCount > 0 && regionCount <= scratchPixels.size &&
                !sourceBitmap.isRecycled && !targetBitmap.isRecycled
            ) {
                sourceBitmap.getPixels(scratchPixels, 0, rectW, minX, minY, rectW, rectH)
                var dstIdx = 0
                for (y in minY..maxY) {
                    val maskRow = y * width + minX
                    for (dx in 0 until rectW) {
                        val alpha = mask[maskRow + dx].toInt() and 0xFF
                        scratchPixels[dstIdx] = (scratchPixels[dstIdx] and 0x00FFFFFF) or (alpha shl 24)
                        dstIdx++
                    }
                }
                targetBitmap.setHasAlpha(true)
                targetBitmap.setPixels(scratchPixels, 0, rectW, minX, minY, rectW, rectH)
            }
        }
    }

    /**
     * Smart Object foreground extraction (non-AI, 100% offline):
     * 1. Preserves already-transparent cutouts when perimeter already contains substantial alpha transparency.
     * 2. Estimates dominant border background clusters and color spread around the 2px perimeter frame.
     * 3. Runs a gradient-aware, border-seeded BFS flood-fill (using primitive queue arrays) so interior
     *    object regions cannot become transparent holes and sharp/hairy edges act as natural barriers.
     * 4. Applies morphological 3x3 boundary smoothing + 1.5px anti-aliased alpha transition along object edges.
     */
    fun extractSmartObjectMask(source: Bitmap): ByteArray {
        val w = source.width
        val h = source.height
        val total = w * h
        if (w <= 2 || h <= 2) return createFullMaskFromSource(source)

        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val origAlpha = ByteArray(total)
        for (i in 0 until total) {
            origAlpha[i] = ((pixels[i] ushr 24) and 0xFF).toByte()
        }

        // Check perimeter transparency: if the source already has a cutout (e.g. transparent PNG), preserve it
        var perimeterCount = 0
        var transparentPerimeterCount = 0
        val borderSamples = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) {
            for (y in intArrayOf(0, 1, h - 2, h - 1)) {
                val idx = y * w + x
                perimeterCount++
                val a = origAlpha[idx].toInt() and 0xFF
                if (a < 32) {
                    transparentPerimeterCount++
                } else {
                    borderSamples.add(pixels[idx])
                }
            }
        }
        for (y in 2 until h - 2) {
            for (x in intArrayOf(0, 1, w - 2, w - 1)) {
                val idx = y * w + x
                perimeterCount++
                val a = origAlpha[idx].toInt() and 0xFF
                if (a < 32) {
                    transparentPerimeterCount++
                } else {
                    borderSamples.add(pixels[idx])
                }
            }
        }

        if (perimeterCount > 0 && transparentPerimeterCount > perimeterCount * 0.15f) {
            return origAlpha
        }
        if (borderSamples.isEmpty()) return origAlpha

        // Build up to 4 dominant border color clusters (R, G, B)
        val clusterR = IntArray(4)
        val clusterG = IntArray(4)
        val clusterB = IntArray(4)
        val clusterCounts = IntArray(4)
        var clusterCount = 0
        val clusterMergeDistSq = 40 * 40

        val stride = max(1, borderSamples.size / 360)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sampleN = 0
        var sIdx = 0
        while (sIdx < borderSamples.size) {
            val c = borderSamples[sIdx]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            sumR += r
            sumG += g
            sumB += b
            sampleN++

            var matched = false
            for (k in 0 until clusterCount) {
                val dr = r - clusterR[k]
                val dg = g - clusterG[k]
                val db = b - clusterB[k]
                if (dr * dr + dg * dg + db * db <= clusterMergeDistSq) {
                    val cnt = clusterCounts[k]
                    clusterR[k] = (clusterR[k] * cnt + r) / (cnt + 1)
                    clusterG[k] = (clusterG[k] * cnt + g) / (cnt + 1)
                    clusterB[k] = (clusterB[k] * cnt + b) / (cnt + 1)
                    clusterCounts[k] = cnt + 1
                    matched = true
                    break
                }
            }
            if (!matched && clusterCount < 4) {
                clusterR[clusterCount] = r
                clusterG[clusterCount] = g
                clusterB[clusterCount] = b
                clusterCounts[clusterCount] = 1
                clusterCount++
            }
            sIdx += stride
        }
        if (clusterCount == 0 || sampleN == 0) return origAlpha

        val meanR = (sumR / sampleN).toInt()
        val meanG = (sumG / sampleN).toInt()
        val meanB = (sumB / sampleN).toInt()

        var spreadAccum = 0.0
        sIdx = 0
        while (sIdx < borderSamples.size) {
            val c = borderSamples[sIdx]
            val dr = ((c ushr 16) and 0xFF) - meanR
            val dg = ((c ushr 8) and 0xFF) - meanG
            val db = (c and 0xFF) - meanB
            spreadAccum += sqrt((dr * dr + dg * dg + db * db).toDouble())
            sIdx += stride
        }
        val borderSpread = (spreadAccum / max(1, sampleN)).toFloat()

        // Compute fast 4-neighbor gradient edge strength
        val edgeStrength = IntArray(total)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val cLeft = pixels[idx - 1]
                val cRight = pixels[idx + 1]
                val cUp = pixels[idx - w]
                val cDown = pixels[idx + w]
                val dxR = abs(((cRight ushr 16) and 0xFF) - ((cLeft ushr 16) and 0xFF))
                val dxG = abs(((cRight ushr 8) and 0xFF) - ((cLeft ushr 8) and 0xFF))
                val dxB = abs((cRight and 0xFF) - (cLeft and 0xFF))
                val dyR = abs(((cDown ushr 16) and 0xFF) - ((cUp ushr 16) and 0xFF))
                val dyG = abs(((cDown ushr 8) and 0xFF) - ((cUp ushr 8) and 0xFF))
                val dyB = abs((cDown and 0xFF) - (cUp and 0xFF))
                val gradX = max(dxR, max(dxG, dxB))
                val gradY = max(dyR, max(dyG, dyB))
                edgeStrength[idx] = max(gradX, gradY)
            }
        }

        val baseColorDist = (38f + borderSpread * 0.85f).coerceIn(32f, 88f)
        val baseDistSq = (baseColorDist * baseColorDist).toInt()
        val maxDriftDistSq = (baseDistSq * 2.2f).toInt()
        val stepDistSq = 28 * 28
        val outerEdgeBarrier = (46 + (borderSpread * 0.5f).toInt()).coerceIn(44, 72)
        val centerEdgeBarrier = (outerEdgeBarrier * 0.72f).toInt().coerceAtLeast(32)

        val queue = IntArray(total)
        var isBg = BooleanArray(total)

        fun minBorderClusterDistSq(rgb: Int): Int {
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF
            var best = Int.MAX_VALUE
            for (k in 0 until clusterCount) {
                val dr = r - clusterR[k]
                val dg = g - clusterG[k]
                val db = b - clusterB[k]
                val dSq = dr * dr + dg * dg + db * db
                if (dSq < best) best = dSq
            }
            return best
        }

        fun runBorderFlood(distSqLimit: Int, driftSqLimit: Int): Int {
            isBg = BooleanArray(total)
            var head = 0
            var tail = 0
            val cMinX = (w * 0.2f).toInt()
            val cMaxX = (w * 0.8f).toInt()
            val cMinY = (h * 0.2f).toInt()
            val cMaxY = (h * 0.8f).toInt()

            fun trySeed(idx: Int) {
                if (isBg[idx]) return
                val a = origAlpha[idx].toInt() and 0xFF
                if (a < 32 || minBorderClusterDistSq(pixels[idx]) <= distSqLimit) {
                    isBg[idx] = true
                    queue[tail++] = idx
                }
            }

            for (x in 0 until w) {
                trySeed(x)
                trySeed((h - 1) * w + x)
            }
            for (y in 1 until h - 1) {
                trySeed(y * w)
                trySeed(y * w + (w - 1))
            }

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                val cur = pixels[idx]
                val curR = (cur ushr 16) and 0xFF
                val curG = (cur ushr 8) and 0xFF
                val curB = cur and 0xFF

                for (dir in 0 until 4) {
                    val nx = when (dir) {
                        0 -> x - 1
                        1 -> x + 1
                        else -> x
                    }
                    val ny = when (dir) {
                        2 -> y - 1
                        3 -> y + 1
                        else -> y
                    }
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val nIdx = ny * w + nx
                    if (isBg[nIdx]) continue

                    val nAlpha = origAlpha[nIdx].toInt() and 0xFF
                    if (nAlpha < 32) {
                        isBg[nIdx] = true
                        queue[tail++] = nIdx
                        continue
                    }

                    val inCenter = nx in cMinX..cMaxX && ny in cMinY..cMaxY
                    val barrier = if (inCenter) centerEdgeBarrier else outerEdgeBarrier
                    if (edgeStrength[nIdx] > barrier) continue

                    val nextRgb = pixels[nIdx]
                    val borderDist = minBorderClusterDistSq(nextRgb)
                    if (borderDist <= distSqLimit) {
                        isBg[nIdx] = true
                        queue[tail++] = nIdx
                    } else if (borderDist <= driftSqLimit) {
                        val dr = ((nextRgb ushr 16) and 0xFF) - curR
                        val dg = ((nextRgb ushr 8) and 0xFF) - curG
                        val db = (nextRgb and 0xFF) - curB
                        if (dr * dr + dg * dg + db * db <= stepDistSq) {
                            isBg[nIdx] = true
                            queue[tail++] = nIdx
                        }
                    }
                }
            }
            return tail
        }

        var bgCount = runBorderFlood(baseDistSq, maxDriftDistSq)
        // If nearly the whole image was flooded (e.g. uniform solid color or very low-contrast subject), tighten tolerance
        if (bgCount > (total * 0.94f).toInt()) {
            bgCount = runBorderFlood((baseDistSq * 0.42f).toInt(), (maxDriftDistSq * 0.45f).toInt())
        }
        // Still uniform/all-flooded -> preserve original mask safely rather than erasing the sticker to empty
        if (bgCount > (total * 0.95f).toInt() || bgCount == 0) {
            return origAlpha
        }

        // 3x3 morphological cleanup to remove 1px speckles and smooth jagged notches
        val smoothedBg = isBg.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                var bgNeighbors = 0
                for (dy in -1..1) {
                    val nRow = (y + dy) * w
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (isBg[nRow + x + dx]) bgNeighbors++
                    }
                }
                if (!isBg[idx] && bgNeighbors >= 7) {
                    smoothedBg[idx] = true
                } else if (isBg[idx] && bgNeighbors <= 2) {
                    smoothedBg[idx] = false
                }
            }
        }

        // Finalize mask with smooth anti-aliased edge transition and intact interior opacity
        val outMask = ByteArray(total)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val baseA = origAlpha[idx].toInt() and 0xFF
                if (baseA == 0) {
                    outMask[idx] = 0
                    continue
                }
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) {
                    outMask[idx] = if (smoothedBg[idx]) 0 else baseA.toByte()
                    continue
                }

                var bgNeighbors = 0
                for (dy in -1..1) {
                    val nRow = (y + dy) * w
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (smoothedBg[nRow + x + dx]) bgNeighbors++
                    }
                }

                outMask[idx] = if (!smoothedBg[idx]) {
                    if (bgNeighbors == 0) {
                        baseA.toByte()
                    } else {
                        // Gentle edge anti-aliasing without creating transparent border gaps
                        val keepFactor = (1f - bgNeighbors / 14f).coerceIn(0.45f, 1f)
                        (baseA * keepFactor).toInt().coerceIn(0, baseA).toByte()
                    }
                } else {
                    val fgNeighbors = 8 - bgNeighbors
                    if (fgNeighbors >= 3) {
                        val feather = (baseA * (fgNeighbors / 18f)).toInt().coerceIn(0, min(115, baseA))
                        feather.toByte()
                    } else {
                        0
                    }
                }
            }
        }
        return outMask
    }
}
