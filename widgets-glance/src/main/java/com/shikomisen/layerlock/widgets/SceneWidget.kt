package com.shikomisen.layerlock.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.shikomisen.layerlock.canvas.SceneAssets
import com.shikomisen.layerlock.canvas.SceneCanvasRenderer
import com.shikomisen.layerlock.canvas.WidgetDataSource
import com.shikomisen.layerlock.data.LayerLockGraph
import com.shikomisen.layerlock.scene.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A real home-screen widget that renders the user's scene (§8 Phase 4).
 *
 * App widgets are `RemoteViews`, not a surface this app can draw into — so the scene is rendered to
 * a bitmap with the same shared [SceneCanvasRenderer] and handed over as an image. That keeps the
 * widget visually identical to the editor preview and the wallpaper without a fourth renderer.
 *
 * The trade-off is refresh rate: the platform will not update a widget more often than every 30
 * minutes on its own schedule, so a clock inside a widget is not second-accurate. [updateAll] is
 * called whenever the app itself changes a scene, which covers the case users actually notice.
 */
class SceneWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = LayerLockGraph.sceneRepository(context)
        val library = repository.snapshot()
        val scene = library.activeHomeScene ?: library.activeLockScene ?: library.scenes.firstOrNull()

        val widgets = WidgetDataSource(context).current()
        val assets = SceneAssets(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        if (scene != null) {
            assets.prepare(scene)
            // Give decoding a brief chance so the widget does not render every photo as a
            // placeholder. Anything slower than this renders on the next update instead.
            var attempts = 0
            while (attempts < ASSET_WAIT_ATTEMPTS && !assets.isSettled(scene)) {
                delay(ASSET_WAIT_INTERVAL_MS)
                attempts++
            }
        }

        provideContent {
            val size = androidx.glance.LocalSize.current
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (scene == null) {
                    Text(
                        text = "Open LayerLock to design a scene",
                        style = TextStyle(color = ColorProvider(Color.White)),
                    )
                } else {
                    val bitmap = renderScene(
                        scene = scene,
                        assets = assets,
                        widgets = widgets,
                        widthPx = size.width.value.roundToInt(),
                        heightPx = size.height.value.roundToInt(),
                    )
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = scene.name,
                        contentScale = ContentScale.Crop,
                        modifier = GlanceModifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /**
     * Renders the scene at the widget's size.
     *
     * Capped deliberately: a `RemoteViews` bitmap travels over a Binder transaction with a hard size
     * limit, and a too-large widget bitmap is a crash rather than a slow widget.
     */
    private fun renderScene(
        scene: Scene,
        assets: SceneAssets,
        widgets: com.shikomisen.layerlock.canvas.WidgetSnapshot,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val width = widthPx.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
        val height = heightPx.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        SceneCanvasRenderer().draw(
            canvas = Canvas(bitmap),
            scene = scene,
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            frame = SceneCanvasRenderer.Frame(assets = assets, widgets = widgets),
        )
        return bitmap
    }

    companion object {
        private const val MIN_DIMENSION = 96
        private const val MAX_DIMENSION = 1024
        private const val ASSET_WAIT_ATTEMPTS = 20
        private const val ASSET_WAIT_INTERVAL_MS = 50L

        /** Call after any scene change so widgets do not sit stale until the platform's next tick. */
        suspend fun refresh(context: Context) {
            SceneWidget().updateAll(context)
        }
    }
}

class SceneWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SceneWidget()
}
