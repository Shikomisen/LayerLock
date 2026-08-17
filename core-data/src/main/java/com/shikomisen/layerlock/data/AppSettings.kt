package com.shikomisen.layerlock.data

import kotlinx.serialization.Serializable

/**
 * App-wide preferences.
 *
 * [staticMode] is the explicit escape hatch from §12: the mitigation for battery-drain complaints is
 * partly technical (pause when invisible) and partly giving the user a switch that stops all motion
 * outright, without making them rebuild their scene.
 */
@Serializable
data class AppSettings(
    val onboardingComplete: Boolean = false,
    val snapToGrid: Boolean = false,
    /**
     * Snap a dragged layer's edges and centres to those of the other layers, and snap rotation to
     * right angles.
     *
     * Independent of [snapToGrid] on purpose: the grid is an absolute lattice, this aligns against
     * whatever else is already in the scene. Wanting one is no reason to be forced into the other.
     */
    val snapToObjects: Boolean = true,
    val staticMode: Boolean = false,
    /** Frame cap for the live wallpaper. Lower is kinder to the battery. */
    val maxFps: Int = 30,
    val notificationMirroringEnabled: Boolean = false,
    /** Whether the custom lock-screen surface should be shown when the device wakes. */
    val lockScreenEnabled: Boolean = false,
    val showLayerBounds: Boolean = true,
    /** Local cache of the Play Billing entitlement — never the source of truth. See [ProStatus]. */
    val cachedProEntitlement: Boolean = false,
    /**
     * Debug builds only: unlock Pro without a Play purchase, so the gated features can be exercised
     * without a Play Console product or a test account. Read only behind `BuildConfig.DEBUG` in
     * `EntitlementRepository`, and deliberately never written to [cachedProEntitlement] — a release
     * build that somehow inherited this datastore file still ignores it.
     */
    val debugForceProEntitlement: Boolean = false,
)
