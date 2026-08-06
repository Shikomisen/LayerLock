package com.shikomisen.layerlock.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.util.SizeF
import com.shikomisen.layerlock.scene.ColorSpec
import com.shikomisen.layerlock.scene.TextAlign
import com.shikomisen.layerlock.scene.TextStyleSpec

/**
 * Text measurement and drawing for the clock, date, free-text and widget layers.
 *
 * All text is laid out around a centre origin, so a layer's stored `transform.x/y` addresses the
 * middle of the text block regardless of its alignment or line count. That keeps rotation and
 * scaling intuitive — a rotated clock spins about its own centre, not a corner.
 */
object TextPainter {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** Reconfigures and returns the shared paint. Not thread-safe by design — draws are serialised. */
    fun configure(style: TextStyleSpec, opacity: Float = 1f): Paint = paint.apply {
        reset()
        isAntiAlias = true
        isSubpixelText = true
        typeface = FontCatalog.resolve(style.fontFamily, style.weight, style.italic)
        textSize = style.fontSize
        letterSpacing = style.letterSpacing
        color = ColorSpec.withAlpha(ColorSpec.parse(style.color), opacity)
        textAlign = when (style.align) {
            TextAlign.LEFT -> Paint.Align.LEFT
            TextAlign.CENTER -> Paint.Align.CENTER
            TextAlign.RIGHT -> Paint.Align.RIGHT
        }
        if (style.shadow) {
            setShadowLayer(
                style.shadowRadius.coerceAtLeast(0.01f),
                style.shadowDx,
                style.shadowDy,
                ColorSpec.withAlpha(ColorSpec.parse(style.shadowColor), opacity),
            )
        }
    }

    fun linesOf(text: String, style: TextStyleSpec): List<String> {
        val source = if (style.allCaps) text.uppercase() else text
        return source.split('\n')
    }

    fun measure(text: String, style: TextStyleSpec): SizeF {
        val lines = linesOf(text, style)
        val p = configure(style)
        val width = lines.maxOfOrNull { p.measureText(it) } ?: 0f
        return SizeF(width, lineHeight(style) * lines.size)
    }

    fun lineHeight(style: TextStyleSpec): Float =
        style.fontSize * style.lineHeightMultiplier.coerceAtLeast(0.1f)

    /**
     * Draws [text] centred on the canvas origin. The caller is responsible for having already
     * translated, rotated and scaled the canvas into the layer's local space.
     */
    fun draw(canvas: Canvas, text: String, style: TextStyleSpec, opacity: Float = 1f) {
        val lines = linesOf(text, style)
        if (lines.isEmpty()) return

        val p = configure(style, opacity)
        val lineHeight = lineHeight(style)
        val totalHeight = lineHeight * lines.size
        val metrics = p.fontMetrics
        val widest = lines.maxOfOrNull { p.measureText(it) } ?: 0f

        // Centre the block vertically, then place each baseline within its own line box.
        var baseline = -totalHeight / 2f +
            (lineHeight - (metrics.descent - metrics.ascent)) / 2f -
            metrics.ascent

        val x = when (style.align) {
            TextAlign.LEFT -> -widest / 2f
            TextAlign.CENTER -> 0f
            TextAlign.RIGHT -> widest / 2f
        }

        lines.forEach { line ->
            canvas.drawText(line, x, baseline, p)
            baseline += lineHeight
        }
    }
}
