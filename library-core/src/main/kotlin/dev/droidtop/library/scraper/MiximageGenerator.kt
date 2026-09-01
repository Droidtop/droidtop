package dev.droidtop.library.scraper

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File

/**
 * Miximage composition, ported from real ES-DE's MiximageGenerator.cpp
 * (read directly; every layout constant below is that file's own value
 * at its default settings): a 1280x960 canvas (resolution multiplier
 * 2), the screenshot centered at 1060x800 with a +40px horizontal
 * offset inside a 12px frame whose color samples the screenshot, the
 * marquee top-right inside a 620x460 target sized by ES-DE's own
 * surface-area rule (calculateMarqueeSize, transcribed), the cover
 * bottom-left scaled to 600px height capped at 500px width (the
 * "medium" box size with cover fallback -- droidtop scrapes 2D covers,
 * and MiximageCoverFallback is ES-DE's own default-on path), physical
 * media bottom, 32px right of the cover, inside 300x240.
 *
 * Deliberate deviations, documented rather than hidden: Android's
 * bilinear filtering replaces Lanczos/box resampling; the drop shadow
 * is one blurred-alpha pass (BlurMaskFilter) instead of four box-blur
 * iterations; letterbox/pillarbox removal trims fully-black edge
 * rows/columns rather than CImg's average-luminance scan; horizontal
 * box rotation is not implemented (droidtop feeds portrait 2D covers).
 */
object MiximageGenerator {

    private const val WIDTH = 1280
    private const val HEIGHT = 960
    private const val SCREENSHOT_WIDTH = 1060
    private const val SCREENSHOT_HEIGHT = 800
    private const val SCREENSHOT_OFFSET = 40
    private const val FRAME_WIDTH = 12
    private const val MARQUEE_TARGET_WIDTH = 620
    private const val MARQUEE_TARGET_HEIGHT = 460
    private const val COVER_TARGET_WIDTH = 500
    private const val BOX_TARGET_HEIGHT = 600
    private const val PHYSICAL_TARGET_WIDTH = 300
    private const val PHYSICAL_TARGET_HEIGHT = 240
    private const val PHYSICAL_MARGIN = 32
    private const val SHADOW_SIZE = 12f
    private const val ASPECT_MAX = 1.6f
    private const val ASPECT_MIN = 1.05f

