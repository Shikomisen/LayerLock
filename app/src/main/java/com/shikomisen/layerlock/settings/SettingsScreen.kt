package com.shikomisen.layerlock.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shikomisen.layerlock.BuildConfig
import com.shikomisen.layerlock.data.AppSettings
import com.shikomisen.layerlock.data.LayerLockGraph
import com.shikomisen.layerlock.lockscreen.LockScreenActivity
import com.shikomisen.layerlock.lockscreen.LockScreenService
import com.shikomisen.layerlock.lockscreen.NotificationMirror
import com.shikomisen.layerlock.data.pro.ProStatus
import com.shikomisen.layerlock.wallpaper.LayerLockWallpaperService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onShowPaywall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { LayerLockGraph.settingsRepository(context) }
    val entitlements = remember { LayerLockGraph.entitlements(context) }

    val settings by remember {
        settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    }.collectAsStateWithLifecycle()
    val status by entitlements.status.collectAsStateWithLifecycle()

    var showNotificationRationale by remember { mutableStateOf(false) }

    // Re-read on every resume rather than held as a preference: the user can change their wallpaper
    // anywhere in the OS — including in the system picker this screen launches — and a value
    // captured once would sit there claiming to be on after they turned it off elsewhere.
    var isWallpaperActive by remember {
        mutableStateOf(LayerLockWallpaperService.isActiveWallpaper(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isWallpaperActive = LayerLockWallpaperService.isActiveWallpaper(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("Wallpaper") {
                SettingToggle(
                    title = "Set LayerLock as wallpaper",
                    description = if (isWallpaperActive) {
                        "Currently active on this device. Turning this off restores the system " +
                            "default wallpaper. Choose which scene goes where with the Lock and " +
                            "Home buttons on each scene."
                    } else {
                        "Applies LayerLock as your live wallpaper. Android asks you to confirm in " +
                            "its own picker — this app never changes your wallpaper on its own."
                    },
                    checked = isWallpaperActive,
                    onChange = { enable ->
                        if (enable) {
                            runCatching {
                                context.startActivity(
                                    LayerLockWallpaperService.changeLiveWallpaperIntent(context),
                                )
                            }
                            // State is re-read on resume; the picker decides the real outcome.
                        } else {
                            LayerLockWallpaperService.clearWallpaper(context)
                            isWallpaperActive = LayerLockWallpaperService.isActiveWallpaper(context)
                        }
                    },
                )
                AssistChip(
                    onClick = { context.startActivity(LockScreenActivity.intent(context)) },
                    label = { Text("Preview lock screen") },
                )
            }

            SettingsSection("Battery") {
                SettingToggle(
                    title = "Static mode",
                    description = "Freeze all motion. Video backgrounds show a still frame and " +
                        "nothing animates — by far the biggest saving available.",
                    checked = settings.staticMode,
                    onChange = { scope.launch { settingsRepository.setStaticMode(it) } },
                )

                Text("Frame rate cap", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = settings.maxFps.toFloat(),
                    onValueChange = {
                        scope.launch { settingsRepository.setMaxFps(it.roundToInt()) }
                    },
                    valueRange = 5f..60f,
                    steps = 10,
                )
                Text(
                    "${settings.maxFps} fps. Lower is smoother on the battery; animated layers are " +
                        "the only thing this affects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("Editor") {
                SettingToggle(
                    title = "Snap to grid",
                    description = "Align layers to the grid while dragging. Free placement stays " +
                        "available any time you turn this off.",
                    checked = settings.snapToGrid,
                    onChange = { scope.launch { settingsRepository.setSnapToGrid(it) } },
                )
                SettingToggle(
                    title = "Show selection outline",
                    description = "Draw a box and handles around the selected layer.",
                    checked = settings.showLayerBounds,
                    onChange = { scope.launch { settingsRepository.setShowLayerBounds(it) } },
                )
            }

            SettingsSection("Lock screen") {
                SettingToggle(
                    title = "Show my scene on the lock screen",
                    description = "Runs a small background service so LayerLock can draw your scene " +
                        "when the screen wakes. Your PIN, pattern and fingerprint are never touched " +
                        "— Android handles unlocking exactly as it does now.",
                    checked = settings.lockScreenEnabled,
                    onChange = { enabled ->
                        scope.launch { settingsRepository.setLockScreenEnabled(enabled) }
                        if (enabled) LockScreenService.start(context) else LockScreenService.stop(context)
                    },
                )

                SettingToggle(
                    title = "Mirror notifications",
                    description = "Show a notification count and now-playing on your lock scene.",
                    checked = settings.notificationMirroringEnabled &&
                        NotificationMirror.isListenerEnabled(context),
                    onChange = { enabled ->
                        if (enabled) {
                            showNotificationRationale = true
                        } else {
                            scope.launch { settingsRepository.setNotificationMirroring(false) }
                        }
                    },
                )
            }

            SettingsSection("Pro") {
                Text(
                    when (val current = status) {
                        is ProStatus.Entitled -> "Pro is active and verified."
                        is ProStatus.EntitledUnverified -> "Pro is active (${current.reason})."
                        is ProStatus.Unavailable -> "Billing unavailable: ${current.reason}"
                        ProStatus.Free -> "You are on the free tier."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!status.isPro) {
                    AssistChip(onClick = onShowPaywall, label = { Text("See what Pro adds") })
                }
            }

            if (BuildConfig.DEBUG) {
                SettingsSection("Debug") {
                    SettingToggle(
                        title = "Force Pro unlock",
                        description = "Unlock every Pro feature without a Play purchase, so the " +
                            "gated paths can be exercised locally. Debug builds only — this " +
                            "section is not compiled into release.",
                        checked = settings.debugForceProEntitlement,
                        onChange = { scope.launch { entitlements.setDebugProOverride(it) } },
                    )
                }
            }

            SettingsSection("Privacy") {
                Text(
                    "Photos and videos you pick are copied into this app's private storage so your " +
                        "scenes keep working after a restart. Cutouts are generated on-device. " +
                        "Nothing is uploaded anywhere — LayerLock has no server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = { showNotificationRationale = false },
            title = { Text("Before you grant notification access") },
            text = {
                Text(
                    "Android's notification access lets an app read the content of every " +
                        "notification you receive. LayerLock uses it for two things only: how many " +
                        "notifications you have, and what is currently playing.\n\n" +
                        "None of it is stored, and none of it leaves your device. You can revoke " +
                        "this at any time in Android settings.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationRationale = false
                        scope.launch { settingsRepository.setNotificationMirroring(true) }
                        runCatching { context.startActivity(NotificationMirror.settingsIntent()) }
                    },
                ) {
                    Text("Open Android settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationRationale = false }) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
