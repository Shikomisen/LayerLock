package com.shikomisen.layerlock.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shikomisen.layerlock.canvas.WidgetText
import com.shikomisen.layerlock.scene.WidgetKind

enum class AddLayerChoice(
    val label: String,
    val description: String,
    val requiresPro: Boolean = false,
) {
    CLOCK("Clock", "The time, in any font you like"),
    DATE("Date", "Today's date, formatted how you want"),
    TEXT("Text", "Any words — a name, a quote, a label"),
    PHOTO("Photo", "A still image, sticker or logo"),
    VIDEO("Video", "A looping clip that sits above the background", requiresPro = true),
    GIF("GIF", "An animated GIF layer", requiresPro = true),
    CUTOUT("Cutout", "Cut a person out of a photo for a depth effect", requiresPro = true),
}

/**
 * The add-layer sheet.
 *
 * Pro-only types are shown rather than hidden, with what they do spelled out — a paywall the user
 * can see the shape of converts better than one that hides the feature entirely, and it stops the
 * free tier feeling arbitrarily crippled.
 */
@Composable
fun AddLayerSheet(
    isPro: Boolean,
    onAdd: (AddLayerChoice) -> Unit,
    onAddWidget: (WidgetKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Add a layer", style = MaterialTheme.typography.titleLarge)

        AddLayerChoice.entries.forEach { choice ->
            ChoiceRow(
                title = choice.label,
                description = choice.description,
                locked = choice.requiresPro && !isPro,
                onClick = { onAdd(choice) },
            )
        }

        Text(
            "Widgets",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WidgetKind.entries.forEach { kind ->
                AssistChip(
                    onClick = { onAddWidget(kind) },
                    label = { Text(WidgetText.label(kind)) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    locked: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (locked) {
                    Text(
                        "Pro",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
