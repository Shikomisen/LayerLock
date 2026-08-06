package com.shikomisen.layerlock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.shikomisen.layerlock.data.LayerLockGraph
import com.shikomisen.layerlock.data.pro.ProFeature
import com.shikomisen.layerlock.editor.EditorScreen
import com.shikomisen.layerlock.library.LibraryScreen
import com.shikomisen.layerlock.onboarding.OnboardingScreen
import com.shikomisen.layerlock.pro.PaywallScreen
import com.shikomisen.layerlock.settings.SettingsScreen

/**
 * Destinations.
 *
 * A plain back stack rather than a navigation library: there are five screens, one of which carries
 * an argument, and the whole thing is legible in one screenful.
 */
sealed interface Destination {
    data object Onboarding : Destination
    data object Library : Destination
    data class Editor(val sceneId: String) : Destination
    data object Settings : Destination
    data class Paywall(val feature: ProFeature? = null) : Destination
}

@Composable
fun LayerLockApp() {
    val context = LocalContext.current
    val settingsRepository = remember { LayerLockGraph.settingsRepository(context) }
    var startResolved by remember { mutableStateOf(false) }
    val backStack: SnapshotStateList<Destination> = remember {
        listOf<Destination>(Destination.Library).toMutableStateList()
    }

    LaunchedEffect(Unit) {
        // Onboarding explains each permission before it is ever requested (§8 Phase 5).
        if (!settingsRepository.snapshot().onboardingComplete) {
            backStack.add(Destination.Onboarding)
        }
        startResolved = true
    }

    fun navigate(destination: Destination) {
        backStack.add(destination)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    val current = backStack.last()

    BackHandler(enabled = backStack.size > 1) { back() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (!startResolved) return@Surface

        when (current) {
            Destination.Onboarding -> OnboardingScreen(
                onFinished = { back() },
            )

            Destination.Library -> LibraryScreen(
                onOpenScene = { sceneId -> navigate(Destination.Editor(sceneId)) },
                onOpenSettings = { navigate(Destination.Settings) },
                onShowPaywall = { navigate(Destination.Paywall()) },
            )

            is Destination.Editor -> EditorScreen(
                sceneId = current.sceneId,
                onBack = { back() },
                onShowPaywall = { feature -> navigate(Destination.Paywall(feature)) },
            )

            Destination.Settings -> SettingsScreen(
                onBack = { back() },
                onShowPaywall = { navigate(Destination.Paywall()) },
            )

            is Destination.Paywall -> PaywallScreen(
                highlighted = current.feature,
                onBack = { back() },
            )
        }
    }
}
