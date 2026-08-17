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
 *
 * ## Pocket suppression (OPEN-1)
 *
 * Because [requestUnlock] hands off to the system credential prompt, a swipe fired by accident is
 * not a harmless misread: subsequent blind contact lands on the OS keypad as wrong attempts and
 * trips a real lockout. The unlock gesture is therefore gated on [rememberScreenCovered] and needs
 * a deliberate amount of travel ([UNLOCK_DRAG_THRESHOLD]) before it fires at all.
 *
 * That gate is about *what the user is taken to be asking for*, and changes nothing above: the
 * handoff, `setShowWhenLocked`, and `requestDismissKeyguard`'s semantics are all untouched, and a
 * device with no proximity sensor behaves exactly as it did before.
 */
class LockScreenActivity : ComponentActivity() {

    private val widgetDataSource by lazy { WidgetDataSource(this) }

    private var assets: SceneAssets? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showWhenLocked()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val sceneRepository = LayerLockGraph.sceneRepository(this)
        // Held as a field so onDestroy can free it. This Activity is recreated on every screen-on
        // (it is noHistory), so leaking a scene's worth of decoded bitmaps per wake adds up fast.
        val assets = SceneAssets(applicationContext, lifecycleScope).also { this.assets = it }
        val sceneFlow = sceneRepository.activeScene(ScreenTarget.LOCK)
            .stateIn(lifecycleScope, SharingStarted.Eagerly, null)

        setContent {
            val scene by sceneFlow.collectAsStateWithLifecycle()
            val widgets = remember { widgetDataSource.current() }
            val resolved = scene ?: remember { ScenePresets.blank("lock-placeholder", "LayerLock") }

            // Pocket guard (OPEN-1). While the proximity sensor reads "near" the unlock gesture
            // detector is detached outright rather than kept around and filtered, so fabric contact
            // has nothing to accumulate against and cannot reach requestUnlock().
            val covered by rememberScreenCovered()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (covered) Modifier else Modifier.unlockSwipe(::requestUnlock)),
            ) {
                SceneSurface(
                    scene = resolved,
                    assets = assets,
                    modifier = Modifier.fillMaxSize(),
                    widgets = widgets.copy(
                        notificationCount = NotificationMirror.count,
                        nowPlaying = NotificationMirror.nowPlaying,
                    ),
                    // Nothing is visible against a pocket lining, so a covered wake falls back to
                    // the poster frame instead of holding a live decoder. Passing the state rather
                    // than only sampling it at create also covers the phone going into a pocket
                    // while the scene is already up.
                    playVideo = !covered,
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
        assets?.release()
        assets = null
        super.onDestroy()
    }

    companion object {

        fun intent(context: Context): Intent =
            Intent(context, LockScreenActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
    }
}

/**
 * How far up a single gesture has to travel, in pixels, before it counts as a deliberate unlock
 * swipe.
 *
 * This is measured *cumulatively over one gesture*, which is the part that matters. The previous
 * `-18f` was compared against a single drag event's delta, so it was really a velocity test in
 * disguise: it fired on any one frame that happened to move 18px, which a brush against fabric
 * easily does, while a slow but perfectly deliberate swipe on a 120Hz panel might never produce
 * that much in a single frame. Accumulating the gesture makes the number mean what it reads as —
 * total travel — so a firm value rejects incidental contact without making a real swipe harder.
 *
 * 100px is roughly 8mm on a typical modern panel: a clear, intentional flick, still short enough to
 * trigger well before the thumb runs out of screen. Expressing it in dp would be more consistent
 * across densities and is worth doing if device testing shows it feeling different across the §11
 * matrix.
 */
private const val UNLOCK_DRAG_THRESHOLD = -100f

/**
 * The upward-swipe gesture that asks the OS to take over.
 *
 * Applied conditionally, so that while the proximity sensor reads "near" this is simply absent from
 * the modifier chain — detaching it also cancels any gesture already in flight, which is what makes
 * a phone going into a pocket mid-swipe safe rather than merely unlikely to fire.
 */
private fun Modifier.unlockSwipe(onUnlock: () -> Unit): Modifier = pointerInput(Unit) {
    var travelled = 0f
    var requested = false

    detectVerticalDragGestures(
        onDragStart = {
            travelled = 0f
            requested = false
        },
    ) { _, dragAmount ->
        travelled += dragAmount
        // One request per gesture: every further event past the threshold would otherwise fire
        // another requestDismissKeyguard at the system prompt that the first one just raised.
        if (!requested && travelled < UNLOCK_DRAG_THRESHOLD) {
            requested = true
            onUnlock()
        }
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
