package com.shikomisen.layerlock.data.pro

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Play Billing wrapper for the single one-time "Pro" unlock (§9).
 *
 * One-time product, not a subscription — deliberately, and the doc explains why: Widgetsmith took
 * real reputational damage moving a previously one-off unlock behind a subscription. A subscription
 * here would also have no ongoing cost behind it to justify itself.
 *
 * Purchases are account-scoped by Play automatically, and Play Family Library does not extend to
 * in-app purchases, so no extra work is needed to keep the unlock tied to the buyer.
 */
class BillingManager(
    context: Context,
    private val scope: CoroutineScope,
) {

    /** Must match the in-app product id created in the Play Console. */
    val proProductId: String = PRO_PRODUCT_ID

    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())
    val purchases: StateFlow<List<Purchase>> = _purchases.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<BillingEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<BillingEvent> = _events.asSharedFlow()

    private val connectionMutex = Mutex()
    private var pendingConnection: CompletableDeferred<Boolean>? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                scope.launch { handlePurchases(purchases.orEmpty()) }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _events.tryEmit(BillingEvent.PurchaseCancelled)
            }

            else -> {
                _events.tryEmit(BillingEvent.Error(result.debugMessage.ifBlank { "Purchase failed" }))
            }
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data object Connected : ConnectionState
        data class Unavailable(val reason: String) : ConnectionState
    }

    sealed interface BillingEvent {
        data object PurchaseCancelled : BillingEvent
        data object PurchaseAcknowledged : BillingEvent
        data class Error(val message: String) : BillingEvent
    }

    /** Connects if needed. Returns false when billing is unavailable on this device. */
    suspend fun ensureConnected(): Boolean = connectionMutex.withLock {
        if (client.isReady) {
            _connectionState.value = ConnectionState.Connected
            return@withLock true
        }

        val deferred = CompletableDeferred<Boolean>()
        pendingConnection = deferred
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                _connectionState.value = if (ok) {
                    ConnectionState.Connected
                } else {
                    ConnectionState.Unavailable(result.debugMessage.ifBlank { "Billing unavailable" })
                }
                pendingConnection?.complete(ok)
                pendingConnection = null
            }

            override fun onBillingServiceDisconnected() {
                _connectionState.value = ConnectionState.Disconnected
                pendingConnection?.complete(false)
                pendingConnection = null
            }
        })
        deferred.await()
    }

    private val _proPrice = MutableStateFlow<String?>(null)

    /**
     * Localised price of the Pro unlock, or null until Play has answered.
     *
     * Exposed as a string rather than as `ProductDetails` deliberately: nothing above this module
     * should need the Billing library on its classpath, so the UI cannot accidentally start
     * depending on Play Billing types.
     */
    val proPrice: StateFlow<String?> = _proPrice.asStateFlow()

    private var cachedProductDetails: ProductDetails? = null

    /** Fetches product details and publishes the price. Safe to call repeatedly. */
    suspend fun loadProProduct(): Boolean {
        val details = proProductDetails()
        cachedProductDetails = details
        _proPrice.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
        return details != null
    }

    /** Starts the Play purchase flow. Returns false when the product could not be loaded. */
    fun purchasePro(activity: Activity): Boolean {
        val details = cachedProductDetails ?: return false
        launchPurchaseFlow(activity, details)
        return true
    }

    private suspend fun proProductDetails(): ProductDetails? {
        if (!ensureConnected()) return null

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(proProductId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()

        val result = client.queryProductDetails(params)
        return result.productDetailsList?.firstOrNull { it.productId == proProductId }
    }

    /** Refreshes owned purchases from Play. This is the restore path — no separate button needed. */
    suspend fun refreshPurchases(): List<Purchase> {
        if (!ensureConnected()) return emptyList()

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = client.queryPurchasesAsync(params)
        val owned = result.purchasesList
        handlePurchases(owned)
        return owned
    }

    private fun launchPurchaseFlow(activity: Activity, productDetails: ProductDetails): BillingResult {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build(),
                ),
            )
            .build()
        return client.launchBillingFlow(activity, params)
    }

    /**
     * Acknowledges anything newly purchased. Play automatically refunds unacknowledged purchases
     * after three days, so this is not optional bookkeeping.
     */
    private suspend fun handlePurchases(purchases: List<Purchase>) {
        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase ->
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                val result = client.acknowledgePurchase(params)
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _events.tryEmit(BillingEvent.PurchaseAcknowledged)
                }
            }
        _purchases.value = purchases
    }

    fun release() {
        if (client.isReady) client.endConnection()
    }

    companion object {
        const val PRO_PRODUCT_ID = "layerlock_pro_unlock"
    }
}
