package com.shikomisen.layerlock.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shikomisen.layerlock.data.LayerLockGraph
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val title: String,
    val body: String,
    val detail: String,
)

/**
 * First-run explanation.
 *
 * §8 Phase 5 asks for onboarding that explains *why* before asking for anything — a retention lever,
 * not just politeness. So this screen requests nothing. It describes what each capability is for and
 * what it costs, and every one of them is turned on later, individually, from Settings.
 */
private val PAGES = listOf(
    OnboardingPage(
        title = "Design your screen in layers",
        body = "Backgrounds, clocks, text, photos, widgets — each one a layer you can move, resize, " +
            "rotate and reorder front to back.",
        detail = "Nothing is fixed to a grid unless you want it to be.",
    ),
    OnboardingPage(
        title = "Your photos stay yours",
        body = "Picking a photo or video uses Android's own photo picker, so LayerLock only ever " +
            "sees the exact files you choose.",
        detail = "No storage permission is requested, and cutouts are generated on your device — " +
            "no image is ever uploaded.",
    ),
    OnboardingPage(
        title = "Live wallpapers cost battery",
        body = "A moving wallpaper draws power. LayerLock stops rendering entirely whenever your " +
            "screen is off or another app is in front.",
        detail = "There is a Static mode in Settings that freezes all motion if you would rather " +
            "have the battery back.",
    ),
    OnboardingPage(
        title = "Your lock screen stays secure",
        body = "LayerLock can draw a custom scene in front of your lock screen, but it never " +
            "replaces or weakens how you unlock.",
        detail = "Your PIN, pattern and fingerprint are handled entirely by Android. This app never " +
            "sees them, and could not if it tried.",
    ),
    OnboardingPage(
        title = "Notifications are optional",
        body = "If you want notification counts or now-playing on your lock scene, you can grant " +
            "notification access in Settings.",
        detail = "Everything else works without it. Nothing from your notifications is stored or " +
            "leaves your device.",
    ),
)

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { LayerLockGraph.settingsRepository(context) }
    var pageIndex by remember { mutableIntStateOf(0) }
    val page = PAGES[pageIndex]

    fun finish() {
        scope.launch {
            settingsRepository.setOnboardingComplete(true)
            onFinished()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "${pageIndex + 1} of ${PAGES.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(page.body, style = MaterialTheme.typography.bodyLarge)

        Card {
            Text(
                page.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { finish() }) { Text("Skip") }

            Button(
                onClick = {
                    if (pageIndex == PAGES.lastIndex) finish() else pageIndex++
                },
            ) {
                Text(if (pageIndex == PAGES.lastIndex) "Start designing" else "Next")
            }
        }
    }
}
