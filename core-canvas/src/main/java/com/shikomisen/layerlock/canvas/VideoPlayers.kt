package com.shikomisen.layerlock.canvas

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Builds the ExoPlayer instances this app uses for scene video.
 *
 * Every player goes through here so the buffering policy is stated once. ExoPlayer's defaults target
 * roughly fifty seconds of buffered media, which is right for a video app playing one thing at a
 * time and badly wrong here: LayerLock can have several players alive in a single process — the home
 * wallpaper surface, the lock wallpaper surface, the lock-screen Activity and the editor preview —
 * and the footage is usually a short loop. Multiplying the default across those was enough on its own
 * to exhaust the heap, which surfaced as OutOfMemoryError in whatever unrelated code allocated next.
 *
 * The numbers below buffer a couple of seconds, which a looping wallpaper never outruns.
 */
object VideoPlayers {

    @OptIn(UnstableApi::class)
    fun build(context: Context): ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .build()

    private const val MIN_BUFFER_MS = 2_000
    private const val MAX_BUFFER_MS = 5_000
    private const val BUFFER_FOR_PLAYBACK_MS = 500
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_000
}
