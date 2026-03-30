/*
 * Copyright (c) 2026 Fluxio Project
 * EqualizerAudioProcessor.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A 10-band parametric equalizer implemented as an [AudioProcessor] using biquad peaking EQ
 * filters. All band gains start at 0 dB (flat response) and the EQ starts disabled.
 *
 * Processes PCM_16BIT audio. Other formats are bypassed via [AudioProcessor.AudioFormat.NOT_SET].
 *
 * Thread safety: [coeffs] and [state] are @Volatile vars. [recomputeCoefficients] and
 * [resetDelayLines] always assign a brand-new array object, so the volatile write publishes the new
 * array atomically to the audio thread.
 */
@Singleton
class EqualizerAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    @Volatile private var enabled = false
    @Volatile private var gains = FloatArray(BAND_COUNT)
    private var sampleRate = 0
    private var channelCount = 0

    /**
     * Biquad coefficients per band: [b0, b1, b2, a1, a2], pre-normalized by a0.
     *
     * @Volatile var so the audio thread always sees the latest array after recomputeCoefficients().
     */
    @Volatile
    private var coeffs: Array<FloatArray> = Array(BAND_COUNT) { floatArrayOf(1f, 0f, 0f, 0f, 0f) }

    /**
     * Delay lines: [band][channel][x(n-1), x(n-2), y(n-1), y(n-2)].
     *
     * @Volatile var so the audio thread always sees the latest array after resetDelayLines().
     */
    @Volatile
    private var state: Array<Array<FloatArray>> = Array(BAND_COUNT) { Array(1) { FloatArray(4) } }

    /** Updates the 10 band gains (in dB) and whether the EQ is active. */
    fun setBands(newGains: FloatArray, isEnabled: Boolean) {
        gains = newGains.copyOf()
        enabled = isEnabled
        if (sampleRate > 0) {
            recomputeCoefficients()
            resetDelayLines()
        }
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        recomputeCoefficients()
        resetDelayLines()
        return inputAudioFormat
    }

    override fun onFlush() {
        resetDelayLines()
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        resetDelayLines()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(remaining)

        val currentEnabled = enabled
        val currentGains = gains

        if (!currentEnabled || currentGains.all { it == 0f }) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        // Capture volatile references once — safe publication guarantees we see the latest
        // coefficients and state written by setBands() / recomputeCoefficients().
        val localCoeffs = coeffs
        val localState = state

        // Each PCM_16BIT sample is 2 bytes (little-endian short).
        while (inputBuffer.remaining() >= BYTES_PER_SAMPLE * channelCount) {
            for (ch in 0 until channelCount) {
                // Read little-endian short and normalize to [-1f, 1f]
                var x = inputBuffer.getLeShort() / 32768f
                // Apply 10-band biquad filter chain
                for (band in 0 until BAND_COUNT) {
                    val c = localCoeffs[band]
                    val s = localState[band][ch.coerceAtMost(localState[band].size - 1)]
                    val y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
                    s[1] = s[0]
                    s[0] = x
                    s[3] = s[2]
                    s[2] = y
                    x = y
                }
                // Clamp and convert back to PCM_16BIT little-endian short
                val sample =
                    (x.coerceIn(-1f, 1f) * 32768f)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                outputBuffer.putLeShort(sample)
            }
        }

        outputBuffer.flip()
    }

    /** Reads a little-endian [Short] from the [ByteBuffer] at the current position. */
    private fun ByteBuffer.getLeShort(): Short {
        val lo = get().toInt().and(0xFF)
        val hi = get().toInt().and(0xFF)
        return hi.shl(8).or(lo).toShort()
    }

    /** Writes a little-endian [Short] at the current position of the [ByteBuffer]. */
    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }

    /**
     * Creates a brand-new [Array] of biquad coefficients and assigns it atomically via the
     *
     * @Volatile write, so the audio thread always sees a consistent snapshot.
     */
    private fun recomputeCoefficients() {
        val newCoeffs =
            Array(BAND_COUNT) { i ->
                peakingEqCoeffs(BAND_FREQUENCIES[i], BAND_Q, gains[i], sampleRate.toFloat())
            }
        coeffs = newCoeffs // volatile write — publishes the entire new array atomically
    }

    /** Creates a brand-new delay-line array and assigns it atomically via the @Volatile write. */
    private fun resetDelayLines() {
        val ch = channelCount.coerceIn(1, MAX_CHANNELS)
        state = Array(BAND_COUNT) { Array(ch) { FloatArray(4) } } // volatile write
    }

    private fun peakingEqCoeffs(freq: Float, q: Float, gainDb: Float, fs: Float): FloatArray {
        if (gainDb == 0f) return floatArrayOf(1f, 0f, 0f, 0f, 0f)
        val a = 10f.pow(gainDb / 40f)
        val w0 = (2.0 * PI * freq / fs).toFloat()
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2f * q)
        val a0inv = 1f / (1f + alpha / a)
        return floatArrayOf(
            (1f + alpha * a) * a0inv,
            (-2f * cosW0) * a0inv,
            (1f - alpha * a) * a0inv,
            (-2f * cosW0) * a0inv,
            (1f - alpha / a) * a0inv,
        )
    }

    private companion object {
        const val BAND_COUNT = 10
        const val MAX_CHANNELS = 8
        const val BYTES_PER_SAMPLE = 2

        val BAND_FREQUENCIES =
            floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val BAND_Q = 1.41f
    }
}
