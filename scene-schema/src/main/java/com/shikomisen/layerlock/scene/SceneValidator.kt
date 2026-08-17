package com.shikomisen.layerlock.scene

/**
 * Structural validation for scenes arriving from outside the app — imported preset files, or a
 * library entry written by an older build.
 *
 * This checks the shape of a scene, not whether its media still resolves: a `content://` URI can go
 * stale at any time, so renderers are expected to degrade gracefully on a missing source rather than
 * treat it as a validation failure.
 */
object SceneValidator {

    data class Issue(val field: String, val message: String)

    fun validate(scene: Scene): List<Issue> = buildList {
        if (scene.sceneId.isBlank()) add(Issue("sceneId", "Scene id must not be blank"))
        if (scene.name.isBlank()) add(Issue("name", "Scene name must not be blank"))
        if (scene.canvas.width <= 0 || scene.canvas.height <= 0) {
            add(Issue("canvas", "Canvas must have a positive width and height"))
        }
        if (scene.gridSize <= 0) add(Issue("gridSize", "Grid size must be positive"))

        val duplicateIds = scene.layers.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            add(Issue("layers", "Duplicate layer ids: ${duplicateIds.joinToString()}"))
        }

        if (scene.background.type == BackgroundType.IMAGE ||
            scene.background.type == BackgroundType.VIDEO
        ) {
            if (scene.background.sourceUri.isNullOrBlank()) {
                add(Issue("background.sourceUri", "A ${scene.background.type} background needs a source"))
            }
        }
        if (scene.background.dim !in 0f..1f) {
            add(Issue("background.dim", "Dim must be between 0 and 1"))
        }

        scene.layers.forEach { layer ->
            val prefix = "layers[${layer.id}]"
            if (layer.id.isBlank()) add(Issue(prefix, "Layer id must not be blank"))
            if (layer.opacity !in 0f..1f) add(Issue("$prefix.opacity", "Opacity must be between 0 and 1"))
            if (layer.transform.scale <= 0f) add(Issue("$prefix.transform.scale", "Scale must be positive"))
            if (layer.transform.stretchX <= 0f || layer.transform.stretchY <= 0f) {
                add(Issue("$prefix.transform.stretch", "Stretch must be positive"))
            }
            if (layer.sourceUriOrNull?.isBlank() == true) {
                add(Issue("$prefix.sourceUri", "${layer.displayName} layer needs a media source"))
            }
            layer.textStyle?.let { style ->
                if (style.fontSize <= 0f) add(Issue("$prefix.style.fontSize", "Font size must be positive"))
                if (style.weight !in 100..900) {
                    add(Issue("$prefix.style.weight", "Font weight must be between 100 and 900"))
                }
            }
        }
    }

    fun isValid(scene: Scene): Boolean = validate(scene).isEmpty()

    /**
     * Best-effort repair for an imported scene — clamps out-of-range values and de-duplicates ids so
     * a slightly wrong file still opens, rather than being rejected outright.
     */
    fun sanitise(scene: Scene): Scene {
        val seenIds = mutableSetOf<String>()
        val layers = scene.layers.map { layer ->
            val id = generateSequence(layer.id.ifBlank { "layer" }) { "${it}-copy" }
                .first { seenIds.add(it) }
            val repaired = if (id == layer.id) layer else layer.withId(id)
            repaired
                .withOpacity(repaired.opacity.coerceIn(0f, 1f))
                .withTransform(
                    repaired.transform.copy(
                        scale = repaired.transform.scale.coerceIn(SceneOps.MIN_SCALE, SceneOps.MAX_SCALE),
                        stretchX = repaired.transform.stretchX
                            .coerceIn(SceneOps.MIN_STRETCH, SceneOps.MAX_STRETCH),
                        stretchY = repaired.transform.stretchY
                            .coerceIn(SceneOps.MIN_STRETCH, SceneOps.MAX_STRETCH),
                    ),
                )
        }
        // Only the zoom and a sanity bound on the offsets. The real pan limit depends on the
        // source's intrinsic size, which this module cannot measure — the editor clamps properly,
        // and anything past a whole canvas of offset is corrupt rather than merely out of range.
        val background = scene.background.copy(
            zoom = scene.background.zoom
                .coerceIn(SceneOps.MIN_BACKGROUND_ZOOM, SceneOps.MAX_BACKGROUND_ZOOM),
            offsetX = scene.background.offsetX.coerceIn(-1f, 1f),
            offsetY = scene.background.offsetY.coerceIn(-1f, 1f),
        )

        return scene.copy(
            name = scene.name.ifBlank { "Untitled scene" },
            canvas = CanvasSize(
                width = scene.canvas.width.coerceAtLeast(1),
                height = scene.canvas.height.coerceAtLeast(1),
            ),
            gridSize = scene.gridSize.coerceAtLeast(1),
            background = background.copy(dim = background.dim.coerceIn(0f, 1f)),
            layers = layers,
        )
    }
}

internal fun Layer.withId(id: String): Layer = when (this) {
    is ClockLayer -> copy(id = id)
    is DateLayer -> copy(id = id)
    is TextLayer -> copy(id = id)
    is ImageLayer -> copy(id = id)
    is VideoLayer -> copy(id = id)
    is GifLayer -> copy(id = id)
    is CutoutLayer -> copy(id = id)
    is WidgetLayer -> copy(id = id)
}
