package com.shikomisen.layerlock.canvas

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.shikomisen.layerlock.scene.WidgetKind

/** A point-in-time reading of everything the data-driven widget layers can display. */
data class WidgetSnapshot(
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val weather: Weather? = null,
    val steps: Int? = null,
    val nextEvent: String? = null,
    val nowPlaying: String? = null,
    val notificationCount: Int = 0,
) {
    data class Weather(val temperatureC: Float, val condition: String)
}

/**
 * Supplies live values to `widget` layers.
 *
 * Battery is read directly from the platform, which needs no permission and no polling — the sticky
 * `ACTION_BATTERY_CHANGED` broadcast already holds the current value.
 *
 * The remaining sources are deliberately left as seams rather than stubbed with fake data: steps
 * needs activity-recognition consent, calendar needs a runtime permission, now-playing and the
 * notification count come from the opt-in notification listener (§3), and weather needs a network
 * provider with its own key and privacy-policy implications. Each renders as an em dash until wired
 * up, so the layer is still placeable in the editor without inventing numbers.
 */
class WidgetDataSource(private val context: Context) {

    @Volatile
    private var externalSnapshot: WidgetSnapshot = WidgetSnapshot()

    /** Fed by the notification listener and any provider the app configures. */
    fun update(transform: (WidgetSnapshot) -> WidgetSnapshot) {
        externalSnapshot = transform(externalSnapshot)
    }

    fun current(): WidgetSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        return externalSnapshot.copy(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }
}

/** Renders a widget layer's value as the text the canvas draws. */
object WidgetText {

    private const val UNAVAILABLE = "—"

    fun value(kind: WidgetKind, snapshot: WidgetSnapshot): String = when (kind) {
        WidgetKind.BATTERY -> snapshot.batteryPercent?.let { "$it%" } ?: UNAVAILABLE
        WidgetKind.WEATHER -> snapshot.weather
            ?.let { "${it.temperatureC.toInt()}° ${it.condition}" }
            ?: UNAVAILABLE

        WidgetKind.STEPS -> snapshot.steps?.let { "%,d".format(it) } ?: UNAVAILABLE
        WidgetKind.NEXT_EVENT -> snapshot.nextEvent ?: UNAVAILABLE
        WidgetKind.MUSIC -> snapshot.nowPlaying ?: UNAVAILABLE
        WidgetKind.NOTIFICATIONS -> snapshot.notificationCount.toString()
    }

    fun label(kind: WidgetKind): String = when (kind) {
        WidgetKind.BATTERY -> "Battery"
        WidgetKind.WEATHER -> "Weather"
        WidgetKind.STEPS -> "Steps"
        WidgetKind.NEXT_EVENT -> "Next up"
        WidgetKind.MUSIC -> "Now playing"
        WidgetKind.NOTIFICATIONS -> "Notifications"
    }

    /** A single glyph stands in for an icon set, keeping the renderer free of drawable lookups. */
    fun icon(kind: WidgetKind, snapshot: WidgetSnapshot): String = when (kind) {
        WidgetKind.BATTERY -> if (snapshot.isCharging) "⚡" else "▮"
        WidgetKind.WEATHER -> "☁"
        WidgetKind.STEPS -> "▲"
        WidgetKind.NEXT_EVENT -> "◷"
        WidgetKind.MUSIC -> "♪"
        WidgetKind.NOTIFICATIONS -> "●"
    }
}
