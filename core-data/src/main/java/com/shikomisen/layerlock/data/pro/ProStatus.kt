package com.shikomisen.layerlock.data.pro

/**
 * What the app knows about the user's Pro entitlement right now.
 *
 * The distinction between [Entitled] and [EntitledUnverified] is the point of this type. §10 asks for
 * Pro to be gated on a *verified* purchase rather than a local boolean; but a user who is offline, or
 * whose device fails a Play Integrity check for benign reasons (a legitimately rooted phone, a
 * developer build), must not lose access to features they paid for. So the app trusts the cached
 * entitlement for access, and reports separately whether that trust has been confirmed.
 */
sealed interface ProStatus {

    /** No purchase found. */
    data object Free : ProStatus

    /** Purchase found and confirmed — acknowledged with Play, and integrity-checked if configured. */
    data object Entitled : ProStatus

    /**
     * A purchase is cached locally but has not been confirmed this session — typically offline.
     * Features stay unlocked; the app re-checks when connectivity returns.
     */
    data class EntitledUnverified(val reason: String) : ProStatus

    /** Billing is unavailable on this device or the store connection failed. */
    data class Unavailable(val reason: String) : ProStatus

    val isPro: Boolean
        get() = this is Entitled || this is EntitledUnverified
}

/** The features §9 puts behind the one-time unlock. The free tier stays genuinely usable. */
enum class ProFeature(val label: String, val rationale: String) {
    UNLIMITED_LAYERS(
        "Unlimited layers",
        "Free scenes are capped at $FREE_LAYER_LIMIT layers.",
    ),
    CUTOUT_TOOL(
        "Cutout tool",
        "On-device subject cutouts for depth layering.",
    ),
    VIDEO_LAYERS(
        "Video & GIF layers",
        "Animated layers on top of the background.",
    ),
    PREMIUM_FONTS(
        "Full font library",
        "Every font family, weight and style.",
    ),
    NO_WATERMARK(
        "No watermark",
        "Exports and wallpapers without the LayerLock mark.",
    ),
}

/** Free-tier layer cap — a cap is friendlier than time-limiting the whole app (§9). */
const val FREE_LAYER_LIMIT = 5
