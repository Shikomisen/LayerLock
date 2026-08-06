package com.shikomisen.layerlock.canvas

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.shikomisen.layerlock.scene.Scene
import com.shikomisen.layerlock.scene.ScreenTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Flattens a scene to a bitmap, and gets that bitmap onto the device.
 *
 * This is the shippable-on-its-own path from §8 Phase 1: even with no live wallpaper and no lock
 * Activity, "compose a scene, export a PNG, set it as your wallpaper" is a complete product.
 */
class SceneExporter(private val context: Context) {

    /**
     * Renders [scene] at its authored resolution.
     *
     * Video layers export their poster frame, which is the only sensible still representation of a
     * moving layer — and the reason [SceneAssets] decodes one for every video source.
     */
    suspend fun render(
        scene: Scene,
        assets: AssetSource,
        widgets: WidgetSnapshot = WidgetSnapshot(),
        watermark: Boolean = false,
        timeMillis: Long = System.currentTimeMillis(),
    ): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(
            scene.canvas.width,
            scene.canvas.height,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        SceneCanvasRenderer().draw(
            canvas = canvas,
            scene = scene,
            viewWidth = scene.canvas.width.toFloat(),
            viewHeight = scene.canvas.height.toFloat(),
            frame = SceneCanvasRenderer.Frame(
                timeMillis = timeMillis,
                assets = assets,
                widgets = widgets,
                watermark = watermark,
                // No editor chrome, and nothing is drawn externally — this is the whole scene.
                editor = null,
            ),
        )
        bitmap
    }

    /** Writes a PNG into the app's cache and returns a shareable content URI. */
    suspend fun exportToCache(scene: Scene, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "${scene.name.toFileName()}-${System.currentTimeMillis()}.png")
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Saves a PNG to the user's Pictures library.
     *
     * `MediaStore` with `RELATIVE_PATH` needs no storage permission on API 29+; on older releases the
     * insert still works through the legacy media columns, so no `WRITE_EXTERNAL_STORAGE` request is
     * needed at any API level the app supports.
     */
    suspend fun saveToGallery(scene: Scene, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${scene.name.toFileName()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LayerLock")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null

        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }.onFailure {
            resolver.delete(uri, null, null)
            return@withContext null
        }

        uri
    }

    /**
     * Sets the rendered scene as a static wallpaper.
     *
     * Since Android 12 home and lock screens take independent wallpapers, so [target] maps onto the
     * `FLAG_SYSTEM` / `FLAG_LOCK` flags rather than setting both indiscriminately.
     */
    suspend fun applyAsStaticWallpaper(
        bitmap: Bitmap,
        target: ScreenTarget,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = WallpaperManager.getInstance(context)
            val flags = when (target) {
                ScreenTarget.LOCK -> WallpaperManager.FLAG_LOCK
                ScreenTarget.HOME -> WallpaperManager.FLAG_SYSTEM
                ScreenTarget.BOTH -> WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM
            }
            manager.setBitmap(bitmap, null, true, flags)
            Unit
        }
    }

    /**
     * Hands the image to the system's own "set wallpaper" chooser.
     *
     * Worth offering alongside the direct path above: some OEM skins apply their own cropping and
     * effects in this flow, and a user who expects that dialog gets confused when the wallpaper just
     * silently changes.
     */
    fun wallpaperChooserIntent(imageUri: Uri): Intent =
        WallpaperManager.getInstance(context)
            .getCropAndSetWallpaperIntent(imageUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(imageUri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, imageUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun String.toFileName(): String =
        ifBlank { "layerlock-scene" }.replace(Regex("[^A-Za-z0-9-_]"), "-").take(48)

    private companion object {
        const val EXPORT_DIRECTORY = "exports"
    }
}
