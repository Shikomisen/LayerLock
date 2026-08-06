package com.shikomisen.layerlock.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Copies picked media into app storage.
 *
 * This exists because of a mismatch that would otherwise surface as "my wallpaper went blank after I
 * rebooted". The Android Photo Picker is the right way to get at photos — it needs no `READ_MEDIA_*`
 * permission, which is both better for the user and a much easier Play review (§5) — but the URIs it
 * returns carry a *temporary* grant. Unlike `ACTION_OPEN_DOCUMENT`, they cannot be persisted with
 * `takePersistableUriPermission`. A live wallpaper and a lock screen both have to render long after
 * the picking Activity is gone, so the bytes are copied once, at pick time, and the scene refers to
 * the app's own copy from then on.
 *
 * The trade-off is disk: a scene "owns" its media. [deleteUnreferenced] is what keeps that from
 * growing without bound.
 */
class MediaImporter(private val context: Context) {

    suspend fun import(source: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.filesDir, MEDIA_DIRECTORY).apply { mkdirs() }
            val extension = extensionOf(source)
            val destination = File(directory, "${UUID.randomUUID()}$extension")

            context.contentResolver.openInputStream(source)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not read the selected media")

            Uri.fromFile(destination).toString()
        }
    }

    private fun extensionOf(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri)
        val fromMime = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return when {
            fromMime != null -> ".$fromMime"
            mimeType?.startsWith("video/") == true -> ".mp4"
            else -> ".png"
        }
    }

    /** True for URIs this importer owns, so callers know what is safe to delete. */
    fun isManaged(uri: String): Boolean =
        uri.startsWith("file://") && uri.contains("/$MEDIA_DIRECTORY/")

    /**
     * Deletes imported media no scene refers to any more.
     *
     * Called after a scene is deleted or its background replaced. Anything still referenced by any
     * scene in [referencedUris] is kept, so two scenes sharing a photo behave correctly.
     */
    suspend fun deleteUnreferenced(referencedUris: Set<String>) = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, MEDIA_DIRECTORY)
        if (!directory.isDirectory) return@withContext

        val referenced = referencedUris.mapNotNull { runCatching { Uri.parse(it).path }.getOrNull() }
            .toSet()

        directory.listFiles()?.forEach { file ->
            if (file.absolutePath !in referenced) {
                runCatching { file.delete() }
            }
        }
    }

    private companion object {
        const val MEDIA_DIRECTORY = "media"
    }
}
