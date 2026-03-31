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
 * Accepts both PCM_16BIT (MediaCodec path — MP3, AAC, FLAC, M4A) and PCM_FLOAT (FFmpeg path — OGG,
 * Opus). For PCM_16BIT, samples are normalized to float, filtered, then written back as 16-bit.
 * This covers all common audio formats on Android.
 *
 * Persisted settings are restored at construction time via [EqualizerSettings] so the EQ is active
 * from the first audio frame, even if the EQ screen has never been opened.
 *
 * Thread safety: all @Volatile vars are written atomically by assigning new objects (arrays) or
 * primitive values. [onConfigure], [onFlush], [onReset] and [queueInput] run on the ExoPlayer audio
 * thread. [setBands] runs on the UI thread. Only [enabled], [gains], [coeffs] and [state] cross
 * thread boundaries — all are @Volatile.
 */
@Singleton
class EqualizerAudioProcessor @Inject constructor(equalizerSettings: EqualizerSettings) :
    BaseAudioProcessor() {

    // Restored from SharedPreferences so the EQ is ready before the screen is opened.
    @Volatile private var enabled = equalizerSettings.enabled
    @Volatile private var gains = equalizerSettings.getBands()

    // Set on the audio thread in onConfigure. @Volatile so setBands() (UI thread) can safely
    // read sampleRate to decide whether recomputeCoefficients() is necessary.
    @Volatile private var sampleRate = 0

    // Written only on the audio thread (onConfigure). @Volatile so queueInput, also on the
    // audio thread, always sees the latest value — important after format changes between songs.
    @Volatile private var encoding: Int = C.ENCODING_PCM_16BIT
    private var channelCount = 0

    /**
     * Biquad coefficients per band: [b0, b1, b2, a1, a2], pre-normalized by a0.
     *
     * @Volatile — recomputeCoefficients() assigns a brand-new array, publishing it atomically.
     */
    @Volatile
    private var coeffs: Array<FloatArray> = Array(BAND_COUNT) { floatArrayOf(1f, 0f, 0f, 0f, 0f) }

    /**
     * Delay lines: [band][channel][x(n-1), x(n-2), y(n-1), y(n-2)].
     *
     * @Volatile — resetDelayLines() assigns a brand-new array, publishing it atomically.
     */
    @Volatile
    private var state: Array<Array<FloatArray>> = Array(BAND_COUNT) { Array(1) { FloatArray(4) } }

    /** Called from the UI thread. Updates band gains and enabled flag. */
    fun setBands(newGains: FloatArray, isEnabled: Boolean) {
        gains = newGains.copyOf()
        enabled = isEnabled
        // sampleRate is @Volatile — safe to read from UI thread.
        if (sampleRate > 0) {
            recomputeCoefficients()
            resetDelayLines()
        }
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> {
                encoding = inputAudioFormat.encoding
                sampleRate = inputAudioFormat.sampleRate
                channelCount = inputAudioFormat.channelCount
                recomputeCoefficients()
                resetDelayLines()
                // Return the same format — EQ does not change sample rate, channel count
                // or encoding.
                inputAudioFormat
            }
            // Bypass any other encoding (PCM_24BIT, PCM_32BIT, etc.).
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
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

        // Fast path: EQ is off, or all bands are flat — copy bytes unchanged.
        if (!currentEnabled || currentGains.all { it == 0f }) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        // Snapshot volatile references once per buffer for a consistent view.
        val localCoeffs = coeffs
        val localState = state
        val currentEncoding = encoding

        // Bytes per sample: 4 for FLOAT, 2 for 16BIT.
        val bps = if (currentEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val bytesPerFrame = bps * channelCount

        while (inputBuffer.remaining() >= bytesPerFrame) {
            for (ch in 0 until channelCount) {

                // --- Read one sample and convert to float [-1, 1] ---
                var x: Float =
                    if (currentEncoding == C.ENCODING_PCM_FLOAT) {
                        // 4-byte little-endian IEEE 754 float.
                        val b0 = inputBuffer.get().toInt() and 0xFF
                        val b1 = inputBuffer.get().toInt() and 0xFF
                        val b2 = inputBuffer.get().toInt() and 0xFF
                        val b3 = inputBuffer.get().toInt() and 0xFF
                        java.lang.Float.intBitsToFloat(
                            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                        )
                    } else {
                        // 2-byte little-endian signed short → normalize to [-1, 1].
                        // Read both bytes as unsigned to avoid sign-extension artifacts.
                        val lo = inputBuffer.get().toInt() and 0xFF
                        val hi = inputBuffer.get().toInt() and 0xFF
                        val raw = (hi shl 8) or lo
                        // Convert unsigned 0..65535 to signed -32768..32767.
                        val signed = if (raw >= 32768) raw - 65536 else raw
                        signed.toFloat() / 32768f
                    }

                // --- Apply 10-band biquad filter chain (Direct Form I) ---
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

                // --- Write sample back in the original encoding ---
                if (currentEncoding == C.ENCODING_PCM_FLOAT) {
                    val bits = java.lang.Float.floatToRawIntBits(x.coerceIn(-1f, 1f))
                    outputBuffer.put((bits and 0xFF).toByte())
                    outputBuffer.put(((bits ushr 8) and 0xFF).toByte())
                    outputBuffer.put(((bits ushr 16) and 0xFF).toByte())
                    outputBuffer.put(((bits ushr 24) and 0xFF).toByte())
                } else {
                    // Scale back to -32768..32767 and clamp to prevent overflow on boost.
                    val s =
                        (x.coerceIn(-1f, 1f) * 32767f)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    // Write as little-endian signed short.
                    outputBuffer.put((s and 0xFF).toByte())
                    outputBuffer.put(((s ushr 8) and 0xFF).toByte())
                }
            }
        }

        outputBuffer.flip()
    }

    /** Computes biquad coefficients for all bands and publishes them atomically via @Volatile. */
    private fun recomputeCoefficients() {
        val fs = sampleRate.toFloat()
        val newCoeffs =
            Array(BAND_COUNT) { i -> peakingEqCoeffs(BAND_FREQUENCIES[i], BAND_Q, gains[i], fs) }
        coeffs = newCoeffs
    }

    /** Allocates fresh delay lines and publishes them atomically via @Volatile. */
    private fun resetDelayLines() {
        val ch = channelCount.coerceIn(1, MAX_CHANNELS)
        state = Array(BAND_COUNT) { Array(ch) { FloatArray(4) } }
    }

    /** Returns Direct Form I biquad coefficients [b0,b1,b2,a1,a2] / a0 for a peaking EQ. */
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

        val BAND_FREQUENCIES =
            floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val BAND_Q = 1.41f
    }
}
