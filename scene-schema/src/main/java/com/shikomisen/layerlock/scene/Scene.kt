package com.shikomisen.layerlock.scene

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The saved-scene data model from §6 of the concept doc.
 *
 * Every layer shares the same [Transform] shape, which is what makes "depth rearrangement" and
 * "resize/move anything" uniform across layer types: the renderer only ever has to understand one
 * positioning contract, no matter what it is drawing.
 *
 * Coordinates are expressed in *canvas* pixels (see [CanvasSize]) with the origin at the top-left,
 * and [Transform.x]/[Transform.y] addressing the layer's **centre**. Renderers scale the whole
 * canvas to whatever surface they are drawing into, so a scene authored on one screen size stays
 * proportionally correct on another.
 */
@Serializable
data class Scene(
    val sceneId: String,
    val name: String,
    val target: ScreenTarget = ScreenTarget.LOCK,
    val canvas: CanvasSize = CanvasSize(),
    val background: Background = Background(),
    val layers: List<Layer> = emptyList(),
    /** Editor-only: spacing of the snap grid, in canvas px. Never affects rendering. */
    val gridSize: Int = DEFAULT_GRID_SIZE,
    val updatedAt: Long = 0L,
) {
    /** Layers in draw order — lowest [Layer.z] first, so higher `z` renders in front. */
    val drawOrder: List<Layer> get() = layers.sortedBy { it.z }

    /** True when anything in the scene needs a continuously repainting surface. */
    val isAnimated: Boolean
        get() = background.type == BackgroundType.VIDEO ||
            layers.any { it is VideoLayer || it is GifLayer || it is ClockLayer }

    companion object {
        const val DEFAULT_GRID_SIZE = 48
    }
}

@Serializable
enum class ScreenTarget {
    @SerialName("lock")
    LOCK,

    @SerialName("home")
    HOME,

    @SerialName("both")
    BOTH,
}

@Serializable
data class CanvasSize(
    val width: Int = 1080,
    val height: Int = 2400,
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}

@Serializable
enum class BackgroundType {
    @SerialName("image")
    IMAGE,

    @SerialName("video")
    VIDEO,

    @SerialName("color")
    COLOR,

    @SerialName("gradient")
    GRADIENT,
}

/** How a media source is fitted to the area it is drawn into. */
@Serializable
enum class ScaleMode {
    @SerialName("cover")
    COVER,

    @SerialName("contain")
    CONTAIN,

    @SerialName("stretch")
    STRETCH,
}

@Serializable
data class Background(
    val type: BackgroundType = BackgroundType.COLOR,
    val sourceUri: String? = null,
    val loop: Boolean = true,
    val muted: Boolean = true,
    /** Solid fill, or the start stop when [type] is [BackgroundType.GRADIENT]. */
    val color: String = "#FF101014",
    /** End stop for [BackgroundType.GRADIENT]. */
    val colorEnd: String = "#FF2A2A38",
    val gradientAngle: Float = 90f,
    val scaleMode: ScaleMode = ScaleMode.COVER,
    /** 0..1 dim applied over the background — the cheapest way to make overlaid text legible. */
    val dim: Float = 0f,
    /**
     * Which part of an oversized background is shown, as a fraction of the canvas.
     *
     * `COVER` has to crop something whenever the source and the canvas disagree on aspect ratio,
     * and cropping to the centre is only the right answer by luck. These shift that crop: `0.1`
     * moves the image right by a tenth of the canvas width.
     *
     * A fraction of the canvas rather than of the overflow, so the number means the same thing in
     * all three places a background gets drawn — the shared canvas renderer, the ExoPlayer surface
     * in the editor and lock screen, and the GL quad in the wallpaper engine.
     */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Zoom on top of [scaleMode]. 1 is the plain fit; larger crops in further. */
    val zoom: Float = 1f,
)

@Serializable
data class Transform(
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    /**
     * Non-uniform stretch applied on top of [scale].
     *
     * Kept separate from [scale] rather than replacing it with a scaleX/scaleY pair for two
     * reasons: scenes already on disk deserialize unchanged because both default to 1, and "scale"
     * stays a single meaningful number for the inspector slider and for pinch-to-zoom. Dragging an
     * edge handle changes only the stretch, so a layer's uniform scale still means what it did.
     */
    val stretchX: Float = 1f,
    val stretchY: Float = 1f,
) {
    /** Total horizontal and vertical scale factors, which is what anything drawing wants. */
    val effectiveScaleX: Float get() = scale * stretchX
    val effectiveScaleY: Float get() = scale * stretchY
}

@Serializable
enum class TextAlign {
    @SerialName("left")
    LEFT,

    @SerialName("center")
    CENTER,

    @SerialName("right")
    RIGHT,
}

@Serializable
data class TextStyleSpec(
    val fontFamily: String = "sans-serif",
    val fontSize: Float = 96f,
    val color: String = "#FFFFFFFF",
    /** 100..900, as in CSS/Compose font weights. */
    val weight: Int = 400,
    val italic: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineHeightMultiplier: Float = 1.1f,
    val shadow: Boolean = false,
    val shadowColor: String = "#B3000000",
    val shadowRadius: Float = 12f,
    val shadowDx: Float = 0f,
    val shadowDy: Float = 4f,
    val align: TextAlign = TextAlign.CENTER,
    val allCaps: Boolean = false,
)

