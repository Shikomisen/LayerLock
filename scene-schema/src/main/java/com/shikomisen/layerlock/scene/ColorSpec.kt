package com.shikomisen.layerlock.scene

/**
 * Colour parsing shared by every renderer.
 *
 * Scenes store colours as hex strings so the JSON stays human-editable. Both the `#RRGGBB` form
 * used in the §6 example and the `#AARRGGBB` form the editor writes are accepted, plus the short
 * `#RGB` form for hand-written scenes.
 */
object ColorSpec {

    const val WHITE = "#FFFFFFFF"
    const val BLACK = "#FF000000"
    const val TRANSPARENT = "#00000000"

    /** Parses [spec] to a packed ARGB int, falling back to [fallback] for anything unparseable. */
    fun parse(spec: String?, fallback: Int = 0xFFFFFFFF.toInt()): Int {
        val hex = spec?.trim()?.removePrefix("#") ?: return fallback
        val normalised = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> return fallback
        }
        val value = normalised.toLongOrNull(16) ?: return fallback
        return value.toInt()
    }

    /** Formats a packed ARGB int back to the `#AARRGGBB` form the editor writes. */
    fun format(argb: Int): String = "#%08X".format(argb.toLong() and 0xFFFFFFFFL)

    fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * ((argb ushr 24) and 0xFF)).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }
}
