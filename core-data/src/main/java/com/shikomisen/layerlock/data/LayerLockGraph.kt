package com.shikomisen.layerlock.data

import android.content.Context
import com.shikomisen.layerlock.data.pro.BillingManager
import com.shikomisen.layerlock.data.pro.EntitlementRepository
import com.shikomisen.layerlock.data.pro.IntegrityGate
import com.shikomisen.layerlock.data.pro.PurchaseVerifier
import com.shikomisen.layerlock.data.pro.UnverifiedLocalVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons.
 *
 * A hand-written locator rather than a DI framework, for a specific reason: this app has four
 * separate entry points into the same process — the editor Activity, the `WallpaperService`, the
 * lock-screen Activity and the Glance widget receivers. Only the first of those is a natural fit for
 * constructor injection, and all four need the same [SceneRepository] instance so that a scene saved
 * in the editor is the scene the wallpaper redraws.
 */
object LayerLockGraph {

    /** Set from `Application.onCreate` if a server-side verifier is available (§10). */
    @Volatile
    var purchaseVerifier: PurchaseVerifier = UnverifiedLocalVerifier

    /** Google Cloud project number for Play Integrity. Null disables the integrity request. */
    @Volatile
    var integrityCloudProjectNumber: Long? = null

    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Volatile
    private var sceneRepository: SceneRepository? = null

    @Volatile
    private var settingsRepository: SettingsRepository? = null

    @Volatile
    private var billingManager: BillingManager? = null

    @Volatile
    private var entitlementRepository: EntitlementRepository? = null

    fun sceneRepository(context: Context): SceneRepository =
        sceneRepository ?: synchronized(this) {
            sceneRepository ?: SceneRepository(context.applicationContext).also { sceneRepository = it }
        }

    fun settingsRepository(context: Context): SettingsRepository =
        settingsRepository ?: synchronized(this) {
            settingsRepository
                ?: SettingsRepository(context.applicationContext).also { settingsRepository = it }
        }

    fun billingManager(context: Context): BillingManager =
        billingManager ?: synchronized(this) {
            billingManager ?: BillingManager(context.applicationContext, applicationScope)
                .also { billingManager = it }
        }

    fun entitlements(context: Context): EntitlementRepository =
        entitlementRepository ?: synchronized(this) {
            entitlementRepository ?: EntitlementRepository(
                billing = billingManager(context),
                integrity = IntegrityGate(context.applicationContext, integrityCloudProjectNumber),
                verifier = purchaseVerifier,
                settings = settingsRepository(context),
                scope = applicationScope,
            ).also { entitlementRepository = it }
        }
}
