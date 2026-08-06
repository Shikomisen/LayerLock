package com.shikomisen.layerlock.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shikomisen.layerlock.canvas.ClockFormatter
import com.shikomisen.layerlock.canvas.FontCatalog
import com.shikomisen.layerlock.scene.ClockLayer
import com.shikomisen.layerlock.scene.ColorSpec
import com.shikomisen.layerlock.scene.CutoutLayer
import com.shikomisen.layerlock.scene.DateLayer
import com.shikomisen.layerlock.scene.Layer
import com.shikomisen.layerlock.scene.TextAlign
import com.shikomisen.layerlock.scene.TextLayer
import com.shikomisen.layerlock.scene.TextStyleSpec
import com.shikomisen.layerlock.scene.WidgetKind
import com.shikomisen.layerlock.scene.WidgetLayer
import kotlin.math.roundToInt

/** Colours offered as one-tap swatches. Hex entry covers everything else. */
private val SWATCHES = listOf(
    "#FFFFFFFF", "#FF000000", "#FFEDEDED", "#FF9AA0A6",
    "#FFFFD166", "#FFEF476F", "#FF06D6A0", "#FF118AB2",
    "#FFB388FF", "#FFFF8A65", "#FF80DEEA", "#FF000000",
)

/**
 * Properties of the selected layer.
 *
 * Everything here writes through the same `SceneOps` transformations the canvas gestures use, so a
 * slider and a drag produce identical scene changes and share one undo history.
 */