    /** Composes and writes [output] (PNG). False when the screenshot can't be decoded -- it is the mandatory ingredient, same as real ES-DE. */
    fun generate(screenshot: File, marquee: File?, cover: File?, physicalMedia: File?, output: File): Boolean {
        val screenshotBitmap = decode(screenshot) ?: return false
        val canvasBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val trimmed = trimBlackEdges(screenshotBitmap)
        val fitted = fitScreenshot(trimmed)
        val xs = WIDTH / 2 - SCREENSHOT_WIDTH / 2 + SCREENSHOT_OFFSET
        val ys = HEIGHT / 2 - SCREENSHOT_HEIGHT / 2

        // Frame first, sampled from the screenshot's own average color.
        val framePaint = Paint().apply { color = averageColor(fitted) }
        canvas.drawRect(
            RectF(
                (xs - FRAME_WIDTH).toFloat(),
                (ys - FRAME_WIDTH).toFloat(),
                (xs + SCREENSHOT_WIDTH + FRAME_WIDTH).toFloat(),
                (ys + SCREENSHOT_HEIGHT + FRAME_WIDTH).toFloat(),
            ),
            framePaint,
        )
        canvas.drawBitmap(fitted, null, Rect(xs, ys, xs + SCREENSHOT_WIDTH, ys + SCREENSHOT_HEIGHT), paint)

        marquee?.let { decode(it) }?.let { raw ->
            val marqueeBitmap = trimTransparentPadding(raw)
            val (mw, mh) = marqueeSize(marqueeBitmap.width, marqueeBitmap.height)
            val scaled = Bitmap.createScaledBitmap(marqueeBitmap, mw, mh, true)
            val x = WIDTH - scaled.width
            drawWithShadow(canvas, scaled, x.toFloat(), 0f, paint)
        }

        var boxRightEdge = 0
        cover?.let { decode(it) }?.let { raw ->
            val boxBitmap = trimTransparentPadding(raw)
            var scale = BOX_TARGET_HEIGHT.toFloat() / boxBitmap.height
            if (boxBitmap.width * scale > COVER_TARGET_WIDTH) {
                scale = COVER_TARGET_WIDTH.toFloat() / boxBitmap.width
            }
            val scaled = Bitmap.createScaledBitmap(
                boxBitmap,
                (boxBitmap.width * scale).toInt().coerceAtLeast(1),
                (boxBitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            val y = HEIGHT - scaled.height
            drawWithShadow(canvas, scaled, 0f, y.toFloat(), paint)
            boxRightEdge = scaled.width
        }

        physicalMedia?.let { decode(it) }?.let { raw ->
            val mediaBitmap = trimTransparentPadding(raw)
            val scale = minOf(
                PHYSICAL_TARGET_WIDTH.toFloat() / mediaBitmap.width,
                PHYSICAL_TARGET_HEIGHT.toFloat() / mediaBitmap.height,
            )
            val scaled = Bitmap.createScaledBitmap(
                mediaBitmap,
                (mediaBitmap.width * scale).toInt().coerceAtLeast(1),
                (mediaBitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            val x = boxRightEdge + PHYSICAL_MARGIN
            val y = HEIGHT - scaled.height
            drawWithShadow(canvas, scaled, x.toFloat(), y.toFloat(), paint)
        }

        output.parentFile?.mkdirs()
        output.outputStream().use { stream ->
            canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return true
    }

    private fun decode(file: File): Bitmap? =
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()

    /** ES-DE's calculateMarqueeSize, transcribed: surface-area-balanced width with a wide-image boost. */
    private fun marqueeSize(width: Int, height: Int): Pair<Int, Int> {
        val widthRatio = width.toFloat() / height
        var widthModifier = (0.5f + widthRatio / 6.5f).coerceIn(0f, 1f)
        if (widthRatio >= 4f) widthModifier += (widthRatio / 40f).coerceIn(0f, 0.3f)
        val adjustedTargetWidth = MARQUEE_TARGET_WIDTH * widthModifier
        var scaleFactor = adjustedTargetWidth / width
        if (scaleFactor * height > MARQUEE_TARGET_HEIGHT) {
            scaleFactor = MARQUEE_TARGET_HEIGHT.toFloat() / height
        }
        return (width * scaleFactor).toInt().coerceAtLeast(1) to
            (height * scaleFactor).toInt().coerceAtLeast(1)
    }

    /**
     * Default-settings fit (horizontal crop / vertical contain, "high"
     * aspect thresholds 1.05..1.6): inside the thresholds the image is
     * stretched to the 1.325 target exactly like real ES-DE; a wider
     * image is center-cropped; a taller one is contained over black.
     */
    private fun fitScreenshot(source: Bitmap): Bitmap {
        val aspect = source.width.toFloat() / source.height
        val target = Bitmap.createBitmap(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        when {
            aspect > ASPECT_MAX -> {
                val scaledWidth = (SCREENSHOT_HEIGHT * aspect).toInt()
                val offsetX = (scaledWidth - SCREENSHOT_WIDTH) / 2
                canvas.drawBitmap(source, null, Rect(-offsetX, 0, scaledWidth - offsetX, SCREENSHOT_HEIGHT), paint)
            }
            aspect < ASPECT_MIN -> {
                val scaledWidth = (SCREENSHOT_HEIGHT * aspect).toInt()
                val x = (SCREENSHOT_WIDTH - scaledWidth) / 2
                canvas.drawBitmap(source, null, Rect(x, 0, x + scaledWidth, SCREENSHOT_HEIGHT), paint)
            }
            else -> canvas.drawBitmap(source, null, Rect(0, 0, SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT), paint)
        }
        return target
    }

    private fun drawWithShadow(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float, paint: Paint) {
        val alpha = bitmap.extractAlpha()
        val shadowPaint = Paint().apply {
            color = Color.argb(153, 0, 0, 0)
            maskFilter = BlurMaskFilter(SHADOW_SIZE, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(alpha, x + SHADOW_SIZE / 2, y + SHADOW_SIZE / 2, shadowPaint)
        alpha.recycle()
        canvas.drawBitmap(bitmap, x, y, paint)
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in 0 until sample.height) {
            for (x in 0 until sample.width) {
                val pixel = sample.getPixel(x, y)
                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
            }
        }
        val count = sample.width * sample.height
        sample.recycle()
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun trimTransparentPadding(bitmap: Bitmap): Bitmap = trimEdges(bitmap) { pixel -> Color.alpha(pixel) == 0 }

    private fun trimBlackEdges(bitmap: Bitmap): Bitmap = trimEdges(bitmap) { pixel ->
        Color.alpha(pixel) == 0 || (Color.red(pixel) < 10 && Color.green(pixel) < 10 && Color.blue(pixel) < 10)
    }

    private inline fun trimEdges(bitmap: Bitmap, isPadding: (Int) -> Boolean): Bitmap {
        var top = 0
        var bottom = bitmap.height - 1
        var left = 0
        var right = bitmap.width - 1
        val step = maxOf(1, bitmap.width / 64)

        fun rowIsPadding(y: Int): Boolean {
            var x = 0
            while (x < bitmap.width) {
                if (!isPadding(bitmap.getPixel(x, y))) return false
                x += step
            }
            return true
        }

        fun columnIsPadding(x: Int): Boolean {
            var y = 0
            while (y < bitmap.height) {
                if (!isPadding(bitmap.getPixel(x, y))) return false
                y += step
            }
            return true
        }

        while (top < bottom && rowIsPadding(top)) top++
        while (bottom > top && rowIsPadding(bottom)) bottom--
        while (left < right && columnIsPadding(left)) left++
        while (right > left && columnIsPadding(right)) right--

        if (top == 0 && left == 0 && bottom == bitmap.height - 1 && right == bitmap.width - 1) return bitmap
        if (right - left < 32 || bottom - top < 32) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }
}
