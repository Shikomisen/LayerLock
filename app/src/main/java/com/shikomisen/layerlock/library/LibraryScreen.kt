package com.shikomisen.layerlock.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shikomisen.layerlock.canvas.SceneSurface
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.ScreenTarget

/**
 * The scene library.
 *
 * Each row previews the scene through the same renderer that will draw it on the wallpaper, so what
 * the list shows is what the device will show — with video paused, since a scrolling list of playing
 * videos is the opposite of the battery behaviour this app is trying to be careful about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenScene: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onShowPaywall: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(),
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::import) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Message -> snackbarHost.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Your scenes") },
                actions = {
                    if (!isPro) {
                        AssistChip(onClick = onShowPaywall, label = { Text("Go Pro") })
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                        Icon(Icons.Default.Add, contentDescription = "Import a scene file")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.createScene(onOpenScene) }) {
                Icon(Icons.Default.Add, contentDescription = "New scene")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(library.scenes, key = { it.sceneId }) { scene ->
                SceneRow(
                    scene = scene,
                    isActiveLock = library.activeLockSceneId == scene.sceneId,
                    isActiveHome = library.activeHomeSceneId == scene.sceneId,
                    onOpen = { onOpenScene(scene.sceneId) },
                    onDuplicate = { viewModel.duplicate(scene.sceneId) },
                    onDelete = { viewModel.delete(scene.sceneId) },
                    onSetActive = { target -> viewModel.setActive(scene.sceneId, target) },
                    previewContent = {
                        SceneSurface(
                            scene = scene,
                            assets = viewModel.assets,
                            modifier = Modifier.fillMaxSize(),
                            // Paused: a list of autoplaying videos would be a battery bug.
                            playVideo = false,
                            watermark = false,
                        )
                    },
                )
            }

            if (library.scenes.isEmpty()) {
                item {
                    Text(
                        "No scenes yet. Tap + to design one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SceneRow(
    scene: Scene,
    isActiveLock: Boolean,
    isActiveHome: Boolean,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: (ScreenTarget) -> Unit,
    previewContent: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .width(84.dp)
                    .height(150.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                previewContent()
            }

            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    scene.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${scene.layers.size} layers · ${scene.background.type.name.lowercase()} background",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (isActiveLock || isActiveHome) {
                    Text(
                        buildString {
                            append("Active on ")
                            append(
                                listOfNotNull(
                                    "lock".takeIf { isActiveLock },
                                    "home".takeIf { isActiveHome },
                                ).joinToString(" and "),
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = { onSetActive(ScreenTarget.LOCK) },
                        label = { Text("Lock") },
                    )
                    AssistChip(
                        onClick = { onSetActive(ScreenTarget.HOME) },
                        label = { Text("Home") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = onDuplicate, label = { Text("Duplicate") })
                    AssistChip(onClick = onDelete, label = { Text("Delete") })
                }
            }
        }
    }
}
