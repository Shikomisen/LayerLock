package com.shikomisen.layerlock.canvas

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shikomisen.layerlock.scene.ClockLayer
import com.shikomisen.layerlock.scene.GifLayer
import com.shikomisen.layerlock.scene.Scene
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Renders a [Scene] on screen, for the editor preview and the lock-screen Activity.
 *
 * Composed of stacked [RenderBand]s: canvas bands go through the shared [SceneCanvasRenderer], and
 * video bands become real ExoPlayer surfaces, so depth ordering holds across both.
 */
@Composable
fun SceneSurface(
    scene: Scene,
    assets: SceneAssets,
    modifier: Modifier = Modifier,
    widgets: WidgetSnapshot = WidgetSnapshot(),
    editor: SceneCanvasRenderer.EditorOverlay? = null,
    playVideo: Boolean = true,
    watermark: Boolean = false,
    timeMillis: Long = rememberSceneTime(scene).value,
) {
    val renderer = remember { SceneCanvasRenderer() }
    // A paused host draws poster frames instead of holding live players — see [RenderBand.posterOnly].
    val bands = remember(scene, playVideo) {
        if (playVideo) RenderBand.of(scene) else RenderBand.posterOnly(scene)
    }
    val videoLayerIds = remember(bands, playVideo) {
        if (playVideo) RenderBand.videoLayerIds(bands) else emptySet()
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(assets, scene) {
        assets.prepare(scene)
        onDispose { }
    }

    Box(modifier.onSizeChanged { viewSize = it }) {
        bands.forEachIndexed { index, band ->
            when (band) {
                is RenderBand.CanvasBand -> Canvas(Modifier.fillMaxSize()) {
                    // Reading the generation inside the draw scope means a newly decoded asset
                    // invalidates just this draw, with no recomposition needed.
                    @Suppress("UNUSED_VARIABLE")
                    val generation = assets.generation.value

                    renderer.drawLayers(
                        canvas = drawContext.canvas.nativeCanvas,
                        scene = scene,
                        layerIds = band.layerIds,
                        viewWidth = size.width,
                        viewHeight = size.height,
                        frame = SceneCanvasRenderer.Frame(
                            timeMillis = timeMillis,
                            assets = assets,
                            widgets = widgets,
                            externallyDrawnLayerIds = videoLayerIds,
                            skipBackground = !band.drawsBackground,
                            watermark = watermark && index == bands.lastIndex,
                            editor = editor,
                        ),
                    )
                }

                is RenderBand.VideoBand -> VideoBandSurface(
                    band = band,
                    scene = scene,
                    assets = assets,
                    widgets = widgets,
                    playing = playVideo,
                    timeMillis = timeMillis,
                    viewSize = viewSize,
                )
            }
        }
    }
}

/**
 * A live video surface positioned by the same transform maths the canvas renderer uses.
 *
 * `PlayerView` is used rather than a bare `TextureView` for one reason worth the unstable-API
 * opt-in: it already implements the cover/contain fitting a wallpaper needs, and getting that subtly
 * wrong is exactly how video wallpapers end up stretched on tall screens.
 */
@OptIn(UnstableApi::class)
@Composable
private fun BoxScope.VideoBandSurface(
    band: RenderBand.VideoBand,
    scene: Scene,
    assets: SceneAssets,
    widgets: WidgetSnapshot,
    playing: Boolean,
    timeMillis: Long,
    viewSize: IntSize,
) {
    // Compose previews have no media pipeline; the canvas placeholder stands in there.
    if (LocalInspectionMode.current) return

    val context = LocalContext.current
    val density = LocalDensity.current

    val player = remember(band.sourceUri) {
        VideoPlayers.build(context).apply {
            setMediaItem(MediaItem.fromUri(band.sourceUri))
            repeatMode = if (band.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (band.muted) 0f else 1f
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(playing, player) {
        player.playWhenReady = playing
    }

    val placement = if (band.isBackground) {
        // `RESIZE_MODE_ZOOM` below already covers; this shifts which part of that cover is on
        // screen, matching what the canvas renderer does to a still background.
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val zoom = scene.background.zoom.coerceAtLeast(1f)
                scaleX = zoom
                scaleY = zoom
                translationX = scene.background.offsetX * size.width
                translationY = scene.background.offsetY * size.height
            }
    } else {
        val layer = band.layer!!
        val size = LayerGeometry.scaledSize(layer, scene, assets, widgets, timeMillis)
        val mapping = SceneMapping.of(scene, viewSize.width.toFloat(), viewSize.height.toFloat())
        val widthPx = (size.width * mapping.scale).coerceAtLeast(1f)
        val heightPx = (size.height * mapping.scale).coerceAtLeast(1f)
        val centerX = mapping.sceneToViewX(layer.transform.x)
        val centerY = mapping.sceneToViewY(layer.transform.y)

        Modifier
            .align(Alignment.TopStart)
            .offset {
                IntOffset(
                    (centerX - widthPx / 2f).roundToInt(),
                    (centerY - heightPx / 2f).roundToInt(),
                )
            }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .graphicsLayer {
                rotationZ = layer.transform.rotation
                alpha = layer.opacity
            }
    }

    AndroidView(
        modifier = placement,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = if (band.isBackground) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        update = { view -> view.player = player },
    )
}

/**
 * The current time, repainted only as often as the scene actually changes.
 *
 * A scene with animated content needs every frame; a scene whose only moving part is a clock showing
 * minutes needs one repaint a minute. Getting this wrong is the difference between a wallpaper that
 * costs nothing and the battery complaints §12 warns about.
 */
@Composable
fun rememberSceneTime(scene: Scene, enabled: Boolean = true): State<Long> {
    val needsEveryFrame = remember(scene) { scene.layers.any { it is GifLayer } }
    val clockPattern = remember(scene) {
        scene.layers.filterIsInstance<ClockLayer>()
            .minByOrNull { if (it.pattern.contains('s')) 0 else 1 }
            ?.pattern
    }

    return produceState(System.currentTimeMillis(), scene, enabled, needsEveryFrame) {
        if (!enabled) return@produceState
        if (needsEveryFrame) {
            while (isActive) {
                withFrameMillis { value = System.currentTimeMillis() }
            }
        } else {
            while (isActive) {
                val now = System.currentTimeMillis()
                value = now
                delay(clockPattern?.let { ClockFormatter.millisUntilNextChange(it, now) } ?: 60_000L)
            }
        }
    }
}
