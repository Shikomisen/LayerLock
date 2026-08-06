package com.shikomisen.layerlock.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.SizeF
import androidx.annotation.MainThread
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.shikomisen.layerlock.scene.BackgroundType
import com.shikomisen.layerlock.scene.CutoutLayer
import com.shikomisen.layerlock.scene.GifLayer
import com.shikomisen.layerlock.scene.ImageLayer
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.VideoLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only view of decoded media, as the renderer sees it.
 *
 * The renderer is synchronous — it has to be, since it runs inside `lockCanvas`/`unlockCanvasAndPost`
 * on the wallpaper surface — so decoding never happens during a draw. Anything not yet loaded simply
 * returns null and the renderer draws a placeholder for that layer.
 */
interface AssetSource {
    fun bitmap(uri: String): Bitmap?

    /** Animated GIF drawables, which advance themselves between draws. */
    fun drawable(uri: String): Drawable?

    fun intrinsicSize(uri: String): SizeF?

    companion object {
        val Empty: AssetSource = object : AssetSource {
            override fun bitmap(uri: String): Bitmap? = null
            override fun drawable(uri: String): Drawable? = null
            override fun intrinsicSize(uri: String): SizeF? = null
        }
    }
}

/**
 * Decodes and caches the media a scene references.
 *
 * Images are downsampled to the canvas size on decode: a 48-megapixel photo drawn into a 1080-wide
 * canvas costs the same on screen either way, but the full-resolution decode is what actually pushes
 * a wallpaper process over its memory limit.
 */
class SceneAssets(
    context: Context,
    private val scope: CoroutineScope,
) : AssetSource {

    private val appContext = context.applicationContext

    /**
     * Bumped on the main thread each time an asset finishes decoding.
     *
     * Exposed as Compose state so a draw that reads it is invalidated automatically when media
     * arrives — the editor gets a repaint without the renderer knowing Compose exists.
     */
    private val _generation = mutableIntStateOf(0)
    val generation: State<Int> get() = _generation

    /** Non-Compose hosts (the wallpaper engine) use this to request a redraw instead. */
    @Volatile
    var onAssetLoaded: (() -> Unit)? = null

    private val bitmaps = ConcurrentHashMap<String, Bitmap>()
    private val drawables = ConcurrentHashMap<String, Drawable>()
    private val sizes = ConcurrentHashMap<String, SizeF>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

    override fun bitmap(uri: String): Bitmap? = bitmaps[uri]?.takeUnless { it.isRecycled }

    override fun drawable(uri: String): Drawable? = drawables[uri]

    override fun intrinsicSize(uri: String): SizeF? = sizes[uri]

    /** Whether every media source the scene references has resolved one way or the other. */
    fun isSettled(scene: Scene): Boolean =
        sourcesOf(scene).all { (uri, _) -> isResolved(uri) }

    private fun isResolved(uri: String): Boolean =
        bitmaps.containsKey(uri) || drawables.containsKey(uri) || failed.contains(uri)

    /** Kicks off decoding for everything [scene] references. Safe to call on every scene change. */
    fun prepare(scene: Scene) {
        val maxWidth = scene.canvas.width
        val maxHeight = scene.canvas.height
        sourcesOf(scene).forEach { (uri, kind) -> load(uri, kind, maxWidth, maxHeight) }
    }

    private fun sourcesOf(scene: Scene): List<Pair<String, MediaKind>> = buildList {
        scene.background.sourceUri?.let { uri ->
            when (scene.background.type) {
                BackgroundType.IMAGE -> add(uri to MediaKind.IMAGE)
                BackgroundType.VIDEO -> add(uri to MediaKind.VIDEO)
                else -> Unit
            }
        }
        scene.layers.forEach { layer ->
            when (layer) {
                is ImageLayer -> add(layer.sourceUri to MediaKind.IMAGE)
                is GifLayer -> add(layer.sourceUri to MediaKind.GIF)
                is VideoLayer -> add(layer.sourceUri to MediaKind.VIDEO)
                is CutoutLayer -> add((layer.cutoutUri ?: layer.sourceUri) to MediaKind.IMAGE)
                else -> Unit
            }
        }
    }

    private fun load(uri: String, kind: MediaKind, maxWidth: Int, maxHeight: Int) {
        if (isResolved(uri)) return
        if (!inFlight.add(uri)) return

        scope.launch {
            val loaded = runCatching {
                when (kind) {
                    MediaKind.VIDEO -> loadVideoPoster(uri)
                    MediaKind.GIF -> loadAnimated(uri, maxWidth, maxHeight)
                    MediaKind.IMAGE -> loadStill(uri, maxWidth, maxHeight)
                }
            }.getOrDefault(false)

            inFlight.remove(uri)
            if (!loaded) {
                failed.add(uri)
            } else {
                withContext(Dispatchers.Main) {
                    _generation.intValue++
                    onAssetLoaded?.invoke()
                }
            }
        }
    }

    private suspend fun loadStill(uri: String, maxWidth: Int, maxHeight: Int): Boolean {
        val result = imageLoader.execute(request(uri, maxWidth, maxHeight))
        val bitmap = (result as? SuccessResult)?.drawable?.let { it as? BitmapDrawable }?.bitmap
            ?: return false
        bitmaps[uri] = bitmap
        sizes[uri] = SizeF(bitmap.width.toFloat(), bitmap.height.toFloat())
        return true
    }

    private suspend fun loadAnimated(uri: String, maxWidth: Int, maxHeight: Int): Boolean {
        val result = imageLoader.execute(request(uri, maxWidth, maxHeight))
        val drawable = (result as? SuccessResult)?.drawable ?: return false

        // A still GIF (single frame) decodes to a plain bitmap; treat it as an image.
        if (drawable is BitmapDrawable) {
            bitmaps[uri] = drawable.bitmap
            sizes[uri] = SizeF(drawable.bitmap.width.toFloat(), drawable.bitmap.height.toFloat())
            return true
        }

        withContext(Dispatchers.Main) {
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
            (drawable as? Animatable)?.start()
        }
        drawables[uri] = drawable
        sizes[uri] = SizeF(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        return true
    }

    /**
     * Video layers are drawn live by the surface that owns them (an ExoPlayer texture in the editor
     * and lock screen, a GL quad in the wallpaper engine). The poster frame decoded here is what the
     * shared canvas renderer falls back to — which is also exactly what a PNG export needs.
     */
    private suspend fun loadVideoPoster(uri: String): Boolean = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, Uri.parse(uri))
            val frame = retriever.getFrameAtTime(0) ?: return@withContext false
            bitmaps[uri] = frame
            sizes[uri] = SizeF(frame.width.toFloat(), frame.height.toFloat())
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun request(uri: String, maxWidth: Int, maxHeight: Int): ImageRequest =
        ImageRequest.Builder(appContext)
            .data(uri)
            // Hardware bitmaps cannot be read back, and both PNG export and the GL uploader need to.
            .allowHardware(false)
            .size(Size(maxWidth, maxHeight))
            .build()

    @MainThread
    fun release() {
        drawables.values.forEach { (it as? Animatable)?.stop() }
        drawables.clear()
        bitmaps.clear()
        sizes.clear()
        inFlight.clear()
        failed.clear()
    }

    private enum class MediaKind { IMAGE, GIF, VIDEO }
}
