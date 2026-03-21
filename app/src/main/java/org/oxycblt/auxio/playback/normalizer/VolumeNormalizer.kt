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
import timber.log.Timber as L

/**
 * An [AudioProcessor] that analyzes the audio signal in real time and applies a gain adjustment to
 * bring each track closer to a user-defined target loudness level. A transparent soft limiter is
 * always active to prevent clipping when positive gain is applied.
 *
 * This processor works independently of ReplayGain: it analyzes the raw PCM signal rather than
 * reading tags. It must be attached to a [PlaybackSettings] instance via [attach] to receive
 * settings change notifications.
 */
class VolumeNormalizer @Inject constructor(private val playbackSettings: PlaybackSettings) :
    BaseAudioProcessor(), PlaybackSettings.Listener {

    // Changing needsReset to true triggers resetAnalysis() and flush() on the audio stream.
    // Same pattern used by ReplayGainAudioProcessor's 'volume' setter.
    private var needsReset = false
        set(value) {
            field = value
            if (value) {
                resetAnalysis()
                flush()
            }
        }

    private var sumSquares = 0.0
    private var sampleCount = 0L
    private var currentGain = 1f
    private var targetGain = 1f

    private companion object {
        const val ANALYSIS_WINDOW_SAMPLES = 17640
        const val MAX_GAIN = 8f
        const val MIN_GAIN = 0.1f
        const val SMOOTHING = 0.05f
        const val LIMITER_CEILING = 32000
    }

    fun attach() {
        playbackSettings.registerListener(this)
    }

    fun release() {
        playbackSettings.unregisterListener(this)
    }

    override fun onNormalizationSettingsChanged() {
        L.d("Normalization settings changed, resetting processor")
        needsReset = true
    }

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

            sumSquares += raw.toDouble() * raw.toDouble()
            sampleCount++

            if (sampleCount >= ANALYSIS_WINDOW_SAMPLES) {
                recalculateTargetGain()
            }

            currentGain += (targetGain - currentGain) * SMOOTHING
            val amplified = softLimit((raw * currentGain).toInt())
            buffer.putLeShort(amplified.toShort())
        }

        inputBuffer.position(limit)
        buffer.flip()
    }

    private fun recalculateTargetGain() {
        if (sampleCount == 0L) return
        val rms = sqrt(sumSquares / sampleCount).toFloat()
        resetAnalysis()
        if (rms < 1f) {
            targetGain = 1f
            return
        }
        val targetRms =
            when (playbackSettings.normalizationTarget) {
                NormalizationTarget.LUFS_14 -> 2600f
                NormalizationTarget.LUFS_16 -> 1800f
                NormalizationTarget.LUFS_18 -> 1200f
            }
        targetGain = (targetRms / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        L.d("VolumeNormalizer: rms=$rms targetRms=$targetRms gain=$targetGain")
    }

    private fun softLimit(sample: Int): Int {
        if (sample in -LIMITER_CEILING..LIMITER_CEILING) return sample
        val sign = if (sample > 0) 1 else -1
        val over = abs(sample) - LIMITER_CEILING
        val compressed = LIMITER_CEILING + over / 4
        return (sign * compressed).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    private fun resetAnalysis() {
        sumSquares = 0.0
        sampleCount = 0L
    }

    private fun ByteBuffer.getLeShort(at: Int) =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }
}
