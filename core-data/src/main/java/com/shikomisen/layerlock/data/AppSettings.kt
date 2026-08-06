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
    val staticMode: Boolean = false,
    /** Frame cap for the live wallpaper. Lower is kinder to the battery. */
    val maxFps: Int = 30,
    val notificationMirroringEnabled: Boolean = false,
    /** Whether the custom lock-screen surface should be shown when the device wakes. */
    val lockScreenEnabled: Boolean = false,
    val showLayerBounds: Boolean = true,
    /** Local cache of the Play Billing entitlement — never the source of truth. See [ProStatus]. */
    val cachedProEntitlement: Boolean = false,
)
