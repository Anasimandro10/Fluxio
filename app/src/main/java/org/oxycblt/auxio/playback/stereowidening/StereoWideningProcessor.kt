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
 * 2. Amplifies the Side component by `(1 + amount)` where `amount` is `[0..1]`.
 * 3. Applies gain compensation `1 / (1 + amount)` to prevent clipping when the widened signal
 *    exceeds the original peak.
 * 4. Reconstructs Left/Right from the modified Mid/Side pair.
 *
 * **Why Mid-Side?**
 * - Phase-coherent: no Haas delay, no comb-filtering on mono speakers/Bluetooth.
 * - Zero-latency: pure sample-by-sample math, no buffering or look-ahead.
 * - CPU-trivial: two additions, two subtractions, and two multiplications per frame.
 *
 * Only handles [C.ENCODING_PCM_16BIT] — the only encoding delivered to audio processors in this
 * ExoPlayer build (same contract as [EqualizerAudioProcessor]). Any other encoding or non-stereo
 * channel count returns [AudioProcessor.AudioFormat.NOT_SET] so the processor is silently bypassed.
 *
 * **Thread safety:** [amount] is [@Volatile] and written from the UI thread via
 * [StereoWideningSettings]. [onConfigure], [onFlush], [onReset] and [queueInput] run on the
 * ExoPlayer audio thread. A single `Float` write is atomic on the JVM (JLS §17.7).
 */
@Singleton
class StereoWideningProcessor @Inject constructor() : BaseAudioProcessor() {

    /**
     * Widening intensity in the range `[0.0 .. 1.0]`.
     * - `0.0` = original stereo image (no processing, fast-path bypass).
     * - `1.0` = maximum widening (Side doubled).
     *
     * Written by [StereoWideningSettings] on the UI thread; read by [queueInput] on the audio
     * thread. JVM guarantees atomic writes for `Float` (32-bit), and [@Volatile] ensures visibility.
     */
    @Volatile var amount: Float = 0f

    // ── Audio format (set on audio thread in onConfigure) ───────────────

    @Volatile private var channelCount = 0

    // ── AudioProcessor lifecycle (audio thread) ─────────────────────────

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        // Only process PCM_16BIT stereo. Return NOT_SET for everything else so the processor
        // is silently bypassed — never throw (documented project rule).
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channelCount = inputAudioFormat.channelCount
        if (channelCount != 2) {
            // Mono or surround — widening is meaningless, bypass cleanly.
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        // No delay lines or state to reset — M/S is stateless per-sample.
    }

    override fun onReset() {
        channelCount = 0
    }

    // ── Audio processing (audio thread) ─────────────────────────────────

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - pos
        val outputBuffer = replaceOutputBuffer(size)

        val currentAmount = amount

        // Fast path: amount == 0 → copy bytes unchanged (memcpy-level speed).
        if (currentAmount <= 0f) {
            outputBuffer.put(inputBuffer.slice())
            inputBuffer.position(limit)
            outputBuffer.flip()
            return
        }

        // Pre-compute constants outside the loop — avoids repeated division per frame.
        val sideGain = 1.0f + currentAmount
        val compensation = 1.0f / (1.0f + currentAmount)

        // PCM_16BIT stereo: 4 bytes per frame (2 bytes L + 2 bytes R).
        var i = pos
        while (i <= limit - 4) {
            // Read L and R as signed little-endian shorts → float.
            val left = inputBuffer.getLeShort(i).toFloat()
            val right = inputBuffer.getLeShort(i + 2).toFloat()

            // Decompose into Mid (centre) and Side (difference).
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f

            // Amplify the Side component.
            val widenedSide = side * sideGain

            // Reconstruct L/R with gain compensation to prevent clipping.
            val newLeft = (mid + widenedSide) * compensation
            val newRight = (mid - widenedSide) * compensation

            // Clamp to Short range and write as little-endian.
            outputBuffer.putLeShort(
                newLeft
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            )
            outputBuffer.putLeShort(
                newRight
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            )

            i += 4
        }

        // Mark inputBuffer as fully consumed (required by ExoPlayer's AudioProcessorChain).
        inputBuffer.position(limit)
        outputBuffer.flip()
    }

    // ── Little-endian helpers (same pattern as EqualizerAudioProcessor) ──

    /** Reads a little-endian [Short] at absolute index [at] without advancing position. */
    private fun ByteBuffer.getLeShort(at: Int): Short =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    /** Writes a little-endian [Short] at the current position, advancing it by 2. */
    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }
}