/**
 * One element of the scene stack.
 *
 * Serialised polymorphically with `type` as the discriminator, so the JSON matches §6 exactly —
 * `{"id": "layer-1", "type": "clock", "z": 10, ...}`.
 */
@Serializable
sealed interface Layer {
    val id: String
    val z: Int
    val transform: Transform

    /** Editor metadata only — it never changes how [Transform] is interpreted at render time. */
    val gridSnapped: Boolean
    val opacity: Float
    val visible: Boolean

    /** Short human label for the layer list. */
    val displayName: String
}

@Serializable
@SerialName("clock")
data class ClockLayer(
    override val id: String,
    override val z: Int = 0,
    override val transform: Transform,
    override val gridSnapped: Boolean = false,
    override val opacity: Float = 1f,
    override val visible: Boolean = true,
    val style: TextStyleSpec = TextStyleSpec(),
    /** `java.time` pattern. 24h by default; the editor offers a 12h preset. */
    val pattern: String = "HH:mm",
) : Layer {
    override val displayName: String get() = "Clock"
}

@Serializable
@SerialName("date")
data class DateLayer(
    override val id: String,
    override val z: Int = 0,
    override val transform: Transform,
    override val gridSnapped: Boolean = false,
    override val opacity: Float = 1f,
    override val visible: Boolean = true,
    val style: TextStyleSpec = TextStyleSpec(fontSize = 40f),
    val pattern: String = "EEEE, d MMMM",
) : Layer {
    override val displayName: String get() = "Date"
}

@Serializable
@SerialName("text")
data class TextLayer(
    override val id: String,
    override val z: Int = 0,
    override val transform: Transform,
    override val gridSnapped: Boolean = false,
    override val opacity: Float = 1f,
    override val visible: Boolean = true,
    val text: String = "Text",
    val style: TextStyleSpec = TextStyleSpec(fontSize = 48f),
) : Layer {
    override val displayName: String get() = text.take(24).ifBlank { "Text" }
}

@Serializable
@SerialName("image")
data class ImageLayer(
    override val id: String,
    override val z: Int = 0,
    override val transform: Transform,
    override val gridSnapped: Boolean = false,
    override val opacity: Float = 1f,
    override val visible: Boolean = true,
    val sourceUri: String,
    val cornerRadius: Float = 0f,
) : Layer {
    override val displayName: String get() = "Image"
}

@Serializable
@SerialName("video")
data class VideoLayer(
    override val id: String,
    override val z: Int = 0,
    override val transform: Transform,
    override val gridSnapped: Boolean = false,
    override val opacity: Float = 1f,
    override val visible: Boolean = true,
    val sourceUri: String,
    val loop: Boolean = true,
    val muted: Boolean = true,
    val cornerRadius: Float = 0f,
) : Layer {
    override val displayName: String get() = "Video"
}

@Serializable
@SerialName("gif")
data class GifLayer(
    override val id: String,
    override val z: Int = 0,
    override val transform: Transform,
    override val gridSnapped: Boolean = false,
    override val opacity: Float = 1f,
    override val visible: Boolean = true,
    val sourceUri: String,
) : Layer {
    override val displayName: String get() = "GIF"
}

/**
 * A photo with the subject segmented out, so it can sit *in front of* the clock for a depth effect.
 *
 * [sourceUri] is the original picked photo; [cutoutUri] is the cached alpha-masked result produced
 * on-device by ML Kit. Keeping both means the cutout can be re-generated (different model, better
 * threshold) without asking the user to pick the photo again.
 */
@Serializable
@SerialName("cutout")
data class CutoutLayer(
    override val id: String,
    override val z: Int = 0,
    override val transform: Transform,
    override val gridSnapped: Boolean = false,
    override val opacity: Float = 1f,
    override val visible: Boolean = true,
    val sourceUri: String,
    val cutoutUri: String? = null,
) : Layer {
    override val displayName: String get() = "Cutout"
}

@Serializable
enum class WidgetKind {
    @SerialName("weather")
    WEATHER,

    @SerialName("battery")
    BATTERY,

    @SerialName("steps")
    STEPS,

    @SerialName("next_event")
    NEXT_EVENT,

    @SerialName("music")
    MUSIC,

    @SerialName("notifications")
    NOTIFICATIONS,
}

@Serializable
@SerialName("widget")
data class WidgetLayer(
    override val id: String,
    override val z: Int = 0,
    override val transform: Transform,
    override val gridSnapped: Boolean = false,
    override val opacity: Float = 1f,
    override val visible: Boolean = true,
    val widgetKind: WidgetKind = WidgetKind.BATTERY,
    val style: TextStyleSpec = TextStyleSpec(fontSize = 36f),
    val showIcon: Boolean = true,
    val showLabel: Boolean = true,
) : Layer {
    override val displayName: String
        get() = widgetKind.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
}
