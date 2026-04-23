/*
 * Copyright (c) 2026 Fluxio Project
 * CrossfadeProcessor.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.crossfade

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An [AudioProcessor] that applies linear fade-in and fade-out envelopes to produce a crossfade
 * effect at track transitions.
 *
 * Only handles [C.ENCODING_PCM_16BIT]. Any other encoding returns
 * [AudioProcessor.AudioFormat.NOT_SET] so the processor is silently bypassed without errors —
 * same contract as [ReplayGainAudioProcessor] and [EqualizerAudioProcessor].
 *
 * Thread safety: [enabled] and [crossfadeDurationMs] are @Volatile. [notifyTrackStart] and
 * [notifyTrackEndingSoon] are called from the main thread; [queueInput], [onConfigure], [onFlush]
 * and [onReset] run on the ExoPlayer audio thread. [fadeSamplesRemaining] is an [AtomicInteger]
 * so the main-thread write (set) and audio-thread write (decrementAndGet) do not race.
 */
@Singleton
class CrossfadeProcessor @Inject constructor() : BaseAudioProcessor() {

    /** Whether crossfade is active. Written by CrossfadeSettings in paso 24b. */
    @Volatile var enabled = false

    /** Crossfade duration in milliseconds. Written by CrossfadeSettings in paso 24b. */
    @Volatile var crossfadeDurationMs = 0L

    private enum class FadeDirection {
        NONE,
        IN,
        OUT,
    }

    @Volatile private var fadeDirection = FadeDirection.NONE

    /** Remaining samples in the current fade. Decremented on the audio thread. */
    private val fadeSamplesRemaining = AtomicInteger(0)

    /** Total samples for the current fade ramp. Written before [fadeSamplesRemaining]. */
    @Volatile private var fadeTotalSamples = 1

    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 0

    /**
     * Signals the start of a new track and begins a fade-in for [crossfadeDurationMs] ms.
     *
     * Called from the main thread on [Player.MEDIA_ITEM_TRANSITION_REASON_AUTO].
     */
    fun notifyTrackStart() {
        if (!enabled || crossfadeDurationMs <= 0L) return
        val sr = sampleRate
        if (sr <= 0) return
        val samples = (crossfadeDurationMs * sr / 1000L).toInt().coerceAtLeast(1)
        fadeTotalSamples = samples
        fadeSamplesRemaining.set(samples)
        fadeDirection = FadeDirection.IN
    }

    /**
     * Signals that the current track is about to end and begins a fade-out for
     * [crossfadeDurationMs] ms.
     *
     * Called from the position-monitoring coroutine in ExoPlaybackStateHolder.
     */
    fun notifyTrackEndingSoon() {
        if (!enabled || crossfadeDurationMs <= 0L) return
        val sr = sampleRate
        if (sr <= 0) return
        val samples = (crossfadeDurationMs * sr / 1000L).toInt().coerceAtLeast(1)
        fadeTotalSamples = samples
        fadeSamplesRemaining.set(samples)
        fadeDirection = FadeDirection.OUT
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
        return inputAudioFormat
    }

    override fun onFlush() {
        // Seek or manual skip — cancel any active fade immediately.
        fadeDirection = FadeDirection.NONE
        fadeSamplesRemaining.set(0)
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        fadeDirection = FadeDirection.NONE
        fadeSamplesRemaining.set(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val outputBuffer = replaceOutputBuffer(limit - pos)

        val direction = fadeDirection
        if (!enabled || direction == FadeDirection.NONE) {
            // Fast path: no active fade — pass bytes through unchanged.
            outputBuffer.put(inputBuffer.slice())
            inputBuffer.position(limit)
            outputBuffer.flip()
            return
        }

        val localChannelCount = channelCount.coerceAtLeast(1)
        val bytesPerFrame = 2 * localChannelCount
        val total = fadeTotalSamples.toFloat()
        var i = pos

        while (i <= limit - bytesPerFrame) {
            val remaining = fadeSamplesRemaining.get().coerceAtLeast(0)
            val gain =
                when (direction) {
                    FadeDirection.IN -> 1f - remaining.toFloat() / total
                    FadeDirection.OUT -> remaining.toFloat() / total
                    FadeDirection.NONE -> 1f
                }

            for (ch in 0 until localChannelCount) {
                val sample = inputBuffer.getLeShort(i)
                val scaled =
                    (sample * gain)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                outputBuffer.putLeShort(scaled)
                i += 2
            }

            // Decrement once per frame. When it reaches zero, stop the fade.
            if (remaining > 0 && fadeSamplesRemaining.decrementAndGet() <= 0) {
                fadeDirection = FadeDirection.NONE
            }
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }

    /** Reads a little-endian [Short] at absolute byte index [at] without advancing position. */
    private fun ByteBuffer.getLeShort(at: Int): Short =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    /** Writes a little-endian [Short] at the current position, advancing it by 2. */
    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }
}
