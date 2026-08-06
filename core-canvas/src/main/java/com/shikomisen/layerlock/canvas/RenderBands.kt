package com.shikomisen.layerlock.canvas

import com.shikomisen.layerlock.scene.BackgroundType
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.VideoLayer

/**
 * Splits a scene into alternating canvas and video bands.
 *
 * Video is the one layer type the shared canvas renderer cannot draw live: decoded frames arrive on
 * a surface, not as bitmaps a `Canvas` can blit cheaply every frame. Rather than special-casing video
 * everywhere, the scene is cut into runs of consecutive canvas-drawable layers separated by the video
 * layers between them. Each host then stacks those bands in order using whatever it has for video —
 * a texture view in the editor and lock screen, a GL quad in the wallpaper engine — and z-ordering
 * keeps working across the boundary, so a cutout really can sit in front of a video.
 */
sealed interface RenderBand {

    /** A run of layers the shared renderer draws directly. */
    data class CanvasBand(
        val layerIds: Set<String>,
        /** True for the band responsible for painting the scene background. */
        val drawsBackground: Boolean,
    ) : RenderBand

    /** A single live video surface. */
    data class VideoBand(
        val id: String,
        val sourceUri: String,
        val loop: Boolean,
        val muted: Boolean,
        /** Null for the background video, which fills the whole surface. */
        val layer: VideoLayer?,
    ) : RenderBand {
        val isBackground: Boolean get() = layer == null
    }

    companion object {

        const val BACKGROUND_VIDEO_ID = "__background_video__"

        fun of(scene: Scene): List<RenderBand> {
            val bands = mutableListOf<RenderBand>()
            val backgroundIsVideo = scene.background.type == BackgroundType.VIDEO &&
                !scene.background.sourceUri.isNullOrBlank()

            if (backgroundIsVideo) {
                bands += VideoBand(
                    id = BACKGROUND_VIDEO_ID,
                    sourceUri = scene.background.sourceUri!!,
                    loop = scene.background.loop,
                    muted = scene.background.muted,
                    layer = null,
                )
            }

            // The first canvas band paints the background unless a video is already covering it.
            var backgroundPending = !backgroundIsVideo
            val pending = LinkedHashSet<String>()

            fun flush() {
                if (pending.isEmpty() && !backgroundPending) return
                bands += CanvasBand(pending.toSet(), drawsBackground = backgroundPending)
                backgroundPending = false
                pending.clear()
            }

            scene.drawOrder.forEach { layer ->
                if (layer is VideoLayer && layer.visible) {
                    flush()
                    bands += VideoBand(
                        id = layer.id,
                        sourceUri = layer.sourceUri,
                        loop = layer.loop,
                        muted = layer.muted,
                        layer = layer,
                    )
                } else {
                    pending += layer.id
                }
            }
            flush()

            return bands
        }

        /** Ids of every layer a host draws with a live video surface rather than the canvas. */
        fun videoLayerIds(bands: List<RenderBand>): Set<String> = bands
            .filterIsInstance<VideoBand>()
            .mapNotNull { it.layer?.id }
            .toSet()
    }
}
