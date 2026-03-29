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
 * Only processes PCM_FLOAT audio; other formats are bypassed via
 * [AudioProcessor.AudioFormat.NOT_SET].
 */
@Singleton
class EqualizerAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    private var enabled = false
    private var gains = FloatArray(BAND_COUNT)
    private var sampleRate = 0
    private var channelCount = 0

    /** Biquad coefficients per band: [b0, b1, b2, a1, a2], pre-normalized by a0. */
    private val coeffs = Array(BAND_COUNT) { floatArrayOf(1f, 0f, 0f, 0f, 0f) }

    /** Delay lines: [band][channel][x(n-1), x(n-2), y(n-1), y(n-2)]. */
    private var state = Array(BAND_COUNT) { Array(1) { FloatArray(4) } }

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
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
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
        val inputBytes = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(inputBytes)

        if (!enabled || gains.all { it == 0f }) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        while (inputBuffer.remaining() >= BYTES_PER_SAMPLE * channelCount) {
            for (ch in 0 until channelCount) {
                var x = inputBuffer.float
                for (band in 0 until BAND_COUNT) {
                    val c = coeffs[band]
                    val s = state[band][ch.coerceAtMost(state[band].size - 1)]
                    val y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
                    s[1] = s[0]
                    s[0] = x
                    s[3] = s[2]
                    s[2] = y
                    x = y
                }
                outputBuffer.putFloat(x.coerceIn(-1f, 1f))
            }
        }

        outputBuffer.flip()
    }

    private fun recomputeCoefficients() {
        for (i in 0 until BAND_COUNT) {
            coeffs[i] = peakingEqCoeffs(BAND_FREQUENCIES[i], BAND_Q, gains[i], sampleRate.toFloat())
        }
    }

    private fun resetDelayLines() {
        val ch = channelCount.coerceIn(1, MAX_CHANNELS)
        state = Array(BAND_COUNT) { Array(ch) { FloatArray(4) } }
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
        const val BYTES_PER_SAMPLE = 4

        val BAND_FREQUENCIES =
            floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val BAND_Q = 1.41f
    }
}
