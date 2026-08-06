package com.shikomisen.layerlock.data.pro

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Play Integrity token requests (§10).
 *
 * The token is only ever *collected* here — the verdict inside it is signed for a backend to read, so
 * this class deliberately cannot decide whether the device passed. That decision belongs to
 * [PurchaseVerifier]. SafetyNet, the predecessor to this API, was retired in January 2025 and is not
 * used anywhere in this app.
 */
class IntegrityGate(
    private val context: Context,
    /** Google Cloud project number from the Play Console. Integrity is skipped while this is null. */
    private val cloudProjectNumber: Long? = null,
) {

    val isConfigured: Boolean get() = cloudProjectNumber != null

    /**
     * Requests an integrity token bound to [purchaseToken] via a nonce, so a captured token cannot be
     * replayed against a different purchase.
     */
    suspend fun requestToken(purchaseToken: String): Result<String> {
        val projectNumber = cloudProjectNumber
            ?: return Result.failure(IllegalStateException("Play Integrity is not configured"))

        return runCatching {
            suspendCancellableCoroutine { continuation ->
                val request = IntegrityTokenRequest.builder()
                    .setNonce(nonceFor(purchaseToken))
                    .setCloudProjectNumber(projectNumber)
                    .build()

                IntegrityManagerFactory.create(context.applicationContext)
                    .requestIntegrityToken(request)
                    .addOnSuccessListener { response -> continuation.resume(response.token()) }
                    .addOnFailureListener { error -> continuation.cancel(error) }
            }
        }
    }

    /** URL-safe, unpadded base64 of the SHA-256 digest, as the Integrity API requires. */
    private fun nonceFor(purchaseToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(purchaseToken.encodeToByteArray())
        return android.util.Base64.encodeToString(
            digest,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }
}
