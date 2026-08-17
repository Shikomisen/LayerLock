package com.shikomisen.layerlock.editor

import com.shikomisen.layerlock.canvas.AssetSource
import com.shikomisen.layerlock.canvas.LayerGeometry
import com.shikomisen.layerlock.canvas.SceneCanvasRenderer
import com.shikomisen.layerlock.canvas.WidgetSnapshot
import com.shikomisen.layerlock.scene.Layer
import com.shikomisen.layerlock.scene.Scene
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Alignment snapping for the editor.
 *
 * Editor-only, which is why it lives here rather than in `SceneOps`: the scene that gets saved holds
 * plain absolute coordinates either way, and nothing outside the editor ever needs to know a layer
 * arrived at its position by being snapped. It sits in the app module rather than `scene-schema`
 * for the harder reason — it needs [LayerGeometry] to know how big a layer actually is, and
 * `scene-schema` deliberately knows nothing about measuring text or decoding images.
 *
 * This is distinct from the grid snap in `SceneOps`: the grid is an absolute lattice, this aligns a
 * layer against whatever else is already in the scene.
 */
object EditorSnapping {

    /** How close an edge has to get before it snaps, in scene px. */
    const val POSITION_TOLERANCE = 14f

    /** How close to a right angle a rotation has to get before it snaps, in degrees. */
    const val ROTATION_TOLERANCE = 7f

    data class Snapped(
        val x: Float,
        val y: Float,
        val guides: List<SceneCanvasRenderer.Guide>,
    )

    /**
     * Pulls [x]/[y] onto the nearest alignment of another layer or the canvas itself.
     *
     * Each axis is resolved independently — a layer can be left-aligned to one neighbour while its
     * centre lines up with a different one, which is what makes this useful for laying out a stack
     * of text. Only the single closest candidate per axis is applied, so two near-coincident targets
     * cannot fight over the same drag.
     */
    fun snapPosition(
        scene: Scene,
        layer: Layer,
        x: Float,
        y: Float,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
        tolerance: Float = POSITION_TOLERANCE,
    ): Snapped {
        val (halfWidth, halfHeight) = halfExtents(layer, scene, assets, widgets, timeMillis)

        val targetsX = mutableListOf(0f, scene.canvas.width / 2f, scene.canvas.width.toFloat())
        val targetsY = mutableListOf(0f, scene.canvas.height / 2f, scene.canvas.height.toFloat())

        scene.layers.forEach { other ->
            if (other.id == layer.id || !other.visible) return@forEach
            val (otherHalfWidth, otherHalfHeight) = halfExtents(other, scene, assets, widgets, timeMillis)
            val centreX = other.transform.x
            val centreY = other.transform.y
            targetsX += listOf(centreX - otherHalfWidth, centreX, centreX + otherHalfWidth)
            targetsY += listOf(centreY - otherHalfHeight, centreY, centreY + otherHalfHeight)
        }

        val guides = mutableListOf<SceneCanvasRenderer.Guide>()
        val snappedX = resolve(x, halfWidth, targetsX, tolerance)?.also {
            guides += SceneCanvasRenderer.Guide(vertical = true, position = it.target)
        }
        val snappedY = resolve(y, halfHeight, targetsY, tolerance)?.also {
            guides += SceneCanvasRenderer.Guide(vertical = false, position = it.target)
        }

        return Snapped(
            x = snappedX?.let { x + it.correction } ?: x,
            y = snappedY?.let { y + it.correction } ?: y,
            guides = guides,
        )
    }

    /**
     * Snaps a single dragged point — a resize handle — to the alignments around it.
     *
     * Distinct from [snapPosition] because a handle *is* the edge being aligned, so there are no
     * three candidate edges to choose between: the point either lands on a guide or it does not.
     */
    fun snapPoint(
        scene: Scene,
        excludeLayerId: String,
        x: Float,
        y: Float,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
        tolerance: Float = POSITION_TOLERANCE,
    ): Snapped {
        val targetsX = mutableListOf(0f, scene.canvas.width / 2f, scene.canvas.width.toFloat())
        val targetsY = mutableListOf(0f, scene.canvas.height / 2f, scene.canvas.height.toFloat())

        scene.layers.forEach { other ->
            if (other.id == excludeLayerId || !other.visible) return@forEach
            val (halfWidth, halfHeight) = halfExtents(other, scene, assets, widgets, timeMillis)
            targetsX += listOf(
                other.transform.x - halfWidth,
                other.transform.x,
                other.transform.x + halfWidth,
            )
            targetsY += listOf(
                other.transform.y - halfHeight,
                other.transform.y,
                other.transform.y + halfHeight,
            )
        }

        val guides = mutableListOf<SceneCanvasRenderer.Guide>()
        val nearestX = targetsX.minByOrNull { abs(it - x) }?.takeIf { abs(it - x) <= tolerance }
        val nearestY = targetsY.minByOrNull { abs(it - y) }?.takeIf { abs(it - y) <= tolerance }
        nearestX?.let { guides += SceneCanvasRenderer.Guide(vertical = true, position = it) }
        nearestY?.let { guides += SceneCanvasRenderer.Guide(vertical = false, position = it) }

        return Snapped(x = nearestX ?: x, y = nearestY ?: y, guides = guides)
    }

    private class Match(val correction: Float, val target: Float)

    /**
     * Best correction that brings one of the layer's three edges (near, centre, far) onto a target.
     */
    private fun resolve(
        centre: Float,
        halfExtent: Float,
        targets: List<Float>,
        tolerance: Float,
    ): Match? {
        var best: Match? = null
        listOf(centre - halfExtent, centre, centre + halfExtent).forEach { edge ->
            targets.forEach { target ->
                val correction = target - edge
                if (abs(correction) > tolerance) return@forEach
                if (best == null || abs(correction) < abs(best!!.correction)) {
                    best = Match(correction, target)
                }
            }
        }
        return best
    }

    /**
     * Snaps to the nearest right angle.
     *
     * Deliberately only 0/90/180/270 rather than every 45°: those four are the ones that read as
     * "straight", and a 45° detent in between would fight a deliberate diagonal.
     */
    fun snapRotation(degrees: Float, tolerance: Float = ROTATION_TOLERANCE): Float {
        val nearest = (degrees / 90f).roundToInt() * 90f
        return if (abs(degrees - nearest) <= tolerance) normalise(nearest) else degrees
    }

    private fun normalise(degrees: Float): Float {
        val result = degrees % 360f
        return if (result < 0f) result + 360f else result
    }

    /**
     * Half the layer's *rotated* footprint.
     *
     * Aligning a rotated layer by its unrotated box would snap an edge the user cannot see, so this
     * uses the same axis-aligned envelope [LayerGeometry.rotatedBounds] draws.
     */
    private fun halfExtents(
        layer: Layer,
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot,
        timeMillis: Long,
    ): Pair<Float, Float> {
        val size = LayerGeometry.scaledSize(layer, scene, assets, widgets, timeMillis)
        val radians = Math.toRadians(layer.transform.rotation.toDouble())
        val cos = abs(cos(radians)).toFloat()
        val sin = abs(sin(radians)).toFloat()
        return (size.width * cos + size.height * sin) / 2f to
            (size.width * sin + size.height * cos) / 2f
    }
}
