/*
 * Copyright (c) 2026 Fluxio Project
 * StereoWideningProcessor.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.stereowidening

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An [AudioProcessor] that widens the stereo image using the Mid-Side (M/S) matrix technique.
 *
 * **How it works:**
 * 1. Decomposes stereo audio into Mid (centre) and Side (difference) components.
 * 2. Amplifies the Side component by `(1 + amount)` where `amount` is in `[0..1]`.
 * 3. Applies gain compensation `1 / (1 + amount)` to prevent clipping.
 * 4. Reconstructs Left/Right from the modified Mid/Side pair.
 *
 * ## Supported encodings
 *
 * The original implementation returned NOT_SET for [C.ENCODING_PCM_FLOAT]. In the
 * MediaCodecAudioRenderer pipeline (MP3, AAC, M4A), DefaultAudioSink always delivers PCM_FLOAT to
 * the AudioProcessor chain — so NOT_SET caused ExoPlayer to silently skip this processor (and
 * downstream processors) for those formats. EQ was unaffected only because it appears before
 * StereoWidening in the chain and was already blocked earlier by ReplayGain throwing an exception.
 *
 * This implementation handles both [C.ENCODING_PCM_16BIT] and [C.ENCODING_PCM_FLOAT]. Non-stereo
 * channel counts and all other encodings still return NOT_SET (bypass cleanly).
 *
 * @Volatile: [amount] and [spatializerBypass] written from UI thread, read from audio thread.
 *   [encoding] and [channelCount] only written on the audio thread in [onConfigure].
 */
@Singleton
class StereoWideningProcessor @Inject constructor() : BaseAudioProcessor() {

    /**
     * Widening intensity in `[0.0 .. 1.0]`.
     * - `0.0` = original stereo (fast-path bypass).
     * - `1.0` = maximum widening (Side component doubled).
     */
    @Volatile var amount: Float = 0f

    /**
     * When true the Android system Spatializer is active — widening is bypassed to avoid phase
     * conflicts.
     */
    @Volatile var spatializerBypass: Boolean = false

    @Volatile private var encoding = C.ENCODING_INVALID
    @Volatile private var channelCount = 0

    // -------------------------------------------------------------------------
    // BaseAudioProcessor — ExoPlayer audio thread
    // -------------------------------------------------------------------------

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> {
                val ch = inputAudioFormat.channelCount
                if (ch != 2) {
                    // Mono or surround — M/S widening is meaningless, bypass cleanly.
                    return AudioProcessor.AudioFormat.NOT_SET
                }
                encoding = inputAudioFormat.encoding
                channelCount = ch
                inputAudioFormat
            }
            // All other encodings: bypass silently. Never throw.
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        // M/S is stateless per-sample — nothing to reset.
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        channelCount = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val outputBuffer = replaceOutputBuffer(limit - pos)

        val currentAmount = amount

        // Fast path: amount == 0 or Spatializer active — copy bytes unchanged.
        if (currentAmount <= 0f || spatializerBypass) {
            outputBuffer.put(inputBuffer.slice())
            inputBuffer.position(limit)
            outputBuffer.flip()
            return
        }

        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                processInt16(inputBuffer, outputBuffer, pos, limit, currentAmount)
            C.ENCODING_PCM_FLOAT ->
                processFloat32(inputBuffer, outputBuffer, pos, limit, currentAmount)
            else -> outputBuffer.put(inputBuffer.slice())
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }

    // -------------------------------------------------------------------------
    // Per-encoding M/S processing
    // -------------------------------------------------------------------------

    private fun processInt16(
        src: ByteBuffer,
        dst: ByteBuffer,
        pos: Int,
        limit: Int,
        currentAmount: Float,
    ) {
        val sideGain = 1.0f + currentAmount
        val compensation = 1.0f / (1.0f + currentAmount)
        var i = pos
        // PCM_16BIT stereo: 4 bytes per frame (2 bytes L + 2 bytes R).
        while (i <= limit - 4) {
            val left = src.getLeShort(i).toFloat()
            val right = src.getLeShort(i + 2).toFloat()
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f
            val widenedSide = side * sideGain
            val newLeft = (mid + widenedSide) * compensation
            val newRight = (mid - widenedSide) * compensation
            dst.putLeShort(
                newLeft.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            )
            dst.putLeShort(
                newRight
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            )
            i += 4
        }
    }

    private fun processFloat32(
        src: ByteBuffer,
        dst: ByteBuffer,
        pos: Int,
        limit: Int,
        currentAmount: Float,
    ) {
        val sideGain = 1.0f + currentAmount
        val compensation = 1.0f / (1.0f + currentAmount)
        var i = pos
        // PCM_FLOAT stereo: 8 bytes per frame (4 bytes L + 4 bytes R).
        while (i <= limit - 8) {
            val left = src.getLeFloat(i)
            val right = src.getLeFloat(i + 4)
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f
            val widenedSide = side * sideGain
            val newLeft = ((mid + widenedSide) * compensation).coerceIn(-1f, 1f)
            val newRight = ((mid - widenedSide) * compensation).coerceIn(-1f, 1f)
            dst.putLeFloat(newLeft)
            dst.putLeFloat(newRight)
            i += 8
        }
    }

    // -------------------------------------------------------------------------
    // Little-endian helpers
    // -------------------------------------------------------------------------

    private fun ByteBuffer.getLeShort(at: Int): Short =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }

    private fun ByteBuffer.getLeFloat(at: Int): Float {
        val bits =
            (get(at).toInt() and 0xFF) or
                ((get(at + 1).toInt() and 0xFF) shl 8) or
                ((get(at + 2).toInt() and 0xFF) shl 16) or
                (get(at + 3).toInt() shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }

    private fun ByteBuffer.putLeFloat(value: Float) {
        val bits = java.lang.Float.floatToRawIntBits(value)
        put((bits and 0xFF).toByte())
        put(((bits shr 8) and 0xFF).toByte())
        put(((bits shr 16) and 0xFF).toByte())
        put((bits shr 24).toByte())
    }
}
