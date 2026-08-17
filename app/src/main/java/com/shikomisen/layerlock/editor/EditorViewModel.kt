package com.shikomisen.layerlock.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shikomisen.layerlock.canvas.CutoutProcessor
import com.shikomisen.layerlock.canvas.FontCatalog
import com.shikomisen.layerlock.canvas.LayerGeometry
import com.shikomisen.layerlock.canvas.SceneAssets
import com.shikomisen.layerlock.canvas.SceneCanvasRenderer
import com.shikomisen.layerlock.canvas.SceneExporter
import com.shikomisen.layerlock.canvas.WidgetDataSource
import com.shikomisen.layerlock.canvas.WidgetSnapshot
import com.shikomisen.layerlock.data.LayerLockGraph
import com.shikomisen.layerlock.data.MediaImporter
import com.shikomisen.layerlock.data.pro.ProFeature
import com.shikomisen.layerlock.scene.Background
import com.shikomisen.layerlock.scene.BackgroundType
import com.shikomisen.layerlock.scene.ClockLayer
import com.shikomisen.layerlock.scene.CutoutLayer
import com.shikomisen.layerlock.scene.DateLayer
import com.shikomisen.layerlock.scene.GifLayer
import com.shikomisen.layerlock.scene.ImageLayer
import com.shikomisen.layerlock.scene.Layer
import com.shikomisen.layerlock.scene.ScaleMode
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.SceneOps
import com.shikomisen.layerlock.scene.ScreenTarget
import com.shikomisen.layerlock.scene.TextLayer
import com.shikomisen.layerlock.scene.TextStyleSpec
import com.shikomisen.layerlock.scene.Transform
import com.shikomisen.layerlock.scene.VideoLayer
import com.shikomisen.layerlock.scene.WidgetKind
import com.shikomisen.layerlock.scene.WidgetLayer
import com.shikomisen.layerlock.scene.withOpacity
import com.shikomisen.layerlock.scene.withTextStyle
import com.shikomisen.layerlock.scene.withTransform
import com.shikomisen.layerlock.scene.withVisible
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Editor state.
 *
 * The scene is held as an immutable value and every edit is a pure transformation of it (see
 * `SceneOps`), which makes undo a stack of previous values rather than a set of inverse operations.
 */