@Composable
fun InspectorPanel(
    layer: Layer?,
    isPro: Boolean,
    onOpacity: (Float) -> Unit,
    onNudge: (Float, Float) -> Unit,
    onScale: (Float) -> Unit,
    onRotate: (Float) -> Unit,
    onGestureStart: () -> Unit,
    onStyle: ((TextStyleSpec) -> TextStyleSpec) -> Unit,
    onText: (String) -> Unit,
    onPattern: (String) -> Unit,
    onWidgetKind: (WidgetKind) -> Unit,
    onGenerateCutout: () -> Unit,
    onBringToFront: () -> Unit,
    onSendToBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (layer == null) {
        EmptyInspector(modifier)
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(layer.displayName, style = MaterialTheme.typography.titleMedium)

        Section("Position") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onNudge(-NUDGE, 0f) }, label = { Text("←") })
                AssistChip(onClick = { onNudge(NUDGE, 0f) }, label = { Text("→") })
                AssistChip(onClick = { onNudge(0f, -NUDGE) }, label = { Text("↑") })
                AssistChip(onClick = { onNudge(0f, NUDGE) }, label = { Text("↓") })
            }
            Text(
                "x ${layer.transform.x.roundToInt()} · y ${layer.transform.y.roundToInt()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LabelledSlider(
            label = "Size",
            value = layer.transform.scale,
            valueRange = 0.1f..6f,
            display = "${(layer.transform.scale * 100).roundToInt()}%",
            onStart = onGestureStart,
            onValueChange = onScale,
        )

        LabelledSlider(
            label = "Rotation",
            value = layer.transform.rotation,
            valueRange = 0f..360f,
            display = "${layer.transform.rotation.roundToInt()}°",
            onStart = onGestureStart,
            onValueChange = onRotate,
        )

        LabelledSlider(
            label = "Opacity",
            value = layer.opacity,
            valueRange = 0f..1f,
            display = "${(layer.opacity * 100).roundToInt()}%",
            onStart = onGestureStart,
            onValueChange = onOpacity,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onBringToFront, label = { Text("Bring to front") })
            AssistChip(onClick = onSendToBack, label = { Text("Send to back") })
        }

        when (layer) {
            is TextLayer -> {
                Section("Text") {
                    androidx.compose.material3.OutlinedTextField(
                        value = layer.text,
                        onValueChange = onText,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Content") },
                    )
                }
                TypographyControls(layer.style, isPro, onStyle)
            }

            is ClockLayer -> {
                PatternChips("Clock format", ClockFormatter.clockPatterns, layer.pattern, onPattern)
                TypographyControls(layer.style, isPro, onStyle)
            }

            is DateLayer -> {
                PatternChips("Date format", ClockFormatter.datePatterns, layer.pattern, onPattern)
                TypographyControls(layer.style, isPro, onStyle)
            }

            is WidgetLayer -> {
                Section("Widget") {
                    ChipRow(
                        options = WidgetKind.entries.map { it to it.name.lowercase().replace('_', ' ') },
                        selected = layer.widgetKind,
                        onSelect = onWidgetKind,
                    )
                }
                TypographyControls(layer.style, isPro, onStyle)
            }

            is CutoutLayer -> {
                Section("Cutout") {
                    Text(
                        if (layer.cutoutUri == null) {
                            "The subject has not been cut out yet."
                        } else {
                            "Subject cut out. Drag it in front of your clock for a depth effect."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AssistChip(
                        onClick = onGenerateCutout,
                        label = { Text(if (layer.cutoutUri == null) "Cut out subject" else "Redo cutout") },
                    )
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun TypographyControls(
    style: TextStyleSpec,
    isPro: Boolean,
    onStyle: ((TextStyleSpec) -> TextStyleSpec) -> Unit,
) {
    Section("Font") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FontCatalog.families.forEach { family ->
                FilterChip(
                    selected = style.fontFamily == family.id,
                    onClick = { onStyle { it.copy(fontFamily = family.id) } },
                    label = {
                        Text(if (family.isPro && !isPro) "${family.label} ★" else family.label)
                    },
                )
            }
        }
    }

    LabelledSlider(
        label = "Font size",
        value = style.fontSize,
        valueRange = 12f..320f,
        display = style.fontSize.roundToInt().toString(),
        onValueChange = { size -> onStyle { it.copy(fontSize = size) } },
    )

    Section("Weight") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FontCatalog.weights.forEach { weight ->
                FilterChip(
                    selected = style.weight == weight,
                    onClick = { onStyle { it.copy(weight = weight) } },
                    label = { Text(weight.toString()) },
                )
            }
        }
    }

    Section("Alignment") {
        ChipRow(
            options = TextAlign.entries.map { it to it.name.lowercase() },
            selected = style.align,
            onSelect = { align -> onStyle { it.copy(align = align) } },
        )
    }

    LabelledSlider(
        label = "Letter spacing",
        value = style.letterSpacing,
        valueRange = -0.1f..0.5f,
        display = "%.2f".format(style.letterSpacing),
        onValueChange = { spacing -> onStyle { it.copy(letterSpacing = spacing) } },
    )

    Section("Colour") {
        ColourSwatches(style.color) { colour -> onStyle { it.copy(color = colour) } }
    }

    ToggleRow("Uppercase", style.allCaps) { value -> onStyle { it.copy(allCaps = value) } }
    ToggleRow("Drop shadow", style.shadow) { value -> onStyle { it.copy(shadow = value) } }

    if (style.shadow) {
        LabelledSlider(
            label = "Shadow softness",
            value = style.shadowRadius,
            valueRange = 0f..48f,
            display = style.shadowRadius.roundToInt().toString(),
            onValueChange = { radius -> onStyle { it.copy(shadowRadius = radius) } },
        )
    }
}

@Composable
fun ColourSwatches(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SWATCHES.distinct().forEach { hex ->
            val isSelected = ColorSpec.parse(selected) == ColorSpec.parse(hex)
            ColourSwatch(
                colour = Color(ColorSpec.parse(hex)),
                selected = isSelected,
                onClick = { onSelect(hex) },
            )
        }
    }
}

@Composable
private fun ColourSwatch(colour: Color, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colour)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
fun LabelledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
    onStart: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // The whole drag is one undo step: [onStart] fires on the first change of a drag, not on each.
    var dragging by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                display,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { newValue ->
                if (!dragging) {
                    dragging = true
                    onStart?.invoke()
                }
                onValueChange(newValue)
            },
            valueRange = valueRange,
            onValueChangeFinished = { dragging = false },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun PatternChips(
    title: String,
    patterns: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Section(title) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            patterns.forEach { (pattern, label) ->
                FilterChip(
                    selected = pattern == selected,
                    onClick = { onSelect(pattern) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun EmptyInspector(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Nothing selected", style = MaterialTheme.typography.titleSmall)
        Text(
            "Tap a layer on the canvas, or pick one from the Layers tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val NUDGE = 8f
