package com.shikomisen.layerlock.data

import android.content.Context
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class SettingsRepository(private val store: DataStore<AppSettings>) {

    constructor(context: Context) : this(createSettingsStore(context.applicationContext))

    val settings: Flow<AppSettings> = store.data

    suspend fun snapshot(): AppSettings = store.data.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }

    suspend fun setOnboardingComplete(complete: Boolean) = update { it.copy(onboardingComplete = complete) }

    suspend fun setSnapToGrid(enabled: Boolean) = update { it.copy(snapToGrid = enabled) }

    suspend fun setSnapToObjects(enabled: Boolean) = update { it.copy(snapToObjects = enabled) }

    suspend fun setStaticMode(enabled: Boolean) = update { it.copy(staticMode = enabled) }

    suspend fun setMaxFps(fps: Int) = update { it.copy(maxFps = fps.coerceIn(1, 60)) }

    suspend fun setLockScreenEnabled(enabled: Boolean) =
        update { it.copy(lockScreenEnabled = enabled) }

    suspend fun setNotificationMirroring(enabled: Boolean) =
        update { it.copy(notificationMirroringEnabled = enabled) }

    suspend fun setShowLayerBounds(enabled: Boolean) = update { it.copy(showLayerBounds = enabled) }

    internal suspend fun cacheProEntitlement(entitled: Boolean) =
        update { it.copy(cachedProEntitlement = entitled) }

    internal suspend fun setDebugForceProEntitlement(enabled: Boolean) =
        update { it.copy(debugForceProEntitlement = enabled) }
}