class EditorViewModel(
    application: Application,
    private val sceneId: String,
) : AndroidViewModel(application) {

    private val sceneRepository = LayerLockGraph.sceneRepository(application)
    private val settingsRepository = LayerLockGraph.settingsRepository(application)
    private val entitlements = LayerLockGraph.entitlements(application)

    private val mediaImporter = MediaImporter(application)
    private val exporter = SceneExporter(application)
    private val cutoutProcessor = CutoutProcessor(application)
    private val widgetDataSource = WidgetDataSource(application)

    val assets = SceneAssets(application, viewModelScope)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val undoStack = ArrayDeque<Scene>()
    private val redoStack = ArrayDeque<Scene>()

    /**
     * The scene as it exists on disk.
     *
     * Edits are held in memory until [save], so this is what "unsaved changes" is measured against
     * and what [discard] returns to.
     */
    private var savedScene: Scene? = null

    /** Unsnapped drag position for the gesture in flight. See [transformSelected]. */
    private var dragOrigin: Pair<Float, Float>? = null

    init {
        viewModelScope.launch {
            val scene = sceneRepository.snapshot().scene(sceneId)
            if (scene == null) {
                _events.send(EditorEvent.SceneMissing)
            } else {
                savedScene = scene
                _uiState.value = _uiState.value.copy(scene = scene, isDirty = false)
                assets.prepare(scene)
            }
        }

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(
                    snapToGrid = settings.snapToGrid,
                    showGrid = settings.snapToGrid,
                    snapToObjects = settings.snapToObjects,
                    showBounds = settings.showLayerBounds,
                )
            }
        }

        viewModelScope.launch {
            entitlements.status.collect { status ->
                _uiState.value = _uiState.value.copy(isPro = status.isPro)
            }
        }

        refreshWidgets()
    }

    // -- Saving -------------------------------------------------------------------------------

    /**
     * Writes the scene to disk.
     *
     * Editing used to write continuously on a debounce, which meant there was no such thing as
     * abandoning an experiment — every stray drag was already permanent by the time the user
     * decided they disliked it. Saving is explicit now, and [EditorScreen] asks before discarding.
     */
    fun save(onSaved: (() -> Unit)? = null) {
        val scene = _uiState.value.scene ?: return
        viewModelScope.launch {
            sceneRepository.upsert(scene)
            savedScene = scene
            _uiState.value = _uiState.value.copy(isDirty = false)
            _events.send(EditorEvent.Message("Saved"))
            onSaved?.invoke()
        }
    }

    /** Throws away in-memory edits and returns to the last saved state. */
    fun discard() {
        val scene = savedScene ?: return
        undoStack.clear()
        redoStack.clear()
        _uiState.value = _uiState.value.copy(
            scene = scene,
            isDirty = false,
            canUndo = false,
            canRedo = false,
            activeGuides = emptyList(),
            selectedLayerId = _uiState.value.selectedLayerId
                ?.takeIf { id -> scene.layers.any { it.id == id } },
        )
        assets.prepare(scene)
    }

    private fun refreshWidgets() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(widgets = widgetDataSource.current())
        }
    }

    // -- Editing ------------------------------------------------------------------------------

    private fun mutate(recordUndo: Boolean = true, transform: (Scene) -> Scene) {
        val current = _uiState.value.scene ?: return
        val updated = transform(current)
        if (updated == current) return

        if (recordUndo) {
            undoStack.addLast(current)
            if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
            redoStack.clear()
        }

        _uiState.value = _uiState.value.copy(
            scene = updated,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            isDirty = updated != savedScene,
        )
        assets.prepare(updated)
    }

    fun select(layerId: String?) {
        dragOrigin = null
        _uiState.value = _uiState.value.copy(selectedLayerId = layerId)
    }

    /**
     * Applies a drag/pinch/rotate gesture to the selected layer.
     *
     * Undo is recorded once per gesture, not per pointer event — [gestureStart] is called when the
     * gesture begins, so a whole drag collapses into a single undo step.
     */
    fun gestureStart() {
        val current = _uiState.value.scene ?: return
        dragOrigin = null
        undoStack.addLast(current)
        if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        _uiState.value = _uiState.value.copy(canUndo = true, canRedo = false)
    }

    fun transformSelected(dx: Float = 0f, dy: Float = 0f, scaleBy: Float = 1f, rotateBy: Float = 0f) {
        val layerId = _uiState.value.selectedLayerId ?: return
        val current = SceneOps.findLayer(_uiState.value.scene ?: return, layerId) ?: return
        var guides: List<SceneCanvasRenderer.Guide> = emptyList()

        // The finger's position, free of any snapping that has been applied to the layer. Feeding
        // the drag back in from the *snapped* position would weld the layer to the first guide it
        // touched: each frame's small delta would land back inside the tolerance and snap again, so
        // the only way off a guide would be to move further in one frame than the tolerance itself.
        val (deltaX, deltaY) = if (dx != 0f || dy != 0f) {
            val raw = dragOrigin ?: (current.transform.x to current.transform.y)
            val next = (raw.first + dx) to (raw.second + dy)
            dragOrigin = next
            (next.first - current.transform.x) to (next.second - current.transform.y)
        } else {
            0f to 0f
        }

        mutate(recordUndo = false) { scene ->
            val moved = SceneOps.transformLayer(
                scene = scene,
                layerId = layerId,
                dx = deltaX,
                dy = deltaY,
                scaleBy = scaleBy,
                rotateBy = rotateBy,
                snapToGrid = _uiState.value.snapToGrid,
            )
            if (!_uiState.value.snapToObjects) return@mutate moved

            val layer = SceneOps.findLayer(moved, layerId) ?: return@mutate moved
            var transform = layer.transform

            if (rotateBy != 0f) {
                transform = transform.copy(rotation = EditorSnapping.snapRotation(transform.rotation))
            }
            // Only while the layer is actually being moved or resized — snapping position during a
            // pure rotation would drag the layer sideways as it turned.
            if (dx != 0f || dy != 0f || scaleBy != 1f) {
                val snapped = EditorSnapping.snapPosition(
                    scene = moved,
                    layer = layer.withTransform(transform),
                    x = transform.x,
                    y = transform.y,
                    assets = assets,
                    widgets = _uiState.value.widgets,
                    timeMillis = System.currentTimeMillis(),
                )
                transform = transform.copy(x = snapped.x, y = snapped.y)
                guides = snapped.guides
            }

            SceneOps.replaceLayer(moved, layer.withTransform(transform))
        }

        _uiState.value = _uiState.value.copy(activeGuides = guides)
    }

    /**
     * Drags one resize handle to a scene-space point.
     *
     * Absolute rather than incremental: the handle goes where the finger is, and the opposite edge
     * stays put. Accumulating deltas here would drift away from the pointer over a long drag, which
     * is very visible when the thing you are dragging is an edge you can see.
     */
    fun resizeSelected(handle: LayerGeometry.Handle, sceneX: Float, sceneY: Float) {
        val layerId = _uiState.value.selectedLayerId ?: return
        var guides: List<SceneCanvasRenderer.Guide> = emptyList()

        mutate(recordUndo = false) { scene ->
            val layer = SceneOps.findLayer(scene, layerId) ?: return@mutate scene
            val now = System.currentTimeMillis()

            var targetX = sceneX
            var targetY = sceneY
            if (_uiState.value.snapToObjects) {
                val snapped = EditorSnapping.snapPoint(
                    scene = scene,
                    excludeLayerId = layerId,
                    x = sceneX,
                    y = sceneY,
                    assets = assets,
                    widgets = _uiState.value.widgets,
                    timeMillis = now,
                )
                // An edge handle only moves along one axis, so only that axis' guide is real.
                targetX = if (handle.dirX != 0) snapped.x else sceneX
                targetY = if (handle.dirY != 0) snapped.y else sceneY
                guides = snapped.guides.filter {
                    if (it.vertical) handle.dirX != 0 else handle.dirY != 0
                }
            }

            val resized = LayerGeometry.resizeFromHandle(
                handle = handle,
                layer = layer,
                scene = scene,
                assets = assets,
                widgets = _uiState.value.widgets,
                timeMillis = now,
                targetX = targetX,
                targetY = targetY,
            )

            SceneOps.resizeLayer(
                scene = scene,
                layerId = layerId,
                x = resized.x,
                y = resized.y,
                stretchX = resized.stretchX,
                stretchY = resized.stretchY,
            )
        }

        _uiState.value = _uiState.value.copy(activeGuides = guides)
    }

    /** Clears any non-uniform stretch, returning the layer to its natural aspect ratio. */
    fun resetSelectedStretch() {
        val layerId = _uiState.value.selectedLayerId ?: return
        mutate { scene ->
            val layer = SceneOps.findLayer(scene, layerId) ?: return@mutate scene
            SceneOps.replaceLayer(
                scene,
                layer.withTransform(layer.transform.copy(stretchX = 1f, stretchY = 1f)),
            )
        }
    }

    /** Clears transient drag state. Called when a canvas gesture finishes. */
    fun gestureEnd() {
        dragOrigin = null
        if (_uiState.value.activeGuides.isEmpty()) return
        _uiState.value = _uiState.value.copy(activeGuides = emptyList())
    }

    /** Absolute setters, for the inspector sliders. Gestures use the relative path above. */
    fun setSelectedScale(scale: Float) {
        val layerId = _uiState.value.selectedLayerId ?: return
        mutate(recordUndo = false) { scene ->
            val layer = SceneOps.findLayer(scene, layerId) ?: return@mutate scene
            SceneOps.replaceLayer(
                scene,
                layer.withTransform(
                    layer.transform.copy(
                        scale = scale.coerceIn(SceneOps.MIN_SCALE, SceneOps.MAX_SCALE),
                    ),
                ),
            )
        }
    }

    fun setSelectedRotation(degrees: Float) {
        val layerId = _uiState.value.selectedLayerId ?: return
        val wrapped = degrees % 360f
        val positive = if (wrapped < 0f) wrapped + 360f else wrapped
        val rotation = if (_uiState.value.snapToObjects) {
            EditorSnapping.snapRotation(positive)
        } else {
            positive
        }
        mutate(recordUndo = false) { scene ->
            val layer = SceneOps.findLayer(scene, layerId) ?: return@mutate scene
            SceneOps.replaceLayer(scene, layer.withTransform(layer.transform.copy(rotation = rotation)))
        }
    }

    /**
     * Points the selected layer's rotation handle at a scene-space position.
     *
     * Absolute like [resizeSelected], for the same reason: the handle should stay under the finger
     * rather than drifting away from it over a long drag.
     */
    fun rotateSelectedTowards(sceneX: Float, sceneY: Float) {
        val scene = _uiState.value.scene ?: return
        val layer = SceneOps.findLayer(scene, _uiState.value.selectedLayerId) ?: return
        setSelectedRotation(LayerGeometry.rotationTowards(layer, sceneX, sceneY))
    }

    fun nudgeSelected(dx: Float, dy: Float) {
        val layerId = _uiState.value.selectedLayerId ?: return
        mutate { scene ->
            SceneOps.transformLayer(scene, layerId, dx, dy, snapToGrid = _uiState.value.snapToGrid)
        }
    }

    fun reorder(fromIndex: Int, toIndex: Int) = mutate { SceneOps.reorder(it, fromIndex, toIndex) }

    fun bringSelectedToFront() {
        val layerId = _uiState.value.selectedLayerId ?: return
        mutate { SceneOps.bringToFront(it, layerId) }
    }

    fun sendSelectedToBack() {
        val layerId = _uiState.value.selectedLayerId ?: return
        mutate { SceneOps.sendToBack(it, layerId) }
    }

    fun deleteLayer(layerId: String) {
        mutate { SceneOps.removeLayer(it, layerId) }
        if (_uiState.value.selectedLayerId == layerId) select(null)
    }

    fun duplicateLayer(layerId: String) {
        val scene = _uiState.value.scene ?: return
        val source = SceneOps.findLayer(scene, layerId) ?: return
        if (!requireLayerBudget(scene)) return

        val copy = copyLayerWithNewId(source)
        mutate { SceneOps.addLayer(it, copy) }
        select(copy.id)
    }

    fun setLayerVisible(layerId: String, visible: Boolean) {
        mutate { scene ->
            val layer = SceneOps.findLayer(scene, layerId) ?: return@mutate scene
            SceneOps.replaceLayer(scene, layer.withVisible(visible))
        }
    }

    fun setLayerOpacity(layerId: String, opacity: Float) {
        mutate(recordUndo = false) { scene ->
            val layer = SceneOps.findLayer(scene, layerId) ?: return@mutate scene
            SceneOps.replaceLayer(scene, layer.withOpacity(opacity))
        }
    }

    fun updateTextStyle(layerId: String, transform: (TextStyleSpec) -> TextStyleSpec) {
        val scene = _uiState.value.scene ?: return
        val layer = SceneOps.findLayer(scene, layerId) ?: return
        val style = when (layer) {
            is ClockLayer -> layer.style
            is DateLayer -> layer.style
            is TextLayer -> layer.style
            is WidgetLayer -> layer.style
            else -> return
        }

        val updated = transform(style)
        val family = FontCatalog.families.firstOrNull { it.id == updated.fontFamily }
        if (family?.isPro == true && !_uiState.value.isPro) {
            viewModelScope.launch { _events.send(EditorEvent.RequiresPro(ProFeature.PREMIUM_FONTS)) }
            return
        }

        mutate { SceneOps.replaceLayer(it, layer.withTextStyle(updated)) }
    }

    fun updateText(layerId: String, text: String) {
        mutate { scene ->
            val layer = SceneOps.findLayer(scene, layerId) as? TextLayer ?: return@mutate scene
            SceneOps.replaceLayer(scene, layer.copy(text = text))
        }
    }

    fun updatePattern(layerId: String, pattern: String) {
        mutate { scene ->
            when (val layer = SceneOps.findLayer(scene, layerId)) {
                is ClockLayer -> SceneOps.replaceLayer(scene, layer.copy(pattern = pattern))
                is DateLayer -> SceneOps.replaceLayer(scene, layer.copy(pattern = pattern))
                else -> scene
            }
        }
    }

    fun updateWidgetKind(layerId: String, kind: WidgetKind) {
        mutate { scene ->
            val layer = SceneOps.findLayer(scene, layerId) as? WidgetLayer ?: return@mutate scene
            SceneOps.replaceLayer(scene, layer.copy(widgetKind = kind))
        }
        refreshWidgets()
    }

    fun renameScene(name: String) = mutate { it.copy(name = name) }

    fun setTarget(target: ScreenTarget) = mutate { it.copy(target = target) }

    // -- Layer creation -----------------------------------------------------------------------

    fun addClock() = addLayer { scene ->
        ClockLayer(id = newLayerId(), transform = centreOf(scene), style = TextStyleSpec(fontSize = 180f))
    }

    fun addDate() = addLayer { scene ->
        DateLayer(id = newLayerId(), transform = centreOf(scene), style = TextStyleSpec(fontSize = 44f))
    }

    fun addText() = addLayer { scene ->
        TextLayer(id = newLayerId(), transform = centreOf(scene), text = "New text")
    }

    fun addWidget(kind: WidgetKind) = addLayer { scene ->
        WidgetLayer(id = newLayerId(), transform = centreOf(scene), widgetKind = kind)
    }

    /** Imports the picked media, then adds the matching layer type. */
    fun addMediaLayer(pickedUri: Uri, kind: MediaLayerKind) {
        val scene = _uiState.value.scene ?: return
        if (!requireLayerBudget(scene)) return

        val feature = when (kind) {
            MediaLayerKind.IMAGE -> null
            MediaLayerKind.VIDEO, MediaLayerKind.GIF -> ProFeature.VIDEO_LAYERS
            MediaLayerKind.CUTOUT -> ProFeature.CUTOUT_TOOL
        }
        if (feature != null && !_uiState.value.isPro) {
            viewModelScope.launch { _events.send(EditorEvent.RequiresPro(feature)) }
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = "Importing…")
            val imported = mediaImporter.import(pickedUri)
            _uiState.value = _uiState.value.copy(busy = null)

            imported.onFailure {
                _events.send(EditorEvent.Message(it.message ?: "Could not import that file"))
                return@launch
            }

            val uri = imported.getOrThrow()
            val transform = centreOf(scene)
            val layer: Layer = when (kind) {
                MediaLayerKind.IMAGE -> ImageLayer(newLayerId(), transform = transform, sourceUri = uri)
                MediaLayerKind.VIDEO -> VideoLayer(newLayerId(), transform = transform, sourceUri = uri)
                MediaLayerKind.GIF -> GifLayer(newLayerId(), transform = transform, sourceUri = uri)
                MediaLayerKind.CUTOUT -> CutoutLayer(newLayerId(), transform = transform, sourceUri = uri)
            }

            mutate { SceneOps.addLayer(it, layer) }
            select(layer.id)

            if (kind == MediaLayerKind.CUTOUT) generateCutout(layer.id)
        }
    }

    /** Runs on-device segmentation and swaps in the masked image. */
    fun generateCutout(layerId: String) {
        if (!_uiState.value.isPro) {
            viewModelScope.launch { _events.send(EditorEvent.RequiresPro(ProFeature.CUTOUT_TOOL)) }
            return
        }

        viewModelScope.launch {
            val layer = SceneOps.findLayer(_uiState.value.scene ?: return@launch, layerId)
                as? CutoutLayer ?: return@launch

            _uiState.value = _uiState.value.copy(busy = "Finding the subject…")
            // The source has to be decoded before it can be segmented.
            awaitAsset(layer.sourceUri)

            when (val result = cutoutProcessor.createCutout(layer.sourceUri, assets)) {
                is CutoutProcessor.Result.Success -> {
                    mutate { scene ->
                        val target = SceneOps.findLayer(scene, layerId) as? CutoutLayer
                            ?: return@mutate scene
                        SceneOps.replaceLayer(scene, target.copy(cutoutUri = result.cutoutUri))
                    }
                    _events.send(EditorEvent.Message("Cutout ready — drag it in front of your clock"))
                }

                is CutoutProcessor.Result.NoSubjectFound ->
                    _events.send(EditorEvent.Message(result.message))

                is CutoutProcessor.Result.Failed ->
                    _events.send(EditorEvent.Message(result.message))
            }
            _uiState.value = _uiState.value.copy(busy = null)
        }
    }

    private suspend fun awaitAsset(uri: String) {
        repeat(ASSET_WAIT_ATTEMPTS) {
            if (assets.bitmap(uri) != null) return
            kotlinx.coroutines.delay(ASSET_WAIT_INTERVAL_MS)
        }
    }

    private fun addLayer(factory: (Scene) -> Layer) {
        val scene = _uiState.value.scene ?: return
        if (!requireLayerBudget(scene)) return
        val layer = factory(scene)
        mutate { SceneOps.addLayer(it, layer) }
        select(layer.id)
    }

    private fun requireLayerBudget(scene: Scene): Boolean {
        if (entitlements.canAddLayer(scene.layers.size)) return true
        viewModelScope.launch { _events.send(EditorEvent.RequiresPro(ProFeature.UNLIMITED_LAYERS)) }
        return false
    }

    // -- Background ---------------------------------------------------------------------------

    fun setBackgroundMedia(pickedUri: Uri, video: Boolean) {
        if (video && !_uiState.value.isPro) {
            viewModelScope.launch { _events.send(EditorEvent.RequiresPro(ProFeature.VIDEO_LAYERS)) }
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = "Importing…")
            val imported = mediaImporter.import(pickedUri)
            _uiState.value = _uiState.value.copy(busy = null)

            imported.onSuccess { uri ->
                mutate { scene ->
                    scene.copy(
                        background = scene.background.copy(
                            type = if (video) BackgroundType.VIDEO else BackgroundType.IMAGE,
                            sourceUri = uri,
                        ),
                    )
                }
            }.onFailure {
                _events.send(EditorEvent.Message(it.message ?: "Could not import that file"))
            }
        }
    }

    fun setBackground(transform: (Background) -> Background) =
        mutate { it.copy(background = transform(it.background)) }

    /**
     * Repositions the background from a canvas drag, while the Background tab is open.
     *
     * The deltas arrive in scene pixels — the same units every other canvas gesture uses — and are
     * converted to the fraction-of-canvas that [Background.offsetX] is expressed in.
     */
    fun panBackground(dx: Float, dy: Float, zoomBy: Float = 1f) {
        mutate(recordUndo = false) { scene ->
            val (limitX, limitY) = backgroundPanLimits(scene, zoomBy)
            SceneOps.panBackground(
                scene = scene,
                dx = dx / scene.canvas.width,
                dy = dy / scene.canvas.height,
                zoomBy = zoomBy,
                limitX = limitX,
                limitY = limitY,
            )
        }
    }

    /**
     * How far the background can be panned on each axis, as a fraction of the canvas.
     *
     * This is half the overhang the current fit produces. A `COVER` photo that is wider than the
     * canvas already has somewhere to go at zoom 1 — that is the crop the user wants to choose —
     * whereas `CONTAIN` and `STRETCH` only gain room once zoomed past 1.
     */
    private fun backgroundPanLimits(scene: Scene, zoomBy: Float): Pair<Float, Float> {
        val background = scene.background
        val zoom = (background.zoom * zoomBy)
            .coerceIn(SceneOps.MIN_BACKGROUND_ZOOM, SceneOps.MAX_BACKGROUND_ZOOM)
        val canvasWidth = scene.canvas.width.toFloat()
        val canvasHeight = scene.canvas.height.toFloat()

        val intrinsic = background.sourceUri?.let { assets.intrinsicSize(it) }
        val fit = when {
            intrinsic == null || intrinsic.width <= 0f || intrinsic.height <= 0f -> null
            background.scaleMode == ScaleMode.COVER ->
                maxOf(canvasWidth / intrinsic.width, canvasHeight / intrinsic.height)

            background.scaleMode == ScaleMode.CONTAIN ->
                minOf(canvasWidth / intrinsic.width, canvasHeight / intrinsic.height)

            else -> null
        }

        // Stretch, or a source that has not decoded yet: the fit fills exactly, so the only
        // overhang is whatever the zoom adds.
        if (fit == null || intrinsic == null) {
            val limit = (zoom - 1f) / 2f
            return limit to limit
        }

        val shownWidth = intrinsic.width * fit * zoom
        val shownHeight = intrinsic.height * fit * zoom
        return ((shownWidth - canvasWidth) / canvasWidth / 2f).coerceAtLeast(0f) to
            ((shownHeight - canvasHeight) / canvasHeight / 2f).coerceAtLeast(0f)
    }

    fun resetBackgroundFraming() = mutate {
        it.copy(background = it.background.copy(offsetX = 0f, offsetY = 0f, zoom = 1f))
    }

    fun setBackgroundDim(dim: Float) =
        mutate(recordUndo = false) { it.copy(background = it.background.copy(dim = dim)) }

    // -- Editor preferences -------------------------------------------------------------------

    fun toggleSnapToGrid() {
        viewModelScope.launch {
            settingsRepository.setSnapToGrid(!_uiState.value.snapToGrid)
        }
    }

    fun toggleSnapToObjects() {
        viewModelScope.launch {
            settingsRepository.setSnapToObjects(!_uiState.value.snapToObjects)
        }
    }

    fun setGridSize(size: Int) = mutate { it.copy(gridSize = size.coerceIn(8, 240)) }

    fun setPreviewPlaying(playing: Boolean) {
        _uiState.value = _uiState.value.copy(previewPlaying = playing)
    }

    // -- Undo / redo --------------------------------------------------------------------------

    fun undo() {
        val current = _uiState.value.scene ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        applyHistory(previous)
    }

    fun redo() {
        val current = _uiState.value.scene ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        applyHistory(next)
    }

    private fun applyHistory(scene: Scene) {
        _uiState.value = _uiState.value.copy(
            scene = scene,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            selectedLayerId = _uiState.value.selectedLayerId
                ?.takeIf { id -> scene.layers.any { it.id == id } },
            isDirty = scene != savedScene,
        )
        assets.prepare(scene)
    }

    // -- Output -------------------------------------------------------------------------------

    /** Renders the scene and hands it to the system wallpaper picker. */
    fun exportAndShare() = withRenderedScene("Exporting…") { scene, bitmap ->
        val uri = exporter.exportToCache(scene, bitmap)
        _events.send(EditorEvent.LaunchIntent(Intent.createChooser(exporter.shareIntent(uri), "Share scene")))
    }

    fun saveToGallery() = withRenderedScene("Saving…") { scene, bitmap ->
        val uri = exporter.saveToGallery(scene, bitmap)
        _events.send(
            if (uri != null) {
                EditorEvent.Message("Saved to Pictures/LayerLock")
            } else {
                EditorEvent.Message("Could not save to your gallery")
            },
        )
    }

    fun applyStaticWallpaper() = withRenderedScene("Applying…") { scene, bitmap ->
        exporter.applyAsStaticWallpaper(bitmap, scene.target)
            .onSuccess { _events.send(EditorEvent.Message("Wallpaper set")) }
            .onFailure { _events.send(EditorEvent.Message(it.message ?: "Could not set the wallpaper")) }
    }

    fun openSystemWallpaperChooser() = withRenderedScene("Preparing…") { scene, bitmap ->
        val uri = exporter.exportToCache(scene, bitmap)
        runCatching { exporter.wallpaperChooserIntent(uri) }
            .onSuccess { _events.send(EditorEvent.LaunchIntent(it)) }
            .onFailure { _events.send(EditorEvent.Message("No wallpaper picker available on this device")) }
    }

    /** Assigns this scene to the live wallpaper / lock surface and asks the OS to activate it. */
    fun applyAsLiveScene() {
        viewModelScope.launch {
            val scene = _uiState.value.scene ?: return@launch
            // Applying a scene necessarily publishes it, so this counts as a save.
            sceneRepository.upsert(scene)
            savedScene = scene
            _uiState.value = _uiState.value.copy(isDirty = false)
            sceneRepository.setActive(scene.sceneId, scene.target)
            _events.send(EditorEvent.RequestLiveWallpaper)
        }
    }

    private fun withRenderedScene(
        busyLabel: String,
        block: suspend (Scene, android.graphics.Bitmap) -> Unit,
    ) {
        viewModelScope.launch {
            val scene = _uiState.value.scene ?: return@launch
            _uiState.value = _uiState.value.copy(busy = busyLabel)
            runCatching {
                val bitmap = exporter.render(
                    scene = scene,
                    assets = assets,
                    widgets = _uiState.value.widgets,
                    watermark = !_uiState.value.isPro,
                )
                block(scene, bitmap)
            }.onFailure {
                _events.send(EditorEvent.Message(it.message ?: "Something went wrong"))
            }
            _uiState.value = _uiState.value.copy(busy = null)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Deliberately does not save. Leaving the editor without saving is now a decision the user
        // makes at the exit prompt, and honouring it here is the whole point of that prompt.
        cutoutProcessor.close()
        assets.release()
    }

    private fun centreOf(scene: Scene) =
        Transform(x = scene.canvas.width / 2f, y = scene.canvas.height / 2f)

    private fun newLayerId() = "layer-${UUID.randomUUID().toString().take(8)}"

    private fun copyLayerWithNewId(layer: Layer): Layer {
        val id = newLayerId()
        val offset = layer.transform.copy(x = layer.transform.x + 40f, y = layer.transform.y + 40f)
        return when (layer) {
            is ClockLayer -> layer.copy(id = id, transform = offset)
            is DateLayer -> layer.copy(id = id, transform = offset)
            is TextLayer -> layer.copy(id = id, transform = offset)
            is ImageLayer -> layer.copy(id = id, transform = offset)
            is VideoLayer -> layer.copy(id = id, transform = offset)
            is GifLayer -> layer.copy(id = id, transform = offset)
            is CutoutLayer -> layer.copy(id = id, transform = offset)
            is WidgetLayer -> layer.copy(id = id, transform = offset)
        }
    }

    enum class MediaLayerKind { IMAGE, VIDEO, GIF, CUTOUT }

    companion object {
        private const val UNDO_LIMIT = 50
        private const val ASSET_WAIT_ATTEMPTS = 40
        private const val ASSET_WAIT_INTERVAL_MS = 100L

        fun factory(sceneId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY])
                EditorViewModel(application, sceneId)
            }
        }
    }
}

data class EditorUiState(
    val scene: Scene? = null,
    val selectedLayerId: String? = null,
    val snapToGrid: Boolean = false,
    val showGrid: Boolean = false,
    val snapToObjects: Boolean = true,
    val showBounds: Boolean = true,
    val previewPlaying: Boolean = true,
    val isPro: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Whether there are in-memory edits that are not on disk yet. */
    val isDirty: Boolean = false,
    /** Alignment guides for the snap currently in effect. Empty except mid-drag. */
    val activeGuides: List<SceneCanvasRenderer.Guide> = emptyList(),
    val busy: String? = null,
    val widgets: WidgetSnapshot = WidgetSnapshot(),
) {
    val selectedLayer: Layer?
        get() = scene?.layers?.firstOrNull { it.id == selectedLayerId }
}

sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent
    data class RequiresPro(val feature: ProFeature) : EditorEvent
    data class LaunchIntent(val intent: Intent) : EditorEvent
    data object RequestLiveWallpaper : EditorEvent
    data object SceneMissing : EditorEvent
}
