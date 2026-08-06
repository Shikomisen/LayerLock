package com.shikomisen.layerlock.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shikomisen.layerlock.data.LayerLockGraph
import kotlinx.coroutines.launch

/**
 * Restarts the lock-screen service after a reboot, if the user turned it on.
 *
 * Without this the feature would silently stop working at the first restart, which is exactly the
 * kind of thing users report as "the app is broken" rather than as a missing feature.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        LayerLockGraph.applicationScope.launch {
            try {
                if (LayerLockGraph.settingsRepository(appContext).snapshot().lockScreenEnabled) {
                    LockScreenService.start(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
