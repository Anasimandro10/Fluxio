/*
 * Copyright (c) 2026 Fluxio Project
 * VolumeNormalizer.kt is part of Fluxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.playback.normalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * An [AudioProcessor] that measures the average loudness of the first few seconds of each track and
 * applies a single fixed gain for the entire song. This makes all songs play at the same perceived
 * volume without any dynamic changes within a track.
 *
 * Works independently of ReplayGain: no tags required.
 */
class VolumeNormalizer
@Inject
constructor(
    private val playbackSettings: PlaybackSettings,
    private val playbackManager: PlaybackStateManager,
) : BaseAudioProcessor(), PlaybackSettings.Listener, PlaybackStateManager.Listener {

    // Fixed gain applied to the entire current song. Only changes when the song changes.
    private var gain = 1f
        set(value) {
            field = value
            flush()
        }

    // Accumulator used only during the measurement phase at the start of each song.
    private var sumSquares = 0.0
    private var sampleCount = 0L

    // Once we have measured enough samples we lock the gain and stop accumulating.
    private var gainLocked = false

    private companion object {
        // ~3 seconds at 44100 Hz stereo = 44100 * 2 channels * 3s = 264600 samples
        const val MEASUREMENT_SAMPLES = 264600L
        const val MAX_GAIN = 6f
        const val MIN_GAIN = 0.15f
        const val LIMITER_CEILING = 32000
    }

    fun attach() {
        playbackSettings.registerListener(this)
        playbackManager.addListener(this)
    }

    fun release() {
        playbackSettings.unregisterListener(this)
        playbackManager.removeListener(this)
    }

    // Called when the song changes — reset so we measure the new song from scratch.
    override fun onIndexMoved(index: Int) {
        resetForNewSong()
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        if (change.type == QueueChange.Type.SONG) {
            resetForNewSong()
        }
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        resetForNewSong()
    }

    override fun onNormalizationSettingsChanged() {
        L.d("Normalization settings changed, resetting")
        resetForNewSong()
    }

    // --- AUDIO PROCESSOR ---

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            return inputAudioFormat
        }
        throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val buffer = replaceOutputBuffer(limit - pos)

        if (!playbackSettings.normalizationEnabled) {
            buffer.put(inputBuffer.slice())
            inputBuffer.position(limit)
            buffer.flip()
            return
        }

        for (i in pos until limit step 2) {
            val raw = inputBuffer.getLeShort(i)

            // Still in the measurement phase — accumulate samples to calculate the gain.
            if (!gainLocked) {
                sumSquares += raw.toDouble() * raw.toDouble()
                sampleCount++

                if (sampleCount >= MEASUREMENT_SAMPLES) {
                    lockGain()
                }
            }

            // Apply the current fixed gain and soft-limit to prevent clipping.
            val amplified = softLimit((raw * gain).toInt())
            buffer.putLeShort(amplified.toShort())
        }

        inputBuffer.position(limit)
        buffer.flip()
    }

    /**
     * Called after measuring enough samples. Calculates the RMS of what we heard and sets a fixed
     * gain that will stay constant for the rest of this song.
     */
    private fun lockGain() {
        if (sampleCount == 0L) return

        val rms = sqrt(sumSquares / sampleCount).toFloat()
        sumSquares = 0.0
        sampleCount = 0L
        gainLocked = true

        if (rms < 1f) {
            // Silence or near-silence at the start — do not amplify.
            gain = 1f
            L.d("VolumeNormalizer: near-silence detected, gain=1.0")
            return
        }

        val targetRms =
            when (playbackSettings.normalizationTarget) {
                NormalizationTarget.LUFS_14 -> 2600f
                NormalizationTarget.LUFS_16 -> 1800f
                NormalizationTarget.LUFS_18 -> 1200f
            }

        gain = (targetRms / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        L.d("VolumeNormalizer: rms=$rms targetRms=$targetRms gain=$gain (locked)")
    }

    private fun resetForNewSong() {
        sumSquares = 0.0
        sampleCount = 0L
        gainLocked = false
        gain = 1f
    }

    /** Soft limiter: compresses samples above the ceiling instead of hard-clipping. */
    private fun softLimit(sample: Int): Int {
        if (sample in -LIMITER_CEILING..LIMITER_CEILING) return sample
        val sign = if (sample > 0) 1 else -1
        val over = abs(sample) - LIMITER_CEILING
        val compressed = LIMITER_CEILING + over / 4
        return (sign * compressed).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    private fun ByteBuffer.getLeShort(at: Int) =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }
}
