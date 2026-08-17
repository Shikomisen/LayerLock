package com.shikomisen.layerlock.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shikomisen.layerlock.data.pro.ProFeature
import com.shikomisen.layerlock.wallpaper.LayerLockWallpaperService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The editor.
 *
 * Canvas on top, panels below — the layout that keeps the thing being edited visible while it is
 * edited, which matters more here than in most apps because every control changes what the canvas
 * shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    sceneId: String,
    onBack: () -> Unit,
    onShowPaywall: (ProFeature) -> Unit,
    modifier: Modifier = Modifier,
    // Keyed by scene. Without the key, `viewModel()` stores this against the Activity under a key
    // derived from the class name alone, so opening a second scene finds the first scene's instance
    // already there and never calls the factory — the editor shows the wrong scene until the process
    // restarts. There is no NavHost here to scope it for us (see Destination in LayerLockApp).
    viewModel: EditorViewModel = viewModel(
        key = sceneId,
        factory = EditorViewModel.factory(sceneId),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    // These ViewModels live as long as the Activity — one per scene visited — so the decoded bitmaps
    // are freed on the way out rather than accumulating across a browsing session. SceneSurface
    // re-prepares them if this scene is opened again.
    DisposableEffect(viewModel) {
        onDispose { viewModel.assets.release() }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showExitPrompt by remember { mutableStateOf(false) }
    var canvasView by remember { mutableStateOf(CanvasView()) }
    var panMode by remember { mutableStateOf(false) }

    // How much of the panel area is showing: 1 fully open, 0 fully collapsed. An Animatable rather
    // than a Boolean so the panel can sit anywhere in between while a finger is on the handle.
    val panelOpen = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    // Reads the target, not the current value, so the labels flip when the gesture commits rather
    // than halfway through the animation that follows.
    val panelsCollapsed = panelOpen.targetValue < 0.5f
    var pendingPick by remember { mutableStateOf<PickTarget?>(null) }

    // Every exit route goes through here, so unsaved work cannot be lost by any of them.
    fun attemptBack() {
        if (state.isDirty) showExitPrompt = true else onBack()
    }

    BackHandler(enabled = state.isDirty) { showExitPrompt = true }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val target = pendingPick
        pendingPick = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult

        when (target) {
            PickTarget.BACKGROUND_IMAGE -> viewModel.setBackgroundMedia(uri, video = false)
            PickTarget.BACKGROUND_VIDEO -> viewModel.setBackgroundMedia(uri, video = true)
            PickTarget.LAYER_IMAGE ->
                viewModel.addMediaLayer(uri, EditorViewModel.MediaLayerKind.IMAGE)

            PickTarget.LAYER_VIDEO ->
                viewModel.addMediaLayer(uri, EditorViewModel.MediaLayerKind.VIDEO)

            PickTarget.LAYER_GIF -> viewModel.addMediaLayer(uri, EditorViewModel.MediaLayerKind.GIF)
            PickTarget.LAYER_CUTOUT ->
                viewModel.addMediaLayer(uri, EditorViewModel.MediaLayerKind.CUTOUT)
        }
    }

    fun launchPick(target: PickTarget) {
        pendingPick = target
        picker.launch(PickVisualMediaRequest(target.mediaType))
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EditorEvent.Message -> snackbarHost.showSnackbar(event.text)
                is EditorEvent.RequiresPro -> onShowPaywall(event.feature)
                is EditorEvent.LaunchIntent -> context.startActivity(event.intent)
                EditorEvent.RequestLiveWallpaper -> {
                    runCatching {
                        context.startActivity(
                            LayerLockWallpaperService.changeLiveWallpaperIntent(context),
                        )
                    }.onFailure {
                        snackbarHost.showSnackbar("This device has no live wallpaper picker")
                    }
                }

                EditorEvent.SceneMissing -> onBack()
            }
        }
    }

    val scene = state.scene

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    // The title doubles as the rename affordance — tapping the name of the thing
                    // you want to rename is where people look first.
                    Text(
                        text = (scene?.name ?: "Editor") + if (state.isDirty) " •" else "",
                        maxLines = 1,
                        modifier = Modifier.clickable(enabled = scene != null) { showRename = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { attemptBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to your scenes")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Text("Undo")
                    }
                    TextButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Text("Redo")
                    }
                    TextButton(onClick = { viewModel.save() }, enabled = state.isDirty) {
                        Text("Save")
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename scene…") },
                            onClick = { showMenu = false; showRename = true },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Set as live wallpaper") },
                            onClick = { showMenu = false; viewModel.applyAsLiveScene() },
                        )
                        DropdownMenuItem(
                            text = { Text("Set as static wallpaper") },
                            onClick = { showMenu = false; viewModel.applyStaticWallpaper() },
                        )
                        DropdownMenuItem(
                            text = { Text("Open system wallpaper picker") },
                            onClick = { showMenu = false; viewModel.openSystemWallpaperChooser() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Save PNG to gallery") },
                            onClick = { showMenu = false; viewModel.saveToGallery() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share PNG") },
                            onClick = { showMenu = false; viewModel.exportAndShare() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (scene == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // The panels get an explicit, animatable height rather than a layout weight, because a
            // weight cannot be dragged. The canvas simply takes whatever is left.
            val panelMaxHeight = maxHeight * PANEL_HEIGHT_FRACTION
            val panelMaxHeightPx = with(LocalDensity.current) { panelMaxHeight.toPx() }

            Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EditorCanvas(
                    scene = scene,
                    assets = viewModel.assets,
                    state = state,
                    view = canvasView,
                    panMode = panMode,
                    backgroundMode = selectedTab == BACKGROUND_TAB && !panelsCollapsed,
                    onSelect = viewModel::select,
                    onPanBackground = viewModel::panBackground,
                    onGestureStart = viewModel::gestureStart,
                    onGestureEnd = viewModel::gestureEnd,
                    onTransform = { dx, dy, scaleBy, rotateBy ->
                        viewModel.transformSelected(dx, dy, scaleBy, rotateBy)
                    },
                    onResize = viewModel::resizeSelected,
                    onRotateTowards = viewModel::rotateSelectedTowards,
                    onView = { canvasView = it },
                    modifier = Modifier.padding(12.dp),
                )

                state.busy?.let { label ->
                    Surface(
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            EditorToolbar(
                snapToGrid = state.snapToGrid,
                snapToObjects = state.snapToObjects,
                previewPlaying = state.previewPlaying,
                panelsCollapsed = panelsCollapsed,
                panMode = panMode,
                zoomPercent = (canvasView.scale * 100).roundToInt(),
                canResetView = !canvasView.isDefault,
                onToggleSnap = viewModel::toggleSnapToGrid,
                onToggleAlign = viewModel::toggleSnapToObjects,
                onTogglePreview = { viewModel.setPreviewPlaying(!state.previewPlaying) },
                onTogglePanels = {
                    scope.launch {
                        panelOpen.animateTo(if (panelsCollapsed) 1f else 0f, PANEL_SPRING)
                    }
                },
                onTogglePanMode = { panMode = !panMode },
                onZoom = { factor -> canvasView = canvasView.zoomedBy(factor) },
                onResetView = { canvasView = CanvasView() },
                onAddLayer = { showAddSheet = true },
            )

            PanelHandle(
                collapsed = panelsCollapsed,
                onDrag = { delta ->
                    scope.launch {
                        // Dragging the handle down shrinks the panel below it, which is the only
                        // mapping that makes sense once the panel actually tracks the finger.
                        panelOpen.snapTo((panelOpen.value - delta / panelMaxHeightPx).coerceIn(0f, 1f))
                    }
                },
                onDragStopped = { velocity ->
                    scope.launch { panelOpen.settlePanel(velocity, panelMaxHeightPx) }
                },
                onToggle = {
                    scope.launch {
                        panelOpen.animateTo(if (panelsCollapsed) 1f else 0f, PANEL_SPRING)
                    }
                },
            )

            // Fixed inner height, clipped by an outer box that shrinks — so the panel is revealed
            // and hidden from its top edge instead of its contents being squashed mid-drag.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelMaxHeight * panelOpen.value)
                    .clipToBounds(),
            ) {
                Column(modifier = Modifier.requiredHeight(panelMaxHeight)) {
            TabRow(selectedTabIndex = selectedTab) {
                EDITOR_TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            Box(Modifier.weight(PANEL_WEIGHT)) {
                when (selectedTab) {
                    0 -> LayerPanel(
                        scene = scene,
                        selectedLayerId = state.selectedLayerId,
                        onSelect = viewModel::select,
                        onReorder = viewModel::reorder,
                        onToggleVisible = viewModel::setLayerVisible,
                        onDuplicate = viewModel::duplicateLayer,
                        onDelete = viewModel::deleteLayer,
                        onAddLayer = { showAddSheet = true },
                        modifier = Modifier.fillMaxSize(),
                    )

                    1 -> InspectorPanel(
                        layer = state.selectedLayer,
                        isPro = state.isPro,
                        onOpacity = { opacity ->
                            state.selectedLayerId?.let { viewModel.setLayerOpacity(it, opacity) }
                        },
                        onNudge = viewModel::nudgeSelected,
                        onScale = viewModel::setSelectedScale,
                        onRotate = viewModel::setSelectedRotation,
                        onResetStretch = viewModel::resetSelectedStretch,
                        onGestureStart = viewModel::gestureStart,
                        onStyle = { transform ->
                            state.selectedLayerId?.let { viewModel.updateTextStyle(it, transform) }
                        },
                        onText = { text ->
                            state.selectedLayerId?.let { viewModel.updateText(it, text) }
                        },
                        onPattern = { pattern ->
                            state.selectedLayerId?.let { viewModel.updatePattern(it, pattern) }
                        },
                        onWidgetKind = { kind ->
                            state.selectedLayerId?.let { viewModel.updateWidgetKind(it, kind) }
                        },
                        onGenerateCutout = {
                            state.selectedLayerId?.let { viewModel.generateCutout(it) }
                        },
                        onBringToFront = viewModel::bringSelectedToFront,
                        onSendToBack = viewModel::sendSelectedToBack,
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> BackgroundPanel(
                        scene = scene,
                        isPro = state.isPro,
                        onPickImage = { launchPick(PickTarget.BACKGROUND_IMAGE) },
                        onPickVideo = { launchPick(PickTarget.BACKGROUND_VIDEO) },
                        onBackground = viewModel::setBackground,
                        onDim = viewModel::setBackgroundDim,
                        onResetFraming = viewModel::resetBackgroundFraming,
                        onGestureStart = viewModel::gestureStart,
                        onTarget = viewModel::setTarget,
                        onGridSize = viewModel::setGridSize,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
                }
            }
            }
        }
    }

    if (showRename && scene != null) {
        RenameSceneDialog(
            currentName = scene.name,
            onRename = viewModel::renameScene,
            onDismiss = { showRename = false },
        )
    }

    if (showExitPrompt) {
        UnsavedChangesDialog(
            onSave = {
                showExitPrompt = false
                viewModel.save(onSaved = onBack)
            },
            onDiscard = {
                showExitPrompt = false
                viewModel.discard()
                onBack()
            },
            onDismiss = { showExitPrompt = false },
        )
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AddLayerSheet(
                isPro = state.isPro,
                onAdd = { choice ->
                    showAddSheet = false
                    when (choice) {
                        AddLayerChoice.CLOCK -> viewModel.addClock()
                        AddLayerChoice.DATE -> viewModel.addDate()
                        AddLayerChoice.TEXT -> viewModel.addText()
                        AddLayerChoice.PHOTO -> launchPick(PickTarget.LAYER_IMAGE)
                        AddLayerChoice.VIDEO -> launchPick(PickTarget.LAYER_VIDEO)
                        AddLayerChoice.GIF -> launchPick(PickTarget.LAYER_GIF)
                        AddLayerChoice.CUTOUT -> launchPick(PickTarget.LAYER_CUTOUT)
                    }
                },
                onAddWidget = { kind ->
                    showAddSheet = false
                    viewModel.addWidget(kind)
                },
            )
        }
    }
}

@Composable
private fun EditorToolbar(
    snapToGrid: Boolean,
    snapToObjects: Boolean,
    previewPlaying: Boolean,
    panelsCollapsed: Boolean,
    panMode: Boolean,
    zoomPercent: Int,
    canResetView: Boolean,
    onToggleSnap: () -> Unit,
    onToggleAlign: () -> Unit,
    onTogglePreview: () -> Unit,
    onTogglePanels: () -> Unit,
    onTogglePanMode: () -> Unit,
    onZoom: (Float) -> Unit,
    onResetView: () -> Unit,
    onAddLayer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onAddLayer) { Text("Add layer") }

        // The snap toggle from §4: grid mode for symmetric rows, free mode for pixel-precise work.
        TextButton(onClick = onToggleSnap) {
            Text(if (snapToGrid) "Grid: on" else "Grid: off")
        }

        TextButton(onClick = onToggleAlign) {
            Text(if (snapToObjects) "Align: on" else "Align: off")
        }

        // Hides the tabs and panel so the canvas gets the whole screen. The other half of reaching
        // the top and bottom edges — zooming helps with precision, this helps with reach.
        TextButton(onClick = onTogglePanels) {
            Text(if (panelsCollapsed) "Show panels" else "Hide panels")
        }

        // Zoom is the fine-adjustment tool: at 4x a finger-width of travel is a quarter of the
        // scene distance it used to be. Pan mode exists because two-finger pinch is taken by layer
        // scaling once something is selected, so the viewport needs a way in that does not fight it.
        TextButton(onClick = onTogglePanMode) {
            Text(if (panMode) "Pan: on" else "Pan: off")
        }

        TextButton(onClick = { onZoom(ZOOM_STEP) }) { Text("Zoom +") }
        // Enabled at 1x too: zooming out shrinks the canvas inside its frame, which is how you
        // reach a handle that a stretched layer has pushed past the edge of the scene.
        TextButton(onClick = { onZoom(1f / ZOOM_STEP) }) { Text("Zoom −") }
        TextButton(onClick = onResetView, enabled = canResetView) { Text("Fit ($zoomPercent%)") }

        IconButton(onClick = onTogglePreview) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = if (previewPlaying) "Pause preview" else "Play preview",
                tint = if (previewPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * The grabber between the toolbar and the panels.
 *
 * Sits outside the region it collapses, which is the point — a handle that disappeared along with
 * the panels would leave no way to bring them back except the toolbar button. Tap toggles; a
 * vertical drag sets the state directly, so a flick up always closes and a flick down always opens
 * rather than the direction depending on what it happened to be before.
 */
@Composable
private fun PanelHandle(
    collapsed: Boolean,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    onToggle: () -> Unit,
) {
    val label = if (collapsed) "Show panels" else "Hide panels"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState(onDelta = onDrag),
                // `draggable` reports the fling velocity on release, which is what lets a flick
                // finish the gesture the finger only started.
                onDragStopped = { velocity -> onDragStopped(velocity) },
            )
            .clickable(onClick = onToggle)
            .semantics { contentDescription = label }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
    }
}

/**
 * Runs the panel to whichever end it was heading for.
 *
 * A fast flick wins outright, so a short sharp gesture does not get overruled by the panel still
 * being on the wrong side of halfway. Anything slower settles to the nearer end. The release
 * velocity is carried into the animation, which is what stops the hand-off from looking like a
 * stop followed by a separate move.
 */
private suspend fun Animatable<Float, *>.settlePanel(velocity: Float, maxHeightPx: Float) {
    val fractionPerSecond = if (maxHeightPx > 0f) -velocity / maxHeightPx else 0f
    val target = when {
        velocity <= -PANEL_FLING_VELOCITY -> 1f
        velocity >= PANEL_FLING_VELOCITY -> 0f
        value > 0.5f -> 1f
        else -> 0f
    }
    animateTo(target, PANEL_SPRING, initialVelocity = fractionPerSecond)
}

/** Rename, prefilled and preselected so the common case is type-and-confirm. */
@Composable
private fun RenameSceneDialog(
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename scene") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Scene name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()); onDismiss() },
                enabled = name.isNotBlank() && name.trim() != currentName,
            ) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asked on the way out when there are unsaved edits.
 *
 * Three ways out rather than two: "Cancel" has to exist, because the most likely reason to see this
 * dialog is having hit back by accident.
 */
@Composable
private fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unsaved changes") },
        text = { Text("Save your changes to this scene before leaving?") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onDiscard) { Text("Discard") }
            }
        },
    )
}

private enum class PickTarget(val mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType) {
    BACKGROUND_IMAGE(ActivityResultContracts.PickVisualMedia.ImageOnly),
    BACKGROUND_VIDEO(ActivityResultContracts.PickVisualMedia.VideoOnly),
    LAYER_IMAGE(ActivityResultContracts.PickVisualMedia.ImageOnly),
    LAYER_VIDEO(ActivityResultContracts.PickVisualMedia.VideoOnly),
    LAYER_GIF(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif")),
    LAYER_CUTOUT(ActivityResultContracts.PickVisualMedia.ImageOnly),
}

private val EDITOR_TABS = listOf("Layers", "Style", "Background")

/** Index of the Background tab — while it is open, canvas drags reframe the background. */
private const val BACKGROUND_TAB = 2
private const val PANEL_WEIGHT = 1f

/**
 * Share of the editor's height the panels occupy when fully open.
 *
 * Matches the 1.15 : 1 split the canvas and panels used to be given as layout weights, before the
 * panels needed a height that could be dragged.
 */
private const val PANEL_HEIGHT_FRACTION = 0.46f

/** One tap of the zoom buttons. Coarse enough to be worth tapping, fine enough to land somewhere. */
private const val ZOOM_STEP = 1.5f

/** px/second past which a release is treated as a flick rather than a slow drag. */
private const val PANEL_FLING_VELOCITY = 700f

private val PANEL_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
