package com.shikomisen.layerlock.data.pro

import com.android.billingclient.api.Purchase
import com.shikomisen.layerlock.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves the Pro entitlement from Play, integrity and server verification, and exposes the single
 * [ProStatus] the UI gates on.
 */
class EntitlementRepository(
    private val billing: BillingManager,
    private val integrity: IntegrityGate,
    private val verifier: PurchaseVerifier,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {

    private val _status = MutableStateFlow<ProStatus>(ProStatus.Free)
    val status: StateFlow<ProStatus> = _status.asStateFlow()

    private val refreshMutex = Mutex()

    init {
        // Start from the cached entitlement so a paying user is never briefly downgraded at launch.
        scope.launch {
            if (settings.snapshot().cachedProEntitlement) {
                _status.value = ProStatus.EntitledUnverified("Restoring from cache")
            }
            refresh()
        }
    }

    suspend fun refresh(): ProStatus = refreshMutex.withLock {
        val connected = billing.ensureConnected()
        val cached = settings.snapshot().cachedProEntitlement

        if (!connected) {
            val reason = (billing.connectionState.value as? BillingManager.ConnectionState.Unavailable)
                ?.reason
                ?: "Play Billing unavailable"
            val status = if (cached) ProStatus.EntitledUnverified(reason) else ProStatus.Unavailable(reason)
            _status.value = status
            return@withLock status
        }

        val proPurchase = billing.refreshPurchases().firstOrNull { purchase ->
            purchase.products.contains(billing.proProductId) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }

        val status = if (proPurchase == null) {
            ProStatus.Free
        } else {
            verify(proPurchase)
        }

        settings.cacheProEntitlement(status.isPro)
        _status.value = status
        status
    }

    private suspend fun verify(purchase: Purchase): ProStatus {
        val integrityToken = if (integrity.isConfigured) {
            integrity.requestToken(purchase.purchaseToken).getOrNull()
        } else {
            null
        }

        val result = verifier.verify(
            PurchaseVerifier.VerificationRequest(
                productId = billing.proProductId,
                purchaseToken = purchase.purchaseToken,
                orderId = purchase.orderId,
                integrityToken = integrityToken,
            ),
        )

        return when (result) {
            is PurchaseVerifier.VerificationResult.Verified -> ProStatus.Entitled
            is PurchaseVerifier.VerificationResult.Rejected -> ProStatus.Free
            is PurchaseVerifier.VerificationResult.Inconclusive ->
                ProStatus.EntitledUnverified(result.reason)
        }
    }

    fun isUnlocked(feature: ProFeature): Boolean = when (feature) {
        // Every Pro feature currently rides on the same one-time unlock.
        else -> _status.value.isPro
    }

    /** Free scenes are layer-capped rather than time-limited (§9). */
    fun layerLimit(): Int = if (_status.value.isPro) Int.MAX_VALUE else FREE_LAYER_LIMIT

    fun canAddLayer(currentLayerCount: Int): Boolean = currentLayerCount < layerLimit()
}
