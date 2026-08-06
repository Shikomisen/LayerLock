package com.shikomisen.layerlock.data.pro

/**
 * The server-side verification seam required by §10.
 *
 * A local `isPro = true` flag is exactly what a patched APK edits, so the entitlement check is
 * expressed as an interface with a deliberately unsatisfying default: [UnverifiedLocalVerifier]
 * accepts any acknowledged Play purchase and is honest about not having verified anything. Shipping
 * to production means implementing this against a backend that re-validates the purchase token with
 * the Play Developer API and checks the Play Integrity verdict.
 */
interface PurchaseVerifier {

    suspend fun verify(request: VerificationRequest): VerificationResult

    data class VerificationRequest(
        val productId: String,
        val purchaseToken: String,
        val orderId: String?,
        /** Play Integrity token, when one could be obtained. Null offline or when unconfigured. */
        val integrityToken: String?,
    )

    sealed interface VerificationResult {
        data object Verified : VerificationResult
        data class Rejected(val reason: String) : VerificationResult
        /** Could not reach the verifier. Callers keep the cached entitlement and retry later. */
        data class Inconclusive(val reason: String) : VerificationResult
    }
}

/**
 * Development default. Trusts Play's own client-side purchase state and nothing more.
 *
 * This is a real, if minimal, bar: the purchase still has to exist in the Play Billing client and be
 * acknowledged. It stops nothing more determined than that, which is why it reports its results as
 * unverified rather than pretending otherwise.
 */
object UnverifiedLocalVerifier : PurchaseVerifier {
    override suspend fun verify(
        request: PurchaseVerifier.VerificationRequest,
    ): PurchaseVerifier.VerificationResult =
        PurchaseVerifier.VerificationResult.Inconclusive("No server-side verifier configured")
}
