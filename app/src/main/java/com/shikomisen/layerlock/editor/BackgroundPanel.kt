package com.shikomisen.layerlock.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shikomisen.layerlock.scene.Background
import com.shikomisen.layerlock.scene.BackgroundType
import com.shikomisen.layerlock.scene.ScaleMode
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.ScreenTarget
import kotlin.math.roundToInt

/**
 * Background and canvas settings.
 *
 * Picking a photo or video goes through the system Photo Picker, which is why there is no runtime
 * permission prompt anywhere in this flow (§5).
 */
@Composable
fun BackgroundPanel(
    scene: Scene,
    isPro: Boolean,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onBackground: ((Background) -> Background) -> Unit,
    onDim: (Float) -> Unit,
    onResetFraming: () -> Unit,
    onGestureStart: () -> Unit,
    onTarget: (ScreenTarget) -> Unit,
    onGridSize: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = scene.background

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("Source") {
            ChipRow(
                options = listOf(
                    BackgroundType.COLOR to "Solid",
                    BackgroundType.GRADIENT to "Gradient",
                    BackgroundType.IMAGE to "Photo",
                    BackgroundType.VIDEO to "Video",
                ),
                selected = background.type,
                onSelect = { type ->
                    when (type) {
                        BackgroundType.IMAGE -> onPickImage()
                        BackgroundType.VIDEO -> onPickVideo()
                        else -> onBackground { it.copy(type = type) }
                    }
                },
            )

            when (background.type) {
                BackgroundType.IMAGE -> AssistChip(
                    onClick = onPickImage,
                    label = { Text("Choose a different photo") },
                )

                BackgroundType.VIDEO -> AssistChip(
                    onClick = onPickVideo,
                    label = { Text("Choose a different video") },
                )

                else -> Unit
            }

            if (background.type == BackgroundType.VIDEO && !isPro) {
                Text(
                    "Video backgrounds are a Pro feature.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        when (background.type) {
            BackgroundType.COLOR -> Section("Colour") {
                ColourSwatches(background.color) { colour -> onBackground { it.copy(color = colour) } }
            }

            BackgroundType.GRADIENT -> {
                Section("Gradient start") {
                    ColourSwatches(background.color) { colour ->
                        onBackground { it.copy(color = colour) }
                    }
                }
                Section("Gradient end") {
                    ColourSwatches(background.colorEnd) { colour ->
                        onBackground { it.copy(colorEnd = colour) }
                    }
                }
                LabelledSlider(
                    label = "Angle",
                    value = background.gradientAngle,
                    valueRange = 0f..360f,
                    display = "${background.gradientAngle.roundToInt()}°",
                    onStart = onGestureStart,
                    onValueChange = { angle -> onBackground { it.copy(gradientAngle = angle) } },
                )
            }

            BackgroundType.IMAGE, BackgroundType.VIDEO -> Section("Fit") {
                ChipRow(
                    options = listOf(
                        ScaleMode.COVER to "Fill",
                        ScaleMode.CONTAIN to "Fit",
                        ScaleMode.STRETCH to "Stretch",
                    ),
                    selected = background.scaleMode,
                    onSelect = { mode -> onBackground { it.copy(scaleMode = mode) } },
                )

                Text(
                    "Drag the canvas to move the background, pinch to zoom into it. " +
                        "Filling a screen of a different shape always crops something — this is " +
                        "how you choose what gets kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val framed = background.offsetX != 0f ||
                    background.offsetY != 0f ||
                    background.zoom != 1f
                if (framed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Zoom ${(background.zoom * 100).roundToInt()}% · " +
                                "offset ${(background.offsetX * 100).roundToInt()}%, " +
                                "${(background.offsetY * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AssistChip(onClick = onResetFraming, label = { Text("Recentre") })
                    }
                }
            }
        }

        LabelledSlider(
            label = "Dim",
            value = background.dim,
            valueRange = 0f..1f,
            display = "${(background.dim * 100).roundToInt()}%",
            onStart = onGestureStart,
            onValueChange = onDim,
        )
        Text(
            "A little dim is the easiest way to keep a clock readable over a busy photo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Use this scene on") {
            ChipRow(
                options = listOf(
                    ScreenTarget.LOCK to "Lock screen",
                    ScreenTarget.HOME to "Home screen",
                    ScreenTarget.BOTH to "Both",
                ),
                selected = scene.target,
                onSelect = onTarget,
            )
        }

        Section("Grid") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 48, 72, 120).forEach { size ->
                    AssistChip(
                        onClick = { onGridSize(size) },
                        label = { Text("$size${if (scene.gridSize == size) " ✓" else ""}") },
                    )
                }
            }
            Text(
                "Grid spacing in canvas pixels. It only affects snapping while editing — never how " +
                    "the scene is stored or drawn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "Canvas ${scene.canvas.width} × ${scene.canvas.height}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
