package com.shikomisen.layerlock.canvas

import android.graphics.PointF
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
import kotlin.math.atan2
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
        val transform = layer.transform
        return SizeF(
            base.width * transform.effectiveScaleX,
            base.height * transform.effectiveScaleY,
        )
    }

    /**
     * The eight resize handles, as directions in the layer's own unrotated space.
     *
     * `0` on an axis means that axis is not touched by the handle, which is what makes the edge
     * handles single-axis and falls out of the same maths as the corners.
     */
    enum class Handle(val dirX: Int, val dirY: Int) {
        TOP_LEFT(-1, -1),
        TOP(0, -1),
        TOP_RIGHT(1, -1),
        LEFT(-1, 0),
        RIGHT(1, 0),
        BOTTOM_LEFT(-1, 1),
        BOTTOM(0, 1),
        BOTTOM_RIGHT(1, 1),
        ;

        val isCorner: Boolean get() = dirX != 0 && dirY != 0
    }

    /** Result of dragging a handle: where the layer ends up and how stretched it becomes. */
    data class Resize(
        val x: Float,
        val y: Float,
        val stretchX: Float,
        val stretchY: Float,
    )

    /** Smallest a layer may be dragged down to, in canvas px, before the handle stops shrinking it. */
    private const val MIN_RESIZE_EXTENT = 12f

    /**
     * How far beyond the top edge the rotation handle floats, in canvas px.
     *
     * Far enough that it never collides with the top edge handle's touch target, and far enough
     * from the centre that a small movement of the finger is a small change in angle.
     */
    const val ROTATION_HANDLE_OFFSET = 110f

    /** Scene-space position of the rotation handle, straight out from the layer's top edge. */
    fun rotationHandlePosition(
        layer: Layer,
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
    ): PointF {
        val size = scaledSize(layer, scene, assets, widgets, timeMillis)
        val local = rotate(
            0f,
            -(size.height / 2f + ROTATION_HANDLE_OFFSET),
            layer.transform.rotation,
        )
        return PointF(layer.transform.x + local.x, layer.transform.y + local.y)
    }

    /**
     * The layer rotation that puts its rotation handle under ([targetX], [targetY]).
     *
     * The handle sits straight up from the centre, which is a quarter turn from the zero direction
     * of [atan2] — hence the 90.
     */
    fun rotationTowards(layer: Layer, targetX: Float, targetY: Float): Float {
        val degrees = Math.toDegrees(
            atan2(
                (targetY - layer.transform.y).toDouble(),
                (targetX - layer.transform.x).toDouble(),
            ),
        ).toFloat() + 90f
        val normalised = degrees % 360f
        return if (normalised < 0f) normalised + 360f else normalised
    }

    /** Scene-space positions of every handle, in the order of [Handle.entries]. */
    fun handlePositions(
        layer: Layer,
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
    ): List<Pair<Handle, PointF>> {
        val size = scaledSize(layer, scene, assets, widgets, timeMillis)
        val halfWidth = size.width / 2f
        val halfHeight = size.height / 2f
        val transform = layer.transform
        return Handle.entries.map { handle ->
            val local = rotate(
                handle.dirX * halfWidth,
                handle.dirY * halfHeight,
                transform.rotation,
            )
            handle to PointF(transform.x + local.x, transform.y + local.y)
        }
    }

    /**
     * Where [layer] ends up when [handle] is dragged to ([targetX], [targetY]) in scene space.
     *
     * The opposite corner — or, for an edge handle, the opposite edge — is treated as an anchor and
     * stays exactly where it is, so the layer grows out from the side you are not holding. That is
     * the difference between this and a pinch: a pinch scales uniformly about the centre and leaves
     * the layer where it was, while this moves one boundary and lets the centre follow.
     *
     * Everything is computed in the layer's own rotated frame, which is what keeps a rotated layer's
     * handles pulling along its own axes rather than the screen's.
     */
    fun resizeFromHandle(
        handle: Handle,
        layer: Layer,
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
        targetX: Float,
        targetY: Float,
    ): Resize {
        val base = baseSize(layer, scene, assets, widgets, timeMillis)
        val transform = layer.transform
        val halfWidth = base.width * transform.effectiveScaleX / 2f
        val halfHeight = base.height * transform.effectiveScaleY / 2f

        val anchorLocal = rotate(-handle.dirX * halfWidth, -handle.dirY * halfHeight, transform.rotation)
        val anchorX = transform.x + anchorLocal.x
        val anchorY = transform.y + anchorLocal.y

        // The drag, expressed along the layer's own axes.
        val along = rotate(targetX - anchorX, targetY - anchorY, -transform.rotation)

        val newHalfWidth = if (handle.dirX != 0) {
            abs(along.x).coerceAtLeast(MIN_RESIZE_EXTENT) / 2f
        } else {
            halfWidth
        }
        val newHalfHeight = if (handle.dirY != 0) {
            abs(along.y).coerceAtLeast(MIN_RESIZE_EXTENT) / 2f
        } else {
            halfHeight
        }

        val centreLocal = rotate(
            handle.dirX * newHalfWidth,
            handle.dirY * newHalfHeight,
            transform.rotation,
        )

        return Resize(
            x = anchorX + centreLocal.x,
            y = anchorY + centreLocal.y,
            // Only the stretch changes; the uniform scale keeps whatever the user set it to.
            stretchX = if (base.width > 0f && transform.scale > 0f) {
                newHalfWidth * 2f / (base.width * transform.scale)
            } else {
                transform.stretchX
            },
            stretchY = if (base.height > 0f && transform.scale > 0f) {
                newHalfHeight * 2f / (base.height * transform.scale)
            } else {
                transform.stretchY
            },
        )
    }

    /** Rotates a vector by [degrees], matching the convention [hitTest] inverts. */
    private fun rotate(x: Float, y: Float, degrees: Float): PointF {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        return PointF(x * cos - y * sin, x * sin + y * cos)
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
