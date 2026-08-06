package com.shikomisen.layerlock.canvas

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats clock and date layers.
 *
 * `java.time` is used directly rather than desugared — the app's `minSdk` is 26, which is exactly
 * where the platform gained it.
 */
object ClockFormatter {

    /** Presets offered in the editor, so users never have to know `java.time` pattern syntax. */
    val clockPatterns: List<Pair<String, String>> = listOf(
        "HH:mm" to "24-hour",
        "h:mm" to "12-hour",
        "h:mm a" to "12-hour with am/pm",
        "HH:mm:ss" to "With seconds",
        "H" to "Hour only",
    )

    val datePatterns: List<Pair<String, String>> = listOf(
        "EEEE, d MMMM" to "Monday, 5 August",
        "EEE d MMM" to "Mon 5 Aug",
        "d MMMM yyyy" to "5 August 2026",
        "dd/MM/yyyy" to "05/08/2026",
        "EEEE" to "Weekday only",
        "MMMM" to "Month only",
    )

    private val formatterCache = HashMap<String, DateTimeFormatter>()

    fun format(
        pattern: String,
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val formatter = formatterCache.getOrPut("$pattern/$locale") {
            runCatching { DateTimeFormatter.ofPattern(pattern, locale) }
                .getOrElse { DateTimeFormatter.ofPattern("HH:mm", locale) }
        }
        return runCatching {
            formatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
        }.getOrDefault("")
    }

    /**
     * Milliseconds until the rendered text could next change, for the given pattern.
     *
     * The wallpaper engine uses this to sleep until the next visible change instead of repainting on
     * a timer — a clock that only shows minutes has no reason to wake the CPU 30 times a second.
     */
    fun millisUntilNextChange(pattern: String, epochMillis: Long): Long {
        val hasSeconds = pattern.contains('s')
        return if (hasSeconds) {
            1_000L - (epochMillis % 1_000L)
        } else {
            60_000L - (epochMillis % 60_000L)
        }
    }
}
