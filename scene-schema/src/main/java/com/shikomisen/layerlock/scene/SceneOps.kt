package com.shikomisen.layerlock.scene

/**
 * Pure transformations over a [Scene].
 *
 * The editor is a plain "state in, state out" reducer over these — no mutation, so undo/redo is
 * just a stack of previous [Scene] values.
 */
object SceneOps {

    /** Z step between adjacent layers, leaving room to insert between two without a full renumber. */
    private const val Z_STEP = 10

    fun addLayer(scene: Scene, layer: Layer): Scene {
        val topZ = scene.layers.maxOfOrNull { it.z } ?: 0
        return scene.copy(layers = scene.layers + layer.withZ(topZ + Z_STEP))
    }

    fun removeLayer(scene: Scene, layerId: String): Scene =
        scene.copy(layers = scene.layers.filterNot { it.id == layerId })

    fun replaceLayer(scene: Scene, layer: Layer): Scene =
        scene.copy(layers = scene.layers.map { if (it.id == layer.id) layer else it })

    fun findLayer(scene: Scene, layerId: String?): Layer? =
        layerId?.let { id -> scene.layers.firstOrNull { it.id == id } }

    /**
     * Moves the layer at [fromIndex] to [toIndex] in the *front-to-back* list the layer panel shows
     * (index 0 = frontmost), then renumbers `z` so the stored order matches what the user sees.
     */
    fun reorder(scene: Scene, fromIndex: Int, toIndex: Int): Scene {
        val frontToBack = scene.drawOrder.reversed().toMutableList()
        if (fromIndex !in frontToBack.indices || toIndex !in frontToBack.indices) return scene
        frontToBack.add(toIndex, frontToBack.removeAt(fromIndex))
        return renumber(scene, frontToBack)
    }

    fun bringToFront(scene: Scene, layerId: String): Scene {
        val frontToBack = scene.drawOrder.reversed().toMutableList()
        val index = frontToBack.indexOfFirst { it.id == layerId }
        return if (index < 0) scene else reorder(scene, index, 0)
    }

    fun sendToBack(scene: Scene, layerId: String): Scene {
        val frontToBack = scene.drawOrder.reversed().toMutableList()
        val index = frontToBack.indexOfFirst { it.id == layerId }
        return if (index < 0) scene else reorder(scene, index, frontToBack.lastIndex)
    }

    /** Assigns evenly spaced `z` values to a front-to-back ordered list. */
    private fun renumber(scene: Scene, frontToBack: List<Layer>): Scene {
        val backToFront = frontToBack.reversed()
        val renumbered = backToFront.mapIndexed { index, layer -> layer.withZ((index + 1) * Z_STEP) }
        return scene.copy(layers = renumbered)
    }

    /**
     * Applies a drag/scale/rotate gesture.
     *
     * Snapping is applied here, at edit time, and only to the resulting x/y — which is exactly why
     * `gridSnapped` can stay pure editor metadata: whichever mode the user was in, the scene ends up
     * holding the same kind of absolute coordinate.
     */
    fun transformLayer(
        scene: Scene,
        layerId: String,
        dx: Float = 0f,
        dy: Float = 0f,
        scaleBy: Float = 1f,
        rotateBy: Float = 0f,
        snapToGrid: Boolean = false,
    ): Scene {
        val layer = findLayer(scene, layerId) ?: return scene
        val current = layer.transform
        val rawX = current.x + dx
        val rawY = current.y + dy
        val grid = scene.gridSize.coerceAtLeast(1)
        val x = if (snapToGrid) snap(rawX, grid) else rawX
        val y = if (snapToGrid) snap(rawY, grid) else rawY
        val moved = current.copy(
            x = x.coerceIn(-OFF_CANVAS_SLACK, scene.canvas.width + OFF_CANVAS_SLACK),
            y = y.coerceIn(-OFF_CANVAS_SLACK, scene.canvas.height + OFF_CANVAS_SLACK),
            scale = (current.scale * scaleBy).coerceIn(MIN_SCALE, MAX_SCALE),
            rotation = normaliseRotation(current.rotation + rotateBy),
        )
        return replaceLayer(scene, layer.withTransform(moved).withGridSnapped(snapToGrid))
    }

    private fun snap(value: Float, grid: Int): Float = Math.round(value / grid).toFloat() * grid

    private fun normaliseRotation(degrees: Float): Float {
        var result = degrees % 360f
        if (result < 0f) result += 360f
        return result
    }

    const val MIN_SCALE = 0.05f
    const val MAX_SCALE = 20f

    /**
     * Bounds on the non-uniform component alone.
     *
     * Tighter than the scale range because this is an aspect-ratio distortion, not a size: past
     * roughly 10:1 in either direction a layer stops being recognisable as the thing it was, and
     * the uniform [MAX_SCALE] is still there for making something genuinely huge.
     */
    const val MIN_STRETCH = 0.1f
    const val MAX_STRETCH = 10f

    /** Zoom bounds for a background. Never below 1, so its fit can never leave a gap. */
    const val MIN_BACKGROUND_ZOOM = 1f
    const val MAX_BACKGROUND_ZOOM = 4f

