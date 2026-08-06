package com.shikomisen.layerlock.canvas

import android.graphics.RectF
import android.util.SizeF
import com.shikomisen.layerlock.scene.ClockLayer
import com.shikomisen.layerlock.scene.CutoutLayer
import com.shikomisen.layerlock.scene.DateLayer
import com.shikomisen.layerlock.scene.GifLayer
import com.shikomisen.layerlock.scene.ImageLayer
import com.shikomisen.layerlock.scene.Layer
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.TextLayer
import com.shikomisen.layerlock.scene.VideoLayer
import com.shikomisen.layerlock.scene.WidgetLayer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where each layer sits, in canvas pixels.
 *
 * This is the one place that knows how a layer's size is derived, and it is shared by the renderer,
 * the editor's hit-testing and the selection overlay — so what the user grabs is always exactly what
 * they see drawn.
 */
object LayerGeometry {

    /**
     * A picked photo is laid out so its longest edge covers this fraction of the canvas width at
     * `scale = 1`, rather than using its pixel dimensions directly. Without this, adding a 4000px
     * photo and a 400px sticker would drop two wildly different objects onto the canvas, and `scale`
     * would mean something different for every image.
     */
    const val MEDIA_BASE_FRACTION = 0.6f

    /** Fallback box for a media layer whose source has not decoded (or has gone missing). */
    private const val PLACEHOLDER_ASPECT = 3f / 4f

    /** Unrotated, unscaled size of a layer's content. */
    fun baseSize(
        layer: Layer,
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
    ): SizeF = when (layer) {
        is ClockLayer -> TextPainter.measure(
            ClockFormatter.format(layer.pattern, timeMillis),
            layer.style,
        )

        is DateLayer -> TextPainter.measure(
            ClockFormatter.format(layer.pattern, timeMillis),
            layer.style,
        )

        is TextLayer -> TextPainter.measure(layer.text, layer.style)

        is WidgetLayer -> TextPainter.measure(widgetText(layer, widgets), layer.style)

        is ImageLayer -> mediaSize(assets.intrinsicSize(layer.sourceUri), scene)
        is GifLayer -> mediaSize(assets.intrinsicSize(layer.sourceUri), scene)
        is VideoLayer -> mediaSize(assets.intrinsicSize(layer.sourceUri), scene)
        is CutoutLayer -> mediaSize(
            assets.intrinsicSize(layer.cutoutUri ?: layer.sourceUri),
            scene,
        )
    }

    fun widgetText(layer: WidgetLayer, widgets: WidgetSnapshot): String {
        val value = WidgetText.value(layer.widgetKind, widgets)
        val icon = if (layer.showIcon) "${WidgetText.icon(layer.widgetKind, widgets)} " else ""
        val label = if (layer.showLabel) "\n${WidgetText.label(layer.widgetKind)}" else ""
        return "$icon$value$label"
    }

    private fun mediaSize(intrinsic: SizeF?, scene: Scene): SizeF {
        val base = scene.canvas.width * MEDIA_BASE_FRACTION
        if (intrinsic == null || intrinsic.width <= 0f || intrinsic.height <= 0f) {
            return SizeF(base, base / PLACEHOLDER_ASPECT)
        }
        val longest = maxOf(intrinsic.width, intrinsic.height)
        val factor = base / longest
        return SizeF(intrinsic.width * factor, intrinsic.height * factor)
    }

    /** Final on-canvas size, after the layer's own scale. */
    fun scaledSize(
        layer: Layer,
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
    ): SizeF {
        val base = baseSize(layer, scene, assets, widgets, timeMillis)
        val scale = layer.transform.scale
        return SizeF(base.width * scale, base.height * scale)
    }

    /** Axis-aligned box of the layer *before* rotation, in canvas coordinates. */
    fun unrotatedBounds(
        layer: Layer,
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
    ): RectF {
        val size = scaledSize(layer, scene, assets, widgets, timeMillis)
        val halfWidth = size.width / 2f
        val halfHeight = size.height / 2f
        return RectF(
            layer.transform.x - halfWidth,
            layer.transform.y - halfHeight,
            layer.transform.x + halfWidth,
            layer.transform.y + halfHeight,
        )
    }

    /**
     * Bounding box that contains the layer *after* rotation — what the editor needs to keep a
     * rotated layer's handles on screen.
     */
    fun rotatedBounds(
        layer: Layer,
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
    ): RectF {
        val size = scaledSize(layer, scene, assets, widgets, timeMillis)
        val radians = Math.toRadians(layer.transform.rotation.toDouble())
        val cos = abs(cos(radians)).toFloat()
        val sin = abs(sin(radians)).toFloat()
        val width = size.width * cos + size.height * sin
        val height = size.width * sin + size.height * cos
        return RectF(
            layer.transform.x - width / 2f,
            layer.transform.y - height / 2f,
            layer.transform.x + width / 2f,
            layer.transform.y + height / 2f,
        )
    }

    /**
     * Whether a canvas-space point falls inside a layer, accounting for rotation.
     *
     * The point is rotated backwards into the layer's own space, which is cheaper and more accurate
     * than testing against the rotated bounding box — the latter would let a user grab a rotated
     * layer by its empty corners.
     */
    fun hitTest(
        layer: Layer,
        canvasX: Float,
        canvasY: Float,
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
        touchSlop: Float = 0f,
    ): Boolean {
        val size = scaledSize(layer, scene, assets, widgets, timeMillis)
        val dx = canvasX - layer.transform.x
        val dy = canvasY - layer.transform.y
        val radians = Math.toRadians(-layer.transform.rotation.toDouble())
        val localX = (dx * cos(radians) - dy * sin(radians)).toFloat()
        val localY = (dx * sin(radians) + dy * cos(radians)).toFloat()
        val halfWidth = size.width / 2f + touchSlop
        val halfHeight = size.height / 2f + touchSlop
        return abs(localX) <= halfWidth && abs(localY) <= halfHeight
    }

    /** Topmost layer under a point, so a tap selects what the user can actually see. */
    fun layerAt(
        scene: Scene,
        canvasX: Float,
        canvasY: Float,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
        touchSlop: Float = 0f,
    ): Layer? = scene.drawOrder
        .asReversed()
        .firstOrNull { layer ->
            layer.visible &&
                hitTest(layer, canvasX, canvasY, scene, assets, widgets, timeMillis, touchSlop)
        }

    /** Destination rect for content of [contentSize] fitted into [target] using [scaleMode] rules. */
    fun fit(
        contentWidth: Float,
        contentHeight: Float,
        target: RectF,
        cover: Boolean,
    ): RectF {
        if (contentWidth <= 0f || contentHeight <= 0f) return RectF(target)
        val scale = if (cover) {
            maxOf(target.width() / contentWidth, target.height() / contentHeight)
        } else {
            minOf(target.width() / contentWidth, target.height() / contentHeight)
        }
        val width = contentWidth * scale
        val height = contentHeight * scale
        val centerX = target.centerX()
        val centerY = target.centerY()
        return RectF(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f,
        )
    }
}
