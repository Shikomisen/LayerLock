package com.shikomisen.layerlock.lockscreen

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.shikomisen.layerlock.canvas.SceneAssets
import com.shikomisen.layerlock.canvas.SceneSurface
import com.shikomisen.layerlock.canvas.WidgetDataSource
import com.shikomisen.layerlock.data.LayerLockGraph
import com.shikomisen.layerlock.scene.ScenePresets
import com.shikomisen.layerlock.scene.ScreenTarget
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * The custom lock-screen surface (§8 Phase 3).
 *
 * ## What this does and does not do
 *
 * This Activity draws *in front of* the keyguard using `setShowWhenLocked(true)` — the same public,
 * non-privileged mechanism an incoming-call screen uses. It is only ever the visual layer shown
 * *before* any unlock decision.
 *
 * It does not replace, weaken, or observe device security. When the user asks to unlock, the app
 * calls [KeyguardManager.requestDismissKeyguard] and the OS takes over completely: if there is no
 * secure lock it dismisses immediately, and if there is, Android shows its own PIN, pattern,
 * password or biometric prompt. This app never sees, stores, or handles any credential, and there is
 * no code path here that could — which is exactly why the older Device Admin "disable keyguard" plus
 * `SYSTEM_ALERT_WINDOW` overlay approach is avoided entirely (§3, §10). That combination is what
 * credential-phishing malware uses, and Play reviews it accordingly.
 */
class LockScreenActivity : ComponentActivity() {

    private val widgetDataSource by lazy { WidgetDataSource(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showWhenLocked()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val sceneRepository = LayerLockGraph.sceneRepository(this)
        val assets = SceneAssets(applicationContext, lifecycleScope)
        val sceneFlow = sceneRepository.activeScene(ScreenTarget.LOCK)
            .stateIn(lifecycleScope, SharingStarted.Eagerly, null)

        setContent {
            val scene by sceneFlow.collectAsStateWithLifecycle()
            val widgets = remember { widgetDataSource.current() }
            val resolved = scene ?: remember { ScenePresets.blank("lock-placeholder", "LayerLock") }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            // An upward swipe is the conventional "unlock" gesture. It only ever
                            // asks the OS to take over.
                            if (dragAmount < UNLOCK_DRAG_THRESHOLD) requestUnlock()
                        }
                    },
            ) {
                SceneSurface(
                    scene = resolved,
                    assets = assets,
                    modifier = Modifier.fillMaxSize(),
                    widgets = widgets.copy(
                        notificationCount = NotificationMirror.count,
                        nowPlaying = NotificationMirror.nowPlaying,
                    ),
                    playVideo = true,
                )

                UnlockHint(modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    private fun showWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    /**
     * Hands control to the OS.
     *
     * Everything after this call belongs to Android: the credential prompt, the biometric sensor, the
     * mandatory PIN fallback behind biometrics, and the decision itself. The callback only reports
     * what already happened.
     */
    private fun requestUnlock() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    finish()
                }
            },
        )
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {

        private const val UNLOCK_DRAG_THRESHOLD = -18f

        fun intent(context: Context): Intent =
            Intent(context, LockScreenActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
    }
}

@Composable
private fun UnlockHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Swipe up to unlock",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.75f),
        )
    }
}
