package com.shikomisen.layerlock

import android.app.Application
import com.shikomisen.layerlock.data.LayerLockGraph
import com.shikomisen.layerlock.lockscreen.LockScreenService
import kotlinx.coroutines.launch

class LayerLockApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Where a production build would install a server-backed verifier and the Play Console cloud
        // project number (§10). Left unset here, which the entitlement layer reports honestly as an
        // unverified — not a failed — entitlement.
        // LayerLockGraph.purchaseVerifier = BackendPurchaseVerifier(...)
        // LayerLockGraph.integrityCloudProjectNumber = 000000000000L

        LayerLockGraph.applicationScope.launch {
            LayerLockGraph.sceneRepository(this@LayerLockApplication).seedIfEmpty()
        }

        restoreLockScreenService()
    }

    /**
     * Restarts the lock-screen service if the user has it switched on.
     *
     * [BootReceiver] covers a reboot, but nothing covered the other ways this process ends — a crash,
     * a force-stop, a reinstall, or the low-memory killer. In every one of those cases the service
     * stayed down while the setting still said "on", so the lock scene silently stopped appearing
     * until the toggle was flipped off and back on. Doing it here means any process start heals it.
     *
     * Best-effort by design: starting a foreground service is not always permitted from the
     * background (Android 12+), and a refusal here is not worth crashing the app over — the next
     * launch, or the toggle, will start it.
     */
    private fun restoreLockScreenService() {
        LayerLockGraph.applicationScope.launch {
            val enabled = LayerLockGraph.settingsRepository(this@LayerLockApplication)
                .snapshot()
                .lockScreenEnabled
            if (enabled) {
                runCatching { LockScreenService.start(this@LayerLockApplication) }
            }
        }
    }
}
