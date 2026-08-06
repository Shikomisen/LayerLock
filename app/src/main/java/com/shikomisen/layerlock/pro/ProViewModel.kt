package com.shikomisen.layerlock.pro

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shikomisen.layerlock.data.LayerLockGraph
import com.shikomisen.layerlock.data.pro.BillingManager
import com.shikomisen.layerlock.data.pro.ProStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class ProViewModel(application: Application) : AndroidViewModel(application) {

    private val billing = LayerLockGraph.billingManager(application)
    private val entitlements = LayerLockGraph.entitlements(application)

    val status: StateFlow<ProStatus> = entitlements.status
    val price: StateFlow<String?> = billing.proPrice

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            _busy.value = true
            runCatching { billing.loadProProduct() }
            _busy.value = false
        }

        viewModelScope.launch {
            billing.events.collect { event ->
                when (event) {
                    BillingManager.BillingEvent.PurchaseAcknowledged -> {
                        entitlements.refresh()
                        _events.send("Thanks — Pro is unlocked")
                    }

                    BillingManager.BillingEvent.PurchaseCancelled ->
                        _events.send("Purchase cancelled")

                    is BillingManager.BillingEvent.Error -> _events.send(event.message)
                }
            }
        }
    }

    fun purchase(activity: Activity) {
        if (!billing.purchasePro(activity)) {
            viewModelScope.launch {
                _events.send("This product is not available on this device yet")
            }
        }
    }

    /** Play already ties purchases to the account, so "restore" is just a re-query. */
    fun restore() {
        viewModelScope.launch {
            _busy.value = true
            val result = entitlements.refresh()
            _busy.value = false
            _events.send(
                if (result.isPro) "Pro restored" else "No previous purchase found on this account",
            )
        }
    }
}
