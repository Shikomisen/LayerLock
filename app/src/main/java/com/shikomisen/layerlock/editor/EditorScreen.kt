package com.shikomisen.layerlock.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shikomisen.layerlock.data.pro.ProFeature
import com.shikomisen.layerlock.wallpaper.LayerLockWallpaperService

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
    viewModel: EditorViewModel = viewModel(factory = EditorViewModel.factory(sceneId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var pendingPick by remember { mutableStateOf<PickTarget?>(null) }

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
                title = { Text(scene?.name ?: "Editor", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(CANVAS_WEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                EditorCanvas(
                    scene = scene,
                    assets = viewModel.assets,
                    state = state,
                    onSelect = viewModel::select,
                    onGestureStart = viewModel::gestureStart,
                    onTransform = { dx, dy, scaleBy, rotateBy ->
                        viewModel.transformSelected(dx, dy, scaleBy, rotateBy)
                    },
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
                previewPlaying = state.previewPlaying,
                onToggleSnap = viewModel::toggleSnapToGrid,
                onTogglePreview = { viewModel.setPreviewPlaying(!state.previewPlaying) },
                onAddLayer = { showAddSheet = true },
            )

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
                        onGestureStart = viewModel::gestureStart,
                        onTarget = viewModel::setTarget,
                        onGridSize = viewModel::setGridSize,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
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
    previewPlaying: Boolean,
    onToggleSnap: () -> Unit,
    onTogglePreview: () -> Unit,
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

private enum class PickTarget(val mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType) {
    BACKGROUND_IMAGE(ActivityResultContracts.PickVisualMedia.ImageOnly),
    BACKGROUND_VIDEO(ActivityResultContracts.PickVisualMedia.VideoOnly),
    LAYER_IMAGE(ActivityResultContracts.PickVisualMedia.ImageOnly),
    LAYER_VIDEO(ActivityResultContracts.PickVisualMedia.VideoOnly),
    LAYER_GIF(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif")),
    LAYER_CUTOUT(ActivityResultContracts.PickVisualMedia.ImageOnly),
}

private val EDITOR_TABS = listOf("Layers", "Style", "Background")
private const val CANVAS_WEIGHT = 1.15f
private const val PANEL_WEIGHT = 1f
