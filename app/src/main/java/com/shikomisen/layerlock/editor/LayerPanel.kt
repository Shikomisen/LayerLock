package com.shikomisen.layerlock.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shikomisen.layerlock.scene.Layer
import com.shikomisen.layerlock.scene.Scene
import kotlin.math.roundToInt

/**
 * The layers panel — §4's "depth rearrangement", as a Photoshop-style stack.
 *
 * Listed front-to-back, because that is how the user perceives the scene: the top row is what sits
 * closest to them. `SceneOps.reorder` takes the same front-to-back indices and renumbers `z` behind
 * the scenes, so nothing in the UI has to reason about z-values.
 */
@Composable
fun LayerPanel(
    scene: Scene,
    selectedLayerId: String?,
    onSelect: (String) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    onToggleVisible: (String, Boolean) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddLayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frontToBack = remember(scene) { scene.drawOrder.asReversed() }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${frontToBack.size} layers · front to back",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onAddLayer) {
                Icon(Icons.Default.Add, contentDescription = "Add a layer")
            }
        }

        if (frontToBack.isEmpty()) {
            EmptyLayers(onAddLayer)
            return@Column
        }

        frontToBack.forEachIndexed { index, layer ->
            LayerRow(
                layer = layer,
                index = index,
                lastIndex = frontToBack.lastIndex,
                selected = layer.id == selectedLayerId,
                dragging = draggingIndex == index,
                dragOffset = if (draggingIndex == index) dragOffset else 0f,
                onSelect = { onSelect(layer.id) },
                onToggleVisible = { onToggleVisible(layer.id, it) },
                onDuplicate = { onDuplicate(layer.id) },
                onDelete = { onDelete(layer.id) },
                onMoveUp = { if (index > 0) onReorder(index, index - 1) },
                onMoveDown = { if (index < frontToBack.lastIndex) onReorder(index, index + 1) },
                onDragStart = {
                    draggingIndex = index
                    dragOffset = 0f
                    onSelect(layer.id)
                },
                onDrag = { delta ->
                    dragOffset += delta
                    val current = draggingIndex ?: return@LayerRow
                    val steps = (dragOffset / rowHeightPx).roundToInt()
                    if (steps != 0) {
                        val target = (current + steps).coerceIn(0, frontToBack.lastIndex)
                        if (target != current) {
                            onReorder(current, target)
                            dragOffset -= (target - current) * rowHeightPx
                            draggingIndex = target
                        }
                    }
                },
                onDragEnd = {
                    draggingIndex = null
                    dragOffset = 0f
                },
            )
        }
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    index: Int,
    lastIndex: Int,
    selected: Boolean,
    dragging: Boolean,
    dragOffset: Float,
    onSelect: () -> Unit,
    onToggleVisible: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .graphicsLayer {
                translationY = dragOffset
                shadowElevation = if (dragging) 12f else 0f
            }
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle. Reordering is deliberately not bound to the whole row: the row itself has
            // to stay tappable for selection.
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = "Drag to reorder",
                modifier = Modifier
                    .size(28.dp)
                    .pointerInput(index) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, amount ->
                                change.consume()
                                onDrag(amount.y)
                            },
                        )
                    },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f),
            ) {
                Text(
                    text = layer.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
                Text(
                    text = "z ${layer.z}${if (layer.gridSnapped) " · snapped" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onMoveUp, enabled = index > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move forward")
            }
            IconButton(onClick = onMoveDown, enabled = index < lastIndex) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move back")
            }
            Switch(
                checked = layer.visible,
                onCheckedChange = onToggleVisible,
            )
            IconButton(onClick = onDuplicate) {
                Icon(Icons.Default.Add, contentDescription = "Duplicate layer")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete layer")
            }
        }
    }
}

@Composable
private fun EmptyLayers(onAddLayer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onAddLayer)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("No layers yet", style = MaterialTheme.typography.titleSmall)
        Text(
            "Add a clock, some text or a photo to get started.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ROW_HEIGHT = 64.dp
