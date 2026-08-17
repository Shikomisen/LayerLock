package com.shikomisen.layerlock.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.graphics.Shader
import com.shikomisen.layerlock.scene.Background
import com.shikomisen.layerlock.scene.BackgroundType
import com.shikomisen.layerlock.scene.ClockLayer
import com.shikomisen.layerlock.scene.ColorSpec
import com.shikomisen.layerlock.scene.CutoutLayer
import com.shikomisen.layerlock.scene.DateLayer
import com.shikomisen.layerlock.scene.GifLayer
import com.shikomisen.layerlock.scene.ImageLayer
import com.shikomisen.layerlock.scene.Layer
import com.shikomisen.layerlock.scene.ScaleMode
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.TextLayer
import com.shikomisen.layerlock.scene.VideoLayer
import com.shikomisen.layerlock.scene.WidgetLayer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scene renderer — §5's "single custom Canvas-based scene renderer shared by the in-app editor
 * preview, the `WallpaperService` engine and the `setShowWhenLocked` lock Activity".
 *
 * It draws into a plain [Canvas], which is what makes that sharing possible: the editor hands it a
 * Compose drawing canvas, the wallpaper engine hands it a `lockCanvas()` surface canvas (or a bitmap
 * to upload as a GL texture), the lock Activity hands it the same as the editor, and PNG export hands
 * it a bitmap canvas. None of them need to know anything about the others.
 *
 * The renderer is stateless and synchronous. It never decodes, never allocates per frame beyond a
 * handful of reused paints, and never blocks — anything not yet decoded draws as a placeholder.
 */
class SceneCanvasRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scratchRect = RectF()
    private val scratchPath = Path()

    /** Everything a single frame needs that is not part of the scene itself. */
    data class Frame(
        val timeMillis: Long = System.currentTimeMillis(),
        val assets: AssetSource = AssetSource.Empty,
        val widgets: WidgetSnapshot = WidgetSnapshot(),
        /** Layers drawn live by the host surface (video textures) and skipped by this renderer. */
        val externallyDrawnLayerIds: Set<String> = emptySet(),
        /** Suppresses the background, for hosts that draw it themselves. */
        val skipBackground: Boolean = false,
        val watermark: Boolean = false,
        val editor: EditorOverlay? = null,
    )

    /** Editor-only chrome. Never set by the wallpaper or lock-screen hosts. */
    data class EditorOverlay(
        val selectedLayerId: String? = null,
        val showGrid: Boolean = false,
        val showBounds: Boolean = true,
        val gridSize: Int = 48,
        /** Alignment guides for the edge the dragged layer just snapped to. Transient. */
        val guides: List<Guide> = emptyList(),
    )

    /**
     * A single alignment guide, in scene coordinates.
     *
     * Drawn only while a snap is actually in effect. A snap the user cannot see reads as the drag
     * being laggy or imprecise rather than as a feature, which is the whole reason this exists.
     */
    data class Guide(val vertical: Boolean, val position: Float)

    /**
     * Draws [scene] into [canvas], scaled to fill a [viewWidth] x [viewHeight] surface.
     *
     * The scene's own canvas size is the authoring coordinate space; the renderer maps it onto
     * whatever surface it is given, so a scene authored at 1080x2400 renders correctly on a
     * 1440x3200 screen without any of the layer maths changing.
     */
    fun draw(
        canvas: Canvas,
        scene: Scene,
        viewWidth: Float,
        viewHeight: Float,
        frame: Frame = Frame(),
    ) {
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val save = canvas.save()
        // Cover rather than fit: a wallpaper with letterboxing at the top would look broken.
        val scale = maxOf(
            viewWidth / scene.canvas.width.toFloat(),
            viewHeight / scene.canvas.height.toFloat(),
        )
        canvas.translate(
            (viewWidth - scene.canvas.width * scale) / 2f,
            (viewHeight - scene.canvas.height * scale) / 2f,
        )
        canvas.scale(scale, scale)

        if (!frame.skipBackground) {
            drawBackground(canvas, scene, frame)
        }

        frame.editor?.takeIf { it.showGrid }?.let { drawGrid(canvas, scene, it) }

        scene.drawOrder.forEach { layer ->
            if (!layer.visible) return@forEach
            if (layer.id in frame.externallyDrawnLayerIds) return@forEach
            drawLayer(canvas, scene, layer, frame)
        }

        frame.editor?.let { drawEditorChrome(canvas, scene, it, frame) }
        frame.editor?.takeIf { it.guides.isNotEmpty() }?.let { drawGuides(canvas, scene, it) }
        if (frame.watermark) drawWatermark(canvas, scene)

        canvas.restoreToCount(save)
    }

    /**
     * Draws only the layers in [layerIds]. Hosts that interleave live video textures with canvas
     * content use this to paint the bands above and below each video.
     */
    fun drawLayers(
        canvas: Canvas,
        scene: Scene,
        layerIds: Set<String>,
        viewWidth: Float,
        viewHeight: Float,
        frame: Frame = Frame(),
    ) {
        val subset = scene.copy(layers = scene.layers.filter { it.id in layerIds })
        draw(canvas, subset, viewWidth, viewHeight, frame)
    }

    // -- Background ---------------------------------------------------------------------------

    private fun drawBackground(canvas: Canvas, scene: Scene, frame: Frame) {
        val background = scene.background
        scratchRect.set(0f, 0f, scene.canvas.width.toFloat(), scene.canvas.height.toFloat())

        when (background.type) {
            BackgroundType.COLOR -> {
                fillPaint.reset()
                fillPaint.isAntiAlias = true
                fillPaint.color = ColorSpec.parse(background.color)
                canvas.drawRect(scratchRect, fillPaint)
            }

            BackgroundType.GRADIENT -> drawGradient(canvas, background, scratchRect)

            BackgroundType.IMAGE, BackgroundType.VIDEO -> {
                val bitmap = background.sourceUri?.let { frame.assets.bitmap(it) }
                if (bitmap == null) {
                    drawGradient(canvas, background, scratchRect)
                } else {
                    drawBitmapFitted(
                        canvas = canvas,
                        bitmap = bitmap,
                        target = scratchRect,
                        scaleMode = background.scaleMode,
                        opacity = 1f,
                        background = background,
                    )
                }
            }
        }

        if (background.dim > 0f) {
            overlayPaint.reset()
            overlayPaint.color = Color.argb((background.dim.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)
            canvas.drawRect(scratchRect, overlayPaint)
        }
    }

    private fun drawGradient(canvas: Canvas, background: Background, bounds: RectF) {
        val radians = Math.toRadians(background.gradientAngle.toDouble())
        val halfWidth = bounds.width() / 2f
        val halfHeight = bounds.height() / 2f
        val dx = (cos(radians) * halfWidth).toFloat()
        val dy = (sin(radians) * halfHeight).toFloat()

        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.shader = LinearGradient(
            bounds.centerX() - dx,
            bounds.centerY() - dy,
            bounds.centerX() + dx,
            bounds.centerY() + dy,
            ColorSpec.parse(background.color),
            ColorSpec.parse(background.colorEnd),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(bounds, fillPaint)
        fillPaint.shader = null
    }

    // -- Layers -------------------------------------------------------------------------------

    private fun drawLayer(canvas: Canvas, scene: Scene, layer: Layer, frame: Frame) {
        val save = canvas.save()
        canvas.translate(layer.transform.x, layer.transform.y)
        canvas.rotate(layer.transform.rotation)
        canvas.scale(layer.transform.effectiveScaleX, layer.transform.effectiveScaleY)

        when (layer) {
            is ClockLayer -> TextPainter.draw(
                canvas,
                ClockFormatter.format(layer.pattern, frame.timeMillis),
                layer.style,
                layer.opacity,
            )

            is DateLayer -> TextPainter.draw(
                canvas,
                ClockFormatter.format(layer.pattern, frame.timeMillis),
                layer.style,
                layer.opacity,
            )

            is TextLayer -> TextPainter.draw(canvas, layer.text, layer.style, layer.opacity)

            is WidgetLayer -> TextPainter.draw(
                canvas,
                LayerGeometry.widgetText(layer, frame.widgets),
                layer.style,
                layer.opacity,
            )

            is ImageLayer -> drawMedia(
                canvas,
                scene,
                layer,
                frame,
                layer.sourceUri,
                layer.cornerRadius,
            )

            is CutoutLayer -> drawMedia(
                canvas,
                scene,
                layer,
                frame,
                layer.cutoutUri ?: layer.sourceUri,
                0f,
            )

            is VideoLayer -> drawMedia(
                canvas,
                scene,
                layer,
                frame,
                layer.sourceUri,
                layer.cornerRadius,
            )

            is GifLayer -> drawAnimated(canvas, scene, layer, frame)
        }

        canvas.restoreToCount(save)
    }

    /**
     * Draws a still-image layer, or the poster frame of a video layer that no live texture is
     * covering — which is what makes a PNG export of a video scene produce something sensible.
     */
    private fun drawMedia(
        canvas: Canvas,
        scene: Scene,
        layer: Layer,
        frame: Frame,
        uri: String,
        cornerRadius: Float,
    ) {
        val base = LayerGeometry.baseSize(layer, scene, frame.assets, frame.widgets, frame.timeMillis)
        scratchRect.set(-base.width / 2f, -base.height / 2f, base.width / 2f, base.height / 2f)

        val bitmap = frame.assets.bitmap(uri)
        if (bitmap == null) {
            drawPlaceholder(canvas, scratchRect, layer.opacity)
            return
        }

        bitmapPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
        if (cornerRadius > 0f) {
            val save = canvas.save()
            scratchPath.reset()
            scratchPath.addRoundRect(scratchRect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(scratchPath)
            canvas.drawBitmap(bitmap, null, scratchRect, bitmapPaint)
            canvas.restoreToCount(save)
        } else {
            canvas.drawBitmap(bitmap, null, scratchRect, bitmapPaint)
        }
        bitmapPaint.alpha = 255
    }

    private fun drawAnimated(canvas: Canvas, scene: Scene, layer: GifLayer, frame: Frame) {
        val base = LayerGeometry.baseSize(layer, scene, frame.assets, frame.widgets, frame.timeMillis)
        scratchRect.set(-base.width / 2f, -base.height / 2f, base.width / 2f, base.height / 2f)

        val drawable = frame.assets.drawable(layer.sourceUri)
        if (drawable == null) {
            // A single-frame GIF decodes to a bitmap instead; fall back to that before giving up.
            drawMedia(canvas, scene, layer, frame, layer.sourceUri, 0f)
            return
        }

        val save = canvas.save()
        canvas.translate(scratchRect.left, scratchRect.top)
        val scaleX = scratchRect.width() / drawable.intrinsicWidth.coerceAtLeast(1)
        val scaleY = scratchRect.height() / drawable.intrinsicHeight.coerceAtLeast(1)
        canvas.scale(scaleX, scaleY)
        drawable.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }

    /** Shown while media decodes, or permanently if its source has gone away. */
    private fun drawPlaceholder(canvas: Canvas, bounds: RectF, opacity: Float) {
        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.color = Color.argb((40 * opacity).toInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawRoundRect(bounds, 24f, 24f, fillPaint)

        strokePaint.reset()
        strokePaint.style = Paint.Style.STROKE
        strokePaint.isAntiAlias = true
        strokePaint.strokeWidth = 3f
        strokePaint.color = Color.argb((90 * opacity).toInt().coerceIn(0, 255), 255, 255, 255)
        strokePaint.pathEffect = DASHED
        canvas.drawRoundRect(bounds, 24f, 24f, strokePaint)
        strokePaint.pathEffect = null
    }

    // -- Editor chrome ------------------------------------------------------------------------

    private fun drawGrid(canvas: Canvas, scene: Scene, overlay: EditorOverlay) {
        val step = overlay.gridSize.coerceAtLeast(4).toFloat()
        strokePaint.reset()
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 1.5f
        strokePaint.color = Color.argb(38, 255, 255, 255)

        var x = 0f
        while (x <= scene.canvas.width) {
            canvas.drawLine(x, 0f, x, scene.canvas.height.toFloat(), strokePaint)
            x += step
        }
        var y = 0f
        while (y <= scene.canvas.height) {
            canvas.drawLine(0f, y, scene.canvas.width.toFloat(), y, strokePaint)
            y += step
        }
    }

    /** Full-bleed lines through whatever the dragged layer is currently aligned to. */
    private fun drawGuides(canvas: Canvas, scene: Scene, overlay: EditorOverlay) {
        strokePaint.reset()
        strokePaint.style = Paint.Style.STROKE
        strokePaint.isAntiAlias = true
        strokePaint.strokeWidth = 3f
        strokePaint.color = GUIDE_COLOR

        overlay.guides.forEach { guide ->
            if (guide.vertical) {
                canvas.drawLine(guide.position, 0f, guide.position, scene.canvas.height.toFloat(), strokePaint)
            } else {
                canvas.drawLine(0f, guide.position, scene.canvas.width.toFloat(), guide.position, strokePaint)
            }
        }
    }

    private fun drawEditorChrome(
        canvas: Canvas,
        scene: Scene,
        overlay: EditorOverlay,
        frame: Frame,
    ) {
        if (!overlay.showBounds) return
        val selected = scene.layers.firstOrNull { it.id == overlay.selectedLayerId } ?: return

        val size = LayerGeometry.scaledSize(
            selected,
            scene,
            frame.assets,
            frame.widgets,
            frame.timeMillis,
        )

        val save = canvas.save()
        canvas.translate(selected.transform.x, selected.transform.y)
        canvas.rotate(selected.transform.rotation)
        scratchRect.set(-size.width / 2f, -size.height / 2f, size.width / 2f, size.height / 2f)

        strokePaint.reset()
        strokePaint.style = Paint.Style.STROKE
        strokePaint.isAntiAlias = true
        strokePaint.strokeWidth = 4f
        strokePaint.color = SELECTION_COLOR
        canvas.drawRect(scratchRect, strokePaint)

        // Rotation handle, on a stalk out from the top edge. Drawn before the resize handles so the
        // top-centre handle sits on top of where the stalk meets it.
        val stalkTop = -size.height / 2f - LayerGeometry.ROTATION_HANDLE_OFFSET
        canvas.drawLine(0f, -size.height / 2f, 0f, stalkTop, strokePaint)

        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.color = SELECTION_COLOR
        canvas.drawCircle(0f, stalkTop, ROTATION_HANDLE_RADIUS, fillPaint)
        // Corners move a corner, edges move one side. Drawn as different shapes so which is which
        // is readable before the user has dragged anything.
        LayerGeometry.Handle.entries.forEach { handle ->
            val handleX = size.width / 2f * handle.dirX
            val handleY = size.height / 2f * handle.dirY
            if (handle.isCorner) {
                canvas.drawCircle(handleX, handleY, HANDLE_RADIUS, fillPaint)
            } else {
                scratchRect.set(
                    handleX - EDGE_HANDLE_HALF_LENGTH * abs(handle.dirY) - EDGE_HANDLE_HALF_THICKNESS * abs(handle.dirX),
                    handleY - EDGE_HANDLE_HALF_LENGTH * abs(handle.dirX) - EDGE_HANDLE_HALF_THICKNESS * abs(handle.dirY),
                    handleX + EDGE_HANDLE_HALF_LENGTH * abs(handle.dirY) + EDGE_HANDLE_HALF_THICKNESS * abs(handle.dirX),
                    handleY + EDGE_HANDLE_HALF_LENGTH * abs(handle.dirX) + EDGE_HANDLE_HALF_THICKNESS * abs(handle.dirY),
                )
                canvas.drawRoundRect(scratchRect, HANDLE_RADIUS, HANDLE_RADIUS, fillPaint)
            }
        }

        canvas.restoreToCount(save)
    }

    private fun drawWatermark(canvas: Canvas, scene: Scene) {
        val save = canvas.save()
        canvas.translate(scene.canvas.width / 2f, scene.canvas.height - WATERMARK_MARGIN)
        TextPainter.draw(
            canvas,
            "Made with LayerLock",
            com.shikomisen.layerlock.scene.TextStyleSpec(
                fontSize = 34f,
                color = "#8CFFFFFF",
                weight = 500,
                letterSpacing = 0.06f,
                shadow = true,
                shadowRadius = 8f,
            ),
        )
        canvas.restoreToCount(save)
    }

    private fun drawBitmapFitted(
        canvas: Canvas,
        bitmap: Bitmap,
        target: RectF,
        scaleMode: ScaleMode,
        opacity: Float,
        background: Background? = null,
    ) {
        bitmapPaint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        val fitted = when (scaleMode) {
            ScaleMode.STRETCH -> target
            ScaleMode.COVER -> LayerGeometry.fit(
                bitmap.width.toFloat(),
                bitmap.height.toFloat(),
                target,
                cover = true,
            )

            ScaleMode.CONTAIN -> LayerGeometry.fit(
                bitmap.width.toFloat(),
                bitmap.height.toFloat(),
                target,
                cover = false,
            )
        }

        // The pan/zoom is applied to the already-fitted rect rather than folded into the fit, so
        // "cover" still decides the base framing and this only adjusts it.
        val destination = if (background == null) {
            fitted
        } else {
            val zoom = background.zoom.coerceAtLeast(1f)
            val width = fitted.width() * zoom
            val height = fitted.height() * zoom
            val centerX = fitted.centerX() + background.offsetX * target.width()
            val centerY = fitted.centerY() + background.offsetY * target.height()
            RectF(
                centerX - width / 2f,
                centerY - height / 2f,
                centerX + width / 2f,
                centerY + height / 2f,
            )
        }

        val save = canvas.save()
        canvas.clipRect(target)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        canvas.restoreToCount(save)
        bitmapPaint.alpha = 255
    }

    private companion object {
        val SELECTION_COLOR = Color.argb(255, 120, 190, 255)

        /** Warm, so a guide reads as a different kind of thing from the selection box. */
        val GUIDE_COLOR = Color.argb(230, 255, 145, 90)
        const val HANDLE_RADIUS = 14f

        /** Edge handles are drawn as short bars along their edge, to read as one-axis controls. */
        const val EDGE_HANDLE_HALF_LENGTH = 26f
        const val EDGE_HANDLE_HALF_THICKNESS = 9f

        /** Slightly larger than a resize handle, since it is the only one that is not on the box. */
        const val ROTATION_HANDLE_RADIUS = 17f
        const val WATERMARK_MARGIN = 90f
        val DASHED: PathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
}
