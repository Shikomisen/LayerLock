package com.shikomisen.layerlock.canvas

import com.shikomisen.layerlock.scene.Scene

/**
 * The scene-space to view-space mapping.
 *
 * [SceneCanvasRenderer] applies exactly this transform internally; hosts need it separately to place
 * non-canvas content (video surfaces) and to convert touch coordinates back into scene space. Both
 * derive from this one definition so a tap can never land somewhere other than what was drawn.
 */
data class SceneMapping(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    fun sceneToViewX(x: Float): Float = x * scale + offsetX
    fun sceneToViewY(y: Float): Float = y * scale + offsetY

    fun viewToSceneX(x: Float): Float = (x - offsetX) / scale
    fun viewToSceneY(y: Float): Float = (y - offsetY) / scale

    /** Converts a view-space distance (a drag delta) into scene units. */
    fun viewToSceneDistance(distance: Float): Float = distance / scale

    companion object {
        fun of(scene: Scene, viewWidth: Float, viewHeight: Float): SceneMapping {
            if (viewWidth <= 0f || viewHeight <= 0f) return SceneMapping(1f, 0f, 0f)
            // Cover, matching the renderer: a wallpaper must never letterbox.
            val scale = maxOf(
                viewWidth / scene.canvas.width.toFloat(),
                viewHeight / scene.canvas.height.toFloat(),
            )
            return SceneMapping(
                scale = scale,
                offsetX = (viewWidth - scene.canvas.width * scale) / 2f,
                offsetY = (viewHeight - scene.canvas.height * scale) / 2f,
            )
        }
    }
}
