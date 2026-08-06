package com.shikomisen.layerlock.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.shikomisen.layerlock.canvas.ClockFormatter
import com.shikomisen.layerlock.canvas.SceneAssets
import com.shikomisen.layerlock.canvas.SceneCanvasRenderer
import com.shikomisen.layerlock.canvas.WidgetDataSource
import com.shikomisen.layerlock.canvas.WidgetSnapshot
import com.shikomisen.layerlock.data.AppSettings
import com.shikomisen.layerlock.data.LayerLockGraph
import com.shikomisen.layerlock.scene.BackgroundType
import com.shikomisen.layerlock.scene.ClockLayer
import com.shikomisen.layerlock.scene.GifLayer
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.ScenePresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The live wallpaper (§8 Phase 2).
 *
 * Wraps the same [SceneCanvasRenderer] the editor uses — no second renderer, no drift between what
 * the user designed and what their home screen shows.
 */
class LayerLockWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SceneWallpaperEngine()

    /**
     * Rendering engine for one wallpaper surface.
     *
     * The single most important thing this class does is **stop drawing**. Live wallpapers are the
     * top battery-complaint category in this app class (§11/§12), and the fix is not micro-optimising
     * the draw call — it is not making it at all. So: nothing is drawn while the surface is invisible,
     * a static scene is drawn once and then left alone, and a scene whose only moving part is a clock
     * sleeps until the minute actually changes rather than running a frame loop.
     */
    private inner class SceneWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = SceneCanvasRenderer()
        private val widgetDataSource = WidgetDataSource(this@LayerLockWallpaperService)
        private val assets = SceneAssets(this@LayerLockWallpaperService, scope)

        private var scene: Scene? = null
        private var settings = AppSettings()
        private var widgets = WidgetSnapshot()

        private var isVisible = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        /**
         * Whether the scene flow has produced a value yet (`null` counts).
         *
         * Nothing may be drawn before it has. `lockCanvas` connects the surface's BufferQueue to the
         * CPU producer, and `eglCreateWindowSurface` on an already-connected surface fails — which
         * latches [glUnavailable] for the rest of the session and silently downgrades a video
         * wallpaper to its poster frame. The scene arrives asynchronously, so drawing a placeholder
         * first is exactly how a video scene loses GL before it ever gets to ask for it.
         */
        private var sceneLoaded = false

        private var gl: GlWallpaperRenderer? = null
        private var glUnavailable = false
        private var player: ExoPlayer? = null
        private var videoSize = VideoSize.UNKNOWN

        private var overlayBitmap: Bitmap? = null
        private var overlayKey: String? = null

        private val drawRunnable = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            assets.onAssetLoaded = { requestDraw() }

            val sceneRepository = LayerLockGraph.sceneRepository(this@LayerLockWallpaperService)
            val settingsRepository = LayerLockGraph.settingsRepository(this@LayerLockWallpaperService)

            scope.launch {
                combine(sceneRepository.library, settingsRepository.settings) { library, appSettings ->
                    // The home surface is what a wallpaper actually paints; the lock scene is the
                    // fallback so a user who only configured a lock scene still sees it.
                    (library.activeHomeScene ?: library.activeLockScene) to appSettings
                }.collect { (activeScene, appSettings) ->
                    onSceneChanged(activeScene, appSettings)
                }
            }
        }

        private fun onSceneChanged(newScene: Scene?, newSettings: AppSettings) {
            val previous = scene
            sceneLoaded = true
            scene = newScene
            settings = newSettings
            widgets = widgetDataSource.current()
            overlayKey = null

            newScene?.let { assets.prepare(it) }

            val backgroundChanged = previous?.background?.sourceUri != newScene?.background?.sourceUri ||
                previous?.background?.type != newScene?.background?.type
            if (backgroundChanged || newSettings.staticMode) {
                teardownVideo()
            }
            setUpSurfaceIfNeeded()
            requestDraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                widgets = widgetDataSource.current()
                player?.play()
                requestDraw()
            } else {
                // Everything stops: no frame callbacks, no decoding, no wake-ups.
                handler.removeCallbacks(drawRunnable)
                player?.pause()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            gl?.resize(width, height)
            releaseOverlayBitmap()
            setUpSurfaceIfNeeded()
            requestDraw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(drawRunnable)
            teardownVideo()
            // A fresh surface deserves a fresh attempt: the usual reason GL failed is a property of
            // the surface we are now discarding, not of the device.
            glUnavailable = false
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(drawRunnable)
            teardownVideo()
            assets.release()
            releaseOverlayBitmap()
            scope.cancel()
            super.onDestroy()
        }

        // -- Video ----------------------------------------------------------------------------

        private fun usesVideoBackground(): Boolean {
            val current = scene ?: return false
            return !settings.staticMode &&
                current.background.type == BackgroundType.VIDEO &&
                !current.background.sourceUri.isNullOrBlank() &&
                !glUnavailable
        }

        private fun setUpSurfaceIfNeeded() {
            if (!usesVideoBackground()) {
                val current = scene
                // Why a video scene fell back to the static canvas is otherwise invisible from
                // outside the process, and it has several independent causes.
                Log.i(
                    TAG,
                    "No video path: staticMode=${settings.staticMode} " +
                        "type=${current?.background?.type} " +
                        "hasUri=${!current?.background?.sourceUri.isNullOrBlank()} " +
                        "glUnavailable=$glUnavailable",
                )
                teardownVideo()
                return
            }
            if (gl?.isReady == true || surfaceWidth <= 0 || surfaceHeight <= 0) {
                Log.i(TAG, "Video setup deferred: ready=${gl?.isReady} size=${surfaceWidth}x$surfaceHeight")
                return
            }

            val renderer = GlWallpaperRenderer(surfaceHolder.surface, surfaceWidth, surfaceHeight)
            if (!renderer.initialise()) {
                // One failure is enough — fall back to the canvas path for the rest of this session
                // rather than retrying a broken GL stack on every frame.
                glUnavailable = true
                gl = null
                return
            }
            gl = renderer
            Log.i(TAG, "GL video path active at ${surfaceWidth}x$surfaceHeight")

            val texture = renderer.surfaceTexture ?: return
            texture.setDefaultBufferSize(surfaceWidth, surfaceHeight)
            texture.setOnFrameAvailableListener { handler.post { drawFrame() } }

            val source = scene?.background?.sourceUri ?: return
            player = ExoPlayer.Builder(this@LayerLockWallpaperService).build().apply {
                setMediaItem(MediaItem.fromUri(source))
                repeatMode = if (scene?.background?.loop != false) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                // A wallpaper that makes noise is a bug, not a feature.
                volume = 0f
                setVideoSurface(Surface(texture))
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(size: VideoSize) {
                        // Qualified: inside `apply` on the player, the bare name would resolve to
                        // the player's own read-only videoSize.
                        this@SceneWallpaperEngine.videoSize = size
                    }
                })
                prepare()
                playWhenReady = isVisible
            }
        }

        private fun teardownVideo() {
            player?.release()
            player = null
            gl?.release()
            gl = null
            videoSize = VideoSize.UNKNOWN
        }

        // -- Drawing --------------------------------------------------------------------------

        private fun requestDraw() {
            if (!isVisible) return
            handler.removeCallbacks(drawRunnable)
            handler.post(drawRunnable)
        }

        private fun drawFrame() {
            if (!isVisible || surfaceWidth <= 0 || surfaceHeight <= 0) return
            // See [sceneLoaded]: a canvas draw here would cost a video scene its GL surface.
            if (!sceneLoaded) return
            val current = scene ?: placeholderScene()

            if (usesVideoBackground() && gl?.isReady == true) {
                drawWithGl(current)
            } else {
                drawWithCanvas(current)
            }

            scheduleNextFrame(current)
        }

        private fun drawWithGl(current: Scene) {
            val glRenderer = gl ?: return
            val key = overlayContentKey(current)
            if (key != overlayKey) {
                glRenderer.setOverlay(renderOverlay(current))
                overlayKey = key
            }
            val drawn = glRenderer.drawFrame(videoSize.width, videoSize.height)
            if (!drawn) {
                glUnavailable = true
                teardownVideo()
                drawWithCanvas(current)
            }
        }

        private fun drawWithCanvas(current: Scene) {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    renderer.draw(
                        canvas = canvas,
                        scene = current,
                        viewWidth = surfaceWidth.toFloat(),
                        viewHeight = surfaceHeight.toFloat(),
                        frame = frameFor(current),
                    )
                }
            } catch (e: IllegalArgumentException) {
                // The surface can go away between the visibility check and lockCanvas.
            } finally {
                if (canvas != null) {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
        }

        /** Renders every non-video layer into a bitmap for the GL compositor to blend over the video. */
        private fun renderOverlay(current: Scene): Bitmap {
            val bitmap = overlayBitmap ?: Bitmap.createBitmap(
                surfaceWidth,
                surfaceHeight,
                Bitmap.Config.ARGB_8888,
            ).also { overlayBitmap = it }

            bitmap.eraseColor(0)
            renderer.draw(
                canvas = Canvas(bitmap),
                scene = current,
                viewWidth = surfaceWidth.toFloat(),
                viewHeight = surfaceHeight.toFloat(),
                // The video *is* the background here, so the canvas must not paint over it.
                frame = frameFor(current).copy(skipBackground = true),
            )
            return bitmap
        }

        private fun frameFor(current: Scene) = SceneCanvasRenderer.Frame(
            timeMillis = System.currentTimeMillis(),
            assets = assets,
            widgets = widgets,
            skipBackground = false,
            editor = null,
        )

        /**
         * Identity of the overlay's *content*.
         *
         * Video plays at 30fps but a clock changes once a minute, so re-rendering and re-uploading a
         * full-screen bitmap every video frame would waste almost all of that work. This key changes
         * only when something visible does.
         */
        private fun overlayContentKey(current: Scene): String {
            val tick = if (current.layers.any { it is GifLayer }) {
                System.currentTimeMillis()
            } else {
                val pattern = current.layers.filterIsInstance<ClockLayer>()
                    .minByOrNull { if (it.pattern.contains('s')) 0 else 1 }
                    ?.pattern
                val resolution = if (pattern?.contains('s') == true) 1_000L else 60_000L
                System.currentTimeMillis() / resolution
            }
            return "${current.hashCode()}|${assets.generation.value}|$tick|${widgets.hashCode()}"
        }

        private fun scheduleNextFrame(current: Scene) {
            if (!isVisible) return
            handler.removeCallbacks(drawRunnable)

            // In GL mode the video's own frame callback drives repaints; a timer would double up.
            if (usesVideoBackground() && gl?.isReady == true) return

            val animated = !settings.staticMode &&
                current.layers.any { it is GifLayer }

            val delay = if (animated) {
                (1_000L / settings.maxFps.coerceIn(1, 60))
            } else {
                val pattern = current.layers.filterIsInstance<ClockLayer>()
                    .minByOrNull { if (it.pattern.contains('s')) 0 else 1 }
                    ?.pattern
                    ?: return // Nothing moves: leave the last frame on screen indefinitely.
                ClockFormatter.millisUntilNextChange(pattern, System.currentTimeMillis())
            }

            handler.postDelayed(drawRunnable, delay.coerceAtLeast(MIN_FRAME_DELAY_MS))
        }

        private fun releaseOverlayBitmap() {
            overlayBitmap = null
            overlayKey = null
        }

        /** Shown when no scene has been assigned yet, so the wallpaper is never just black. */
        private fun placeholderScene(): Scene = ScenePresets.blank("placeholder", "LayerLock")
    }

    companion object {

        private const val TAG = "LayerLockWallpaper"
        private const val MIN_FRAME_DELAY_MS = 16L

        /**
         * Intent that opens the system live-wallpaper picker preselected on this service.
         *
         * `ACTION_CHANGE_LIVE_WALLPAPER` shows the OS's own preview-and-confirm screen, so the user
         * is always the one who applies the wallpaper — the app never changes it behind their back.
         */
        fun changeLiveWallpaperIntent(context: Context): Intent =
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, LayerLockWallpaperService::class.java),
            )

        /**
         * Whether this app is the live wallpaper right now.
         *
         * Read from the system rather than cached in settings, deliberately: the user can change
         * their wallpaper from anywhere in the OS, and a stored flag would go stale and start lying
         * about it. `wallpaperInfo` is null whenever a static image wallpaper is set.
         */
        fun isActiveWallpaper(context: Context): Boolean =
            WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName

        /**
         * Removes this wallpaper, restoring the system default.
         *
         * There is no API to hand the wallpaper back to whatever was set before ours — the platform
         * does not keep that history — so "off" means the device default.
         */
        fun clearWallpaper(context: Context): Result<Unit> = runCatching {
            WallpaperManager.getInstance(context).clear()
        }
    }
}
