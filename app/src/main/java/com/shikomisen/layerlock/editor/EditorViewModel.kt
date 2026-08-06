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
import com.shikomisen.layerlock.canvas.SceneAssets
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

    private val pendingSaves = MutableStateFlow<Scene?>(null)

    init {
        viewModelScope.launch {
            val scene = sceneRepository.snapshot().scene(sceneId)
            if (scene == null) {
                _events.send(EditorEvent.SceneMissing)
            } else {
                _uiState.value = _uiState.value.copy(scene = scene)
                assets.prepare(scene)
            }
        }

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(
                    snapToGrid = settings.snapToGrid,
                    showGrid = settings.snapToGrid,
                    showBounds = settings.showLayerBounds,
                )
            }
        }

        viewModelScope.launch {
            entitlements.status.collect { status ->
                _uiState.value = _uiState.value.copy(isPro = status.isPro)
            }
        }

        observeSaves()
        refreshWidgets()
    }

    @OptIn(FlowPreview::class)
    private fun observeSaves() {
        viewModelScope.launch {
            // Debounced so a drag gesture writes once at the end, not on every pointer event.
            pendingSaves.filterNotNull().debounce(SAVE_DEBOUNCE_MS).collect { scene ->
                sceneRepository.upsert(scene)
            }
        }
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
        )
        assets.prepare(updated)
        pendingSaves.value = updated
    }

    fun select(layerId: String?) {
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
        undoStack.addLast(current)
        if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        _uiState.value = _uiState.value.copy(canUndo = true, canRedo = false)
    }

    fun transformSelected(dx: Float = 0f, dy: Float = 0f, scaleBy: Float = 1f, rotateBy: Float = 0f) {
        val layerId = _uiState.value.selectedLayerId ?: return
        mutate(recordUndo = false) { scene ->
            SceneOps.transformLayer(
                scene = scene,
                layerId = layerId,
                dx = dx,
                dy = dy,
                scaleBy = scaleBy,
                rotateBy = rotateBy,
                snapToGrid = _uiState.value.snapToGrid,
            )
        }
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
        mutate(recordUndo = false) { scene ->
            val layer = SceneOps.findLayer(scene, layerId) ?: return@mutate scene
            SceneOps.replaceLayer(
                scene,
                layer.withTransform(layer.transform.copy(rotation = degrees % 360f)),
            )
        }
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

    fun setBackgroundDim(dim: Float) =
        mutate(recordUndo = false) { it.copy(background = it.background.copy(dim = dim)) }

    // -- Editor preferences -------------------------------------------------------------------

    fun toggleSnapToGrid() {
        viewModelScope.launch {
            settingsRepository.setSnapToGrid(!_uiState.value.snapToGrid)
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
        )
        assets.prepare(scene)
        pendingSaves.value = scene
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
            sceneRepository.upsert(scene)
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
        // A debounced save may still be pending when the editor closes.
        val scene = _uiState.value.scene
        if (scene != null) {
            LayerLockGraph.applicationScope.launch { sceneRepository.upsert(scene) }
        }
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
        private const val SAVE_DEBOUNCE_MS = 400L
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
    val showBounds: Boolean = true,
    val previewPlaying: Boolean = true,
    val isPro: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
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
