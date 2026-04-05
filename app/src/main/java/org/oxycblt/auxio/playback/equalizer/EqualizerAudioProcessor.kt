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
 * filters.
 *
 * Only handles [C.ENCODING_PCM_16BIT] — the only encoding ever delivered to audio processors in
 * this ExoPlayer build (same contract as [ReplayGainAudioProcessor]). Any other encoding returns
 * [AudioProcessor.AudioFormat.NOT_SET] so the processor is silently bypassed without errors.
 *
 * Thread safety: [enabled], [gains], [coeffs], [sampleRate], [channelCount] and [state] are
 * [@Volatile]. [setBands] runs on the UI thread; [onConfigure], [onFlush], [onReset] and
 * [queueInput] run on the ExoPlayer audio thread. Assignments of new array objects are atomic
 * (JVM guarantee).
 */
@Singleton
class EqualizerAudioProcessor @Inject constructor(equalizerSettings: EqualizerSettings) :
    BaseAudioProcessor() {

    // Restored from SharedPreferences at construction — EQ is ready from the first audio frame.
    @Volatile private var enabled = equalizerSettings.enabled
    @Volatile private var gains = equalizerSettings.getBands()

    // Set on the audio thread in onConfigure. Both @Volatile so setBands() (UI thread) reads
    // the latest values written by the audio thread and resetDelayLines() creates the correct
    // number of per-channel delay lines.
    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 0

    /**
     * Biquad coefficients per band: [b0, b1, b2, a1, a2], pre-normalized by a0.
     *
     * Written by assigning a new array (atomic publish via @Volatile).
     */
    @Volatile
    private var coeffs: Array<FloatArray> = Array(BAND_COUNT) { floatArrayOf(1f, 0f, 0f, 0f, 0f) }

    /**
     * Per-band, per-channel delay lines: [band][channel][x(n-1), x(n-2), y(n-1), y(n-2)].
     *
     * Written by assigning a new array (atomic publish via @Volatile).
     */
    @Volatile
    private var state: Array<Array<FloatArray>> = Array(BAND_COUNT) { Array(1) { FloatArray(4) } }

    /** Called from the UI thread to update band gains and the enabled flag. */
    fun setBands(newGains: FloatArray, isEnabled: Boolean) {
        gains = newGains.copyOf()
        enabled = isEnabled
        // sampleRate and channelCount are @Volatile — safe to read from UI thread.
        if (sampleRate > 0) {
            recomputeCoefficients()
            resetDelayLines()
        }
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        // Only handle PCM_16BIT — same contract as ReplayGainAudioProcessor.
        // Return NOT_SET for everything else (PCM_FLOAT, PCM_24BIT, etc.) so the EQ is
        // silently bypassed. This avoids any UnhandledAudioFormatException in the chain.
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
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val outputBuffer = replaceOutputBuffer(limit - pos)

        val currentEnabled = enabled
        val currentGains = gains

        // Fast path: EQ is disabled or all bands are flat — copy bytes without processing.
        // Uses slice() so inputBuffer's position is not advanced here; position(limit) below
        // marks the buffer as fully consumed (same pattern as ReplayGainAudioProcessor).
        if (!currentEnabled || currentGains.all { it == 0f }) {
            outputBuffer.put(inputBuffer.slice())
        } else {
            // Snapshot volatile references once for a consistent view during this buffer.
            val localCoeffs = coeffs
            val localState = state
            val localChannelCount = channelCount

            // PCM_16BIT interleaved: [L, R, L, R, …] — 2 bytes per sample.
            val bytesPerFrame = 2 * localChannelCount
            var i = pos

            while (i <= limit - bytesPerFrame) {
                for (ch in 0 until localChannelCount) {
                    // Deserialize one little-endian signed short and normalize to [-1, 1].
                    var x = inputBuffer.getLeShort(i).toFloat() / 32768f
                    i += 2

                    // Apply the 10-band biquad filter chain (Direct Form I).
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

                    // Clamp, scale back to short range, and serialize as little-endian short.
                    val out =
                        (x.coerceIn(-1f, 1f) * 32767f)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    outputBuffer.putLeShort(out)
                }
            }
        }

        // Mark inputBuffer as fully consumed (required by ExoPlayer's AudioProcessorChain).
        inputBuffer.position(limit)
        outputBuffer.flip()
    }

    /** Recomputes biquad coefficients for all bands and publishes them atomically. */
    private fun recomputeCoefficients() {
        val fs = sampleRate.toFloat()
        val newCoeffs =
            Array(BAND_COUNT) { i -> peakingEqCoeffs(BAND_FREQUENCIES[i], BAND_Q, gains[i], fs) }
        coeffs = newCoeffs
    }

    /** Allocates fresh zero-initialized delay lines and publishes them atomically. */
    private fun resetDelayLines() {
        val ch = channelCount.coerceIn(1, MAX_CHANNELS)
        state = Array(BAND_COUNT) { Array(ch) { FloatArray(4) } }
    }

    /**
     * Returns Direct Form I biquad coefficients [b0, b1, b2, a1, a2] / a0 for a peaking EQ filter
     * at [freq] Hz with quality factor [q] and gain [gainDb] dB, for sample rate [fs] Hz.
     */
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

    /** Reads a little-endian [Short] at absolute index [at] without advancing position. */
    private fun ByteBuffer.getLeShort(at: Int) =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    /** Writes a little-endian [Short] at the current position, advancing it by 2. */
    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }

    private companion object {
        const val BAND_COUNT = 10
        const val MAX_CHANNELS = 8

        val BAND_FREQUENCIES =
            floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val BAND_Q = 1.41f
    }
}
