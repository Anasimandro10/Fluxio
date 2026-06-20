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
 * filters, processing fully in 32-bit float arithmetic internally.
 *
 * ## Supported encodings
 * - [C.ENCODING_PCM_16BIT] — signed 16-bit LE (Ffmpeg pipeline: OGG, some MP3/FLAC)
 * - [C.ENCODING_PCM_FLOAT] — IEEE 754 32-bit LE (MediaCodec pipeline: MP3, AAC, M4A)
 * - [C.ENCODING_PCM_24BIT] — signed 24-bit LE (hi-res FLAC, WAV 24-bit)
 * - [C.ENCODING_PCM_32BIT] — signed 32-bit LE (WAV 32-bit integer)
 *
 * All encodings are processed internally as 32-bit float. Output encoding always equals input
 * encoding — no downstream format change. Any other encoding returns NOT_SET (bypass).
 *
 * ## Thread safety
 *
 * [enabled], [gains], [coeffs], [encoding], [sampleRate], [channelCount] and [state] are all
 * [@Volatile]. [setBands] runs on the UI thread; [onConfigure], [onFlush], [onReset] and
 * [queueInput] run on the ExoPlayer audio thread. JVM guarantees atomic publication of new array
 * references assigned to @Volatile fields.
 */
@Singleton
class EqualizerAudioProcessor @Inject constructor(equalizerSettings: EqualizerSettings) :
    BaseAudioProcessor() {

    @Volatile private var enabled = equalizerSettings.enabled
    @Volatile private var gains = equalizerSettings.getBands()

    /** Whether the equalizer is currently active. Readable from any thread. */
    val isEnabled: Boolean get() = enabled

    /**
     * Called on the main thread whenever the EQ transitions between enabled and disabled.
     * Wired by [ExoPlaybackStateHolder] to trigger Audio Offload re-evaluation.
     */
    var onActiveStateChanged: (() -> Unit)? = null

    @Volatile private var encoding = C.ENCODING_INVALID
    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 0

    @Volatile
    private var coeffs: Array<FloatArray> = Array(BAND_COUNT) { floatArrayOf(1f, 0f, 0f, 0f, 0f) }

    @Volatile
    private var state: Array<Array<FloatArray>> = Array(BAND_COUNT) { Array(1) { FloatArray(4) } }

    // -------------------------------------------------------------------------
    // Public API — UI / ViewModel thread
    // -------------------------------------------------------------------------

    fun setBands(newGains: FloatArray, isEnabled: Boolean) {
        val wasEnabled = enabled
        gains = newGains.copyOf()
        enabled = isEnabled
        if (sampleRate > 0) {
            recomputeCoefficients()
            resetDelayLines()
        }
        if (wasEnabled != isEnabled) {
            onActiveStateChanged?.invoke()
        }
    }

    // -------------------------------------------------------------------------
    // BaseAudioProcessor — ExoPlayer audio thread
    // -------------------------------------------------------------------------

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT -> {
                encoding = inputAudioFormat.encoding
                sampleRate = inputAudioFormat.sampleRate
                channelCount = inputAudioFormat.channelCount
                recomputeCoefficients()
                resetDelayLines()
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        resetDelayLines()
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
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

        // Fast path: EQ off or all bands flat — copy raw bytes unchanged.
        if (!currentEnabled || currentGains.all { it == 0f }) {
            outputBuffer.put(inputBuffer.slice())
            inputBuffer.position(limit)
            outputBuffer.flip()
            return
        }

        val localCoeffs = coeffs
        val localState = state
        val localChannelCount = channelCount
        val localEncoding = encoding
        val bytesPerSample = bytesPerSample(localEncoding)
        val bytesPerFrame = bytesPerSample * localChannelCount

        var i = pos
        while (i <= limit - bytesPerFrame) {
            for (ch in 0 until localChannelCount) {
                // 1. Deserialize one sample to float in [-1.0, 1.0].
                var x = readSampleAsFloat(inputBuffer, i, localEncoding)
                i += bytesPerSample

                // 2. Apply 10-band biquad chain (Direct Form I).
                val chIdx = ch.coerceAtMost(localState[0].size - 1)
                for (band in 0 until BAND_COUNT) {
                    val c = localCoeffs[band]
                    val s = localState[band][chIdx]
                    val y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
                    s[1] = s[0]
                    s[0] = x
                    s[3] = s[2]
                    s[2] = y
                    x = y
                }

                // 3. Clamp and serialize back in the original encoding.
                writeSampleFromFloat(outputBuffer, x.coerceIn(-1f, 1f), localEncoding)
            }
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }

    // -------------------------------------------------------------------------
    // DSP
    // -------------------------------------------------------------------------

    private fun recomputeCoefficients() {
        val fs = sampleRate.toFloat()
        coeffs =
            Array(BAND_COUNT) { i -> peakingEqCoeffs(BAND_FREQUENCIES[i], BAND_Q, gains[i], fs) }
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

    // -------------------------------------------------------------------------
    // Encoding helpers
    // -------------------------------------------------------------------------

    private fun bytesPerSample(enc: Int): Int =
        when (enc) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            else -> 2
        }

    private fun readSampleAsFloat(buf: ByteBuffer, at: Int, enc: Int): Float =
        when (enc) {
            C.ENCODING_PCM_16BIT -> {
                val v = (buf.get(at).toInt() and 0xFF) or (buf.get(at + 1).toInt() shl 8)
                v.toShort().toFloat() / 32768f
            }
            C.ENCODING_PCM_FLOAT -> {
                val bits =
                    (buf.get(at).toInt() and 0xFF) or
                        ((buf.get(at + 1).toInt() and 0xFF) shl 8) or
                        ((buf.get(at + 2).toInt() and 0xFF) shl 16) or
                        (buf.get(at + 3).toInt() shl 24)
                java.lang.Float.intBitsToFloat(bits)
            }
            C.ENCODING_PCM_24BIT -> {
                val v =
                    (buf.get(at).toInt() and 0xFF) or
                        ((buf.get(at + 1).toInt() and 0xFF) shl 8) or
                        (buf.get(at + 2).toInt() shl 16)
                v.toFloat() / 8_388_608f
            }
            C.ENCODING_PCM_32BIT -> {
                val v =
                    (buf.get(at).toInt() and 0xFF) or
                        ((buf.get(at + 1).toInt() and 0xFF) shl 8) or
                        ((buf.get(at + 2).toInt() and 0xFF) shl 16) or
                        (buf.get(at + 3).toInt() shl 24)
                v.toFloat() / 2_147_483_648f
            }
            else -> 0f
        }

    private fun writeSampleFromFloat(buf: ByteBuffer, value: Float, enc: Int) {
        when (enc) {
            C.ENCODING_PCM_16BIT -> {
                val v =
                    (value * 32767f)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                buf.put(v.toByte())
                buf.put((v.toInt() shr 8).toByte())
            }
            C.ENCODING_PCM_FLOAT -> {
                val bits = java.lang.Float.floatToRawIntBits(value)
                buf.put((bits and 0xFF).toByte())
                buf.put(((bits shr 8) and 0xFF).toByte())
                buf.put(((bits shr 16) and 0xFF).toByte())
                buf.put((bits shr 24).toByte())
            }
            C.ENCODING_PCM_24BIT -> {
                val v = (value * 8_388_607f).toInt().coerceIn(-8_388_608, 8_388_607)
                buf.put((v and 0xFF).toByte())
                buf.put(((v shr 8) and 0xFF).toByte())
                buf.put(((v shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> {
                val v =
                    (value * 2_147_483_647f)
                        .toLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        .toInt()
                buf.put((v and 0xFF).toByte())
                buf.put(((v shr 8) and 0xFF).toByte())
                buf.put(((v shr 16) and 0xFF).toByte())
                buf.put((v shr 24).toByte())
            }
        }
    }

    private companion object {
        const val BAND_COUNT = 10
        const val MAX_CHANNELS = 8
        val BAND_FREQUENCIES =
            floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val BAND_Q = 1.41f
    }
}
