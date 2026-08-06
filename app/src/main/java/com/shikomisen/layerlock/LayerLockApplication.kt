package com.shikomisen.layerlock

import android.app.Application
import com.shikomisen.layerlock.data.LayerLockGraph
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
    }
}
