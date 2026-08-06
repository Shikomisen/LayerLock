package com.shikomisen.layerlock.canvas

import android.graphics.Typeface
import android.os.Build

/**
 * Font families offered by the editor, and the resolution of a stored family name to a [Typeface].
 *
 * Scenes store a family *name* rather than a font resource id, so a scene exported from one build
 * still opens in another even if the bundled font set has changed — an unknown family degrades to
 * the default rather than failing to render.
 */
object FontCatalog {

    data class Family(
        val id: String,
        val label: String,
        val isPro: Boolean = false,
    )

    /**
     * System families are available on every device without shipping font binaries, which keeps the
     * APK small. Anything beyond these would need to be bundled and licensed for redistribution.
     */
    val families: List<Family> = listOf(
        Family("sans-serif", "Sans"),
        Family("sans-serif-light", "Sans Light"),
        Family("sans-serif-thin", "Sans Thin"),
        Family("sans-serif-medium", "Sans Medium", isPro = true),
        Family("sans-serif-black", "Sans Black", isPro = true),
        Family("sans-serif-condensed", "Sans Condensed", isPro = true),
        Family("serif", "Serif"),
        Family("serif-monospace", "Serif Mono", isPro = true),
        Family("monospace", "Mono"),
        Family("casual", "Casual", isPro = true),
        Family("cursive", "Cursive", isPro = true),
    )

    val freeFamilies: List<Family> get() = families.filterNot { it.isPro }

    val weights: List<Int> = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)

    private val cache = HashMap<String, Typeface>()

    /**
     * Resolves a family name plus a CSS-style weight to a concrete typeface.
     *
     * On API 28+ the platform can synthesise an arbitrary weight from a family; below that it can
     * only pick between normal and bold, so weights are bucketed rather than ignored.
     */
    fun resolve(family: String, weight: Int = 400, italic: Boolean = false): Typeface {
        val key = "$family/$weight/$italic"
        return cache.getOrPut(key) {
            val base = runCatching { Typeface.create(family, Typeface.NORMAL) }
                .getOrDefault(Typeface.DEFAULT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, weight.coerceIn(1, 1000), italic)
            } else {
                val style = when {
                    weight >= 600 && italic -> Typeface.BOLD_ITALIC
                    weight >= 600 -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                Typeface.create(base, style)
            }
        }
    }
}
