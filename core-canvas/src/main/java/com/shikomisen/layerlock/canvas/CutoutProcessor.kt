package com.shikomisen.layerlock.canvas

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The depth/cutout tool from §4 — on-device subject segmentation, so a person can be layered in
 * front of the clock.
 *
 * Two things matter about how this is wired. First, it runs entirely on-device: no photo ever leaves
 * the phone, which keeps the Data Safety declaration honest and avoids a network round trip per edit.
 * Second, the result is cached as a PNG with a real alpha channel and referenced by URI, so the
 * cutout stays an ordinary movable, resizable, restackable layer rather than being baked into a flat
 * image — which is the entire point of the feature.
 */
class CutoutProcessor(private val context: Context) {

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                // Single-image mode is the accurate (non-streaming) model, and the mask comes back
                // already scaled to the input bitmap — which is what makes the pixel loop below valid.
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build(),
        )
    }

    sealed interface Result {
        data class Success(val cutoutUri: String) : Result
        data class NoSubjectFound(val message: String) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Segments the subject out of [sourceUri] and caches the masked result.
     *
     * @param minimumConfidence pixels below this are cut away entirely.
     * @param featherRange width of the soft edge above the threshold, which stops the cutout looking
     *   like it was trimmed with scissors.
     */
    suspend fun createCutout(
        sourceUri: String,
        assets: AssetSource,
        minimumConfidence: Float = 0.45f,
        featherRange: Float = 0.25f,
    ): Result {
        val source = assets.bitmap(sourceUri)
            ?: return Result.Failed("That image is still loading — try again in a moment")

        return runCatching {
            val mask = segment(source)
            val cutout = applyMask(source, mask, minimumConfidence, featherRange)
                ?: return Result.NoSubjectFound("No person or subject was found in this photo")
            val uri = persist(cutout)
            Result.Success(uri.toString())
        }.getOrElse { error ->
            Result.Failed(error.message ?: "Cutout failed")
        }
    }

    private suspend fun segment(bitmap: Bitmap): SegmentationMask =
        suspendCancellableCoroutine { continuation ->
            segmenter.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { mask -> continuation.resume(mask) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

    /**
     * Multiplies the source by the confidence mask.
     *
     * Returns null when the subject covers so little of the frame that the result would be empty —
     * better to tell the user nothing was found than to hand them a blank layer.
     */
    private suspend fun applyMask(
        source: Bitmap,
        mask: SegmentationMask,
        minimumConfidence: Float,
        featherRange: Float,
    ): Bitmap? = withContext(Dispatchers.Default) {
        val width = mask.width
        val height = mask.height
        if (width <= 0 || height <= 0) return@withContext null

        val scaled = if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val buffer = mask.buffer
        buffer.rewind()

        var keptPixels = 0
        val feather = featherRange.coerceAtLeast(0.001f)

        for (index in pixels.indices) {
            val confidence = buffer.float
            val alphaFactor = ((confidence - minimumConfidence) / feather).coerceIn(0f, 1f)
            if (alphaFactor > 0f) keptPixels++

            val pixel = pixels[index]
            val alpha = ((pixel ushr 24) and 0xFF) * alphaFactor
            pixels[index] = (alpha.toInt().coerceIn(0, 255) shl 24) or (pixel and 0x00FFFFFF)
        }
        buffer.rewind()

        val coverage = keptPixels.toFloat() / pixels.size
        if (coverage < MINIMUM_SUBJECT_COVERAGE) return@withContext null

        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private suspend fun persist(bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, CUTOUT_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "cutout-${System.currentTimeMillis()}.png")
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        file.toUri()
    }

    fun close() {
        runCatching { segmenter.close() }
    }

    private companion object {
        const val CUTOUT_DIRECTORY = "cutouts"

        /** Below this fraction of the frame, treat the segmentation as a miss. */
        const val MINIMUM_SUBJECT_COVERAGE = 0.005f
    }
}