    /**
     * Pans and zooms the background within the overflow it actually has.
     *
     * [limitX] and [limitY] are the largest usable offsets, as a fraction of the canvas, and the
     * caller supplies them because working them out needs the source's intrinsic size — which this
     * module deliberately cannot measure. Clamping to them is what stops the background being
     * dragged off its own edge and leaving a gap.
     *
     * Note that a `COVER` background usually has overflow at zoom 1 already: filling a canvas of a
     * different shape crops one axis, and that crop is exactly what there is to pan through.
     */
    fun panBackground(
        scene: Scene,
        dx: Float,
        dy: Float,
        zoomBy: Float = 1f,
        limitX: Float = 0f,
        limitY: Float = 0f,
    ): Scene {
        val background = scene.background
        val zoom = (background.zoom * zoomBy)
            .coerceIn(MIN_BACKGROUND_ZOOM, MAX_BACKGROUND_ZOOM)
        return scene.copy(
            background = background.copy(
                zoom = zoom,
                offsetX = (background.offsetX + dx).coerceIn(-limitX, limitX),
                offsetY = (background.offsetY + dy).coerceIn(-limitY, limitY),
            ),
        )
    }

    /**
     * Resizes a layer by moving one handle, leaving the opposite edge or corner where it is.
     *
     * The caller works out the new half-extents (it needs [LayerGeometry] to know how big the layer
     * is unscaled, which this module deliberately cannot see); this applies them and clamps.
     */
    fun resizeLayer(
        scene: Scene,
        layerId: String,
        x: Float,
        y: Float,
        stretchX: Float,
        stretchY: Float,
    ): Scene {
        val layer = findLayer(scene, layerId) ?: return scene
        val transform = layer.transform.copy(
            x = x.coerceIn(-OFF_CANVAS_SLACK, scene.canvas.width + OFF_CANVAS_SLACK),
            y = y.coerceIn(-OFF_CANVAS_SLACK, scene.canvas.height + OFF_CANVAS_SLACK),
            stretchX = stretchX.coerceIn(MIN_STRETCH, MAX_STRETCH),
            stretchY = stretchY.coerceIn(MIN_STRETCH, MAX_STRETCH),
        )
        return replaceLayer(scene, layer.withTransform(transform))
    }

    /** How far past the canvas edge a layer's centre may sit before it stops being draggable. */
    private const val OFF_CANVAS_SLACK = 200f
}

/** Copies a layer with a new `z`, without each call site needing to know the concrete type. */
fun Layer.withZ(z: Int): Layer = when (this) {
    is ClockLayer -> copy(z = z)
    is DateLayer -> copy(z = z)
    is TextLayer -> copy(z = z)
    is ImageLayer -> copy(z = z)
    is VideoLayer -> copy(z = z)
    is GifLayer -> copy(z = z)
    is CutoutLayer -> copy(z = z)
    is WidgetLayer -> copy(z = z)
}

fun Layer.withTransform(transform: Transform): Layer = when (this) {
    is ClockLayer -> copy(transform = transform)
    is DateLayer -> copy(transform = transform)
    is TextLayer -> copy(transform = transform)
    is ImageLayer -> copy(transform = transform)
    is VideoLayer -> copy(transform = transform)
    is GifLayer -> copy(transform = transform)
    is CutoutLayer -> copy(transform = transform)
    is WidgetLayer -> copy(transform = transform)
}

fun Layer.withGridSnapped(gridSnapped: Boolean): Layer = when (this) {
    is ClockLayer -> copy(gridSnapped = gridSnapped)
    is DateLayer -> copy(gridSnapped = gridSnapped)
    is TextLayer -> copy(gridSnapped = gridSnapped)
    is ImageLayer -> copy(gridSnapped = gridSnapped)
    is VideoLayer -> copy(gridSnapped = gridSnapped)
    is GifLayer -> copy(gridSnapped = gridSnapped)
    is CutoutLayer -> copy(gridSnapped = gridSnapped)
    is WidgetLayer -> copy(gridSnapped = gridSnapped)
}

fun Layer.withVisible(visible: Boolean): Layer = when (this) {
    is ClockLayer -> copy(visible = visible)
    is DateLayer -> copy(visible = visible)
    is TextLayer -> copy(visible = visible)
    is ImageLayer -> copy(visible = visible)
    is VideoLayer -> copy(visible = visible)
    is GifLayer -> copy(visible = visible)
    is CutoutLayer -> copy(visible = visible)
    is WidgetLayer -> copy(visible = visible)
}

fun Layer.withOpacity(opacity: Float): Layer {
    val value = opacity.coerceIn(0f, 1f)
    return when (this) {
        is ClockLayer -> copy(opacity = value)
        is DateLayer -> copy(opacity = value)
        is TextLayer -> copy(opacity = value)
        is ImageLayer -> copy(opacity = value)
        is VideoLayer -> copy(opacity = value)
        is GifLayer -> copy(opacity = value)
        is CutoutLayer -> copy(opacity = value)
        is WidgetLayer -> copy(opacity = value)
    }
}

/** The text styling of a layer, for the layers that have any. */
val Layer.textStyle: TextStyleSpec?
    get() = when (this) {
        is ClockLayer -> style
        is DateLayer -> style
        is TextLayer -> style
        is WidgetLayer -> style
        else -> null
    }

fun Layer.withTextStyle(style: TextStyleSpec): Layer = when (this) {
    is ClockLayer -> copy(style = style)
    is DateLayer -> copy(style = style)
    is TextLayer -> copy(style = style)
    is WidgetLayer -> copy(style = style)
    else -> this
}

/** The media source of a layer, for the layers that have one. */
val Layer.sourceUriOrNull: String?
    get() = when (this) {
        is ImageLayer -> sourceUri
        is VideoLayer -> sourceUri
        is GifLayer -> sourceUri
        is CutoutLayer -> cutoutUri ?: sourceUri
        else -> null
    }
