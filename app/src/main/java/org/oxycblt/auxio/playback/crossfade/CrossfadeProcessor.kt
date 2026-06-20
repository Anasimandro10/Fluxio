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
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin

/**
 * An [AudioProcessor] that applies an equal-power (sinusoidal) fade envelope to produce a
 * professional crossfade effect at track transitions — the same curve shape used by Spotify and
 * other pro audio players.
 *
 * Equal-power crossfade maintains constant perceived loudness across the transition by using
 * `sin(t * π/2)` for fade-in and `cos(t * π/2)` for fade-out, where `t` is the normalized progress
 * `[0..1]`. This avoids the −6 dB "dip" in the middle that linear fading produces.
 *
 * Supports [C.ENCODING_PCM_16BIT], [C.ENCODING_PCM_FLOAT], and [C.ENCODING_PCM_32BIT]. Any other
 * encoding returns [AudioProcessor.AudioFormat.NOT_SET] so the processor is silently bypassed
 * without errors.
 *
 * Thread safety: All mutable fade state is held in an immutable [FadeState] snapshot swapped
 * atomically via [AtomicReference]. [notifyTrackStart] and [notifyTrackEndingSoon] are called from
 * the main thread; [queueInput], [onConfigure], [onFlush] and [onReset] run on the ExoPlayer audio
 * thread. The atomic snapshot guarantees a consistent view across threads without locks.
 */
@Singleton
class CrossfadeProcessor @Inject constructor() : BaseAudioProcessor() {

    /** Whether crossfade is active. Written by CrossfadeSettings. */
    @Volatile private var _enabled = false
    var enabled: Boolean
        get() = _enabled
        set(value) {
            if (_enabled != value) {
                _enabled = value
                onActiveStateChanged?.invoke()
            }
        }

    /**
     * Called on the main thread whenever crossfade transitions between enabled and disabled. Wired
     * by [ExoPlaybackStateHolder] to trigger Audio Offload re-evaluation.
     */
    var onActiveStateChanged: (() -> Unit)? = null

    /** Crossfade duration in milliseconds. Written by CrossfadeSettings. */
    @Volatile var crossfadeDurationMs = 0L

    // ── Fade state machine ──────────────────────────────────────────────

    private enum class FadeDirection {
        NONE,
        IN,
        OUT,
    }

    /**
     * Immutable snapshot of the fade state. Swapped atomically so the audio thread always sees a
     * consistent {direction, remaining, total} triple — no partial writes possible.
     */
    private data class FadeState(
        val direction: FadeDirection = FadeDirection.NONE,
        val remaining: Int = 0,
        val total: Int = 1,
    )

    private val fadeState = AtomicReference(FadeState())

    // ── Audio format ────────────────────────────────────────────────────

    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 0
    @Volatile private var encoding = C.ENCODING_PCM_16BIT

    // ── Public API (main thread) ────────────────────────────────────────

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
        fadeState.set(FadeState(FadeDirection.IN, samples, samples))
    }

    /**
     * Signals that the current track is about to end and begins a fade-out for
     * [crossfadeDurationMs] ms.
     *
     * Called from the position-monitoring logic in ExoPlaybackStateHolder.
     */
    fun notifyTrackEndingSoon() {
        if (!enabled || crossfadeDurationMs <= 0L) return
        val sr = sampleRate
        if (sr <= 0) return
        val samples = (crossfadeDurationMs * sr / 1000L).toInt().coerceAtLeast(1)
        fadeState.set(FadeState(FadeDirection.OUT, samples, samples))
    }

    // ── AudioProcessor lifecycle (audio thread) ─────────────────────────

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_32BIT -> {
                sampleRate = inputAudioFormat.sampleRate
                channelCount = inputAudioFormat.channelCount
                encoding = inputAudioFormat.encoding
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onFlush() {
        // Seek or manual skip — cancel any active fade immediately.
        fadeState.set(FadeState())
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_PCM_16BIT
        fadeState.set(FadeState())
    }

    // ── Audio processing (audio thread) ─────────────────────────────────

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - pos
        val outputBuffer = replaceOutputBuffer(size)

        val state = fadeState.get()
        if (!enabled || state.direction == FadeDirection.NONE) {
            // Fast path: no active fade — pass bytes through unchanged.
            outputBuffer.put(inputBuffer.slice())
            inputBuffer.position(limit)
            outputBuffer.flip()
            return
        }

        // Ensure native byte order for efficient bulk reads.
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        when (encoding) {
            C.ENCODING_PCM_16BIT -> processInt16(inputBuffer, outputBuffer, pos, limit, state)
            C.ENCODING_PCM_FLOAT -> processFloat32(inputBuffer, outputBuffer, pos, limit, state)
            C.ENCODING_PCM_32BIT -> processInt32(inputBuffer, outputBuffer, pos, limit, state)
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }

    // ── Per-encoding processors ─────────────────────────────────────────

    private fun processInt16(
        input: ByteBuffer,
        output: ByteBuffer,
        pos: Int,
        limit: Int,
        initialState: FadeState,
    ) {
        val ch = channelCount.coerceAtLeast(1)
        val bytesPerFrame = 2 * ch
        val total = initialState.total.toFloat()
        var remaining = initialState.remaining
        val direction = initialState.direction
        var i = pos

        while (i <= limit - bytesPerFrame) {
            val gain = computeGain(direction, remaining, total)

            for (c in 0 until ch) {
                val sample = input.getShort(i)
                val scaled =
                    (sample * gain)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                output.putShort(scaled)
                i += 2
            }

            if (remaining > 0) {
                remaining--
                if (remaining <= 0) {
                    fadeState.set(FadeState())
                    // Process remaining input as passthrough.
                    if (i < limit) {
                        val slice = input.duplicate()
                        slice.position(i)
                        slice.limit(limit)
                        output.put(slice)
                    }
                    return
                }
            }
        }

        // Update shared state with the remaining count.
        fadeState.set(FadeState(direction, remaining, initialState.total))
    }

    private fun processFloat32(
        input: ByteBuffer,
        output: ByteBuffer,
        pos: Int,
        limit: Int,
        initialState: FadeState,
    ) {
        val ch = channelCount.coerceAtLeast(1)
        val bytesPerFrame = 4 * ch
        val total = initialState.total.toFloat()
        var remaining = initialState.remaining
        val direction = initialState.direction
        var i = pos

        while (i <= limit - bytesPerFrame) {
            val gain = computeGain(direction, remaining, total)

            for (c in 0 until ch) {
                val sample = input.getFloat(i)
                val scaled = (sample * gain).coerceIn(-1f, 1f)
                output.putFloat(scaled)
                i += 4
            }

            if (remaining > 0) {
                remaining--
                if (remaining <= 0) {
                    fadeState.set(FadeState())
                    if (i < limit) {
                        val slice = input.duplicate()
                        slice.position(i)
                        slice.limit(limit)
                        output.put(slice)
                    }
                    return
                }
            }
        }

        fadeState.set(FadeState(direction, remaining, initialState.total))
    }

    private fun processInt32(
        input: ByteBuffer,
        output: ByteBuffer,
        pos: Int,
        limit: Int,
        initialState: FadeState,
    ) {
        val ch = channelCount.coerceAtLeast(1)
        val bytesPerFrame = 4 * ch
        val total = initialState.total.toFloat()
        var remaining = initialState.remaining
        val direction = initialState.direction
        var i = pos

        while (i <= limit - bytesPerFrame) {
            val gain = computeGain(direction, remaining, total)

            for (c in 0 until ch) {
                val sample = input.getInt(i)
                val scaled = (sample.toLong() * gain.toLong()).toInt()
                output.putInt(scaled)
                i += 4
            }

            if (remaining > 0) {
                remaining--
                if (remaining <= 0) {
                    fadeState.set(FadeState())
                    if (i < limit) {
                        val slice = input.duplicate()
                        slice.position(i)
                        slice.limit(limit)
                        output.put(slice)
                    }
                    return
                }
            }
        }

        fadeState.set(FadeState(direction, remaining, initialState.total))
    }

    // ── Equal-power gain curve ──────────────────────────────────────────

    /**
     * Computes the gain factor using an equal-power (sinusoidal) curve.
     * - **Fade-in**: `sin(progress * π/2)` — starts at 0, reaches 1
     * - **Fade-out**: `sin(progress * π/2)` where progress counts down — starts at 1, reaches 0
     *
     * At the crossover point (50%), both tracks are at `sin(π/4) ≈ 0.707` (−3 dB each), summing to
     * constant power — no perceived volume dip.
     */
    private fun computeGain(direction: FadeDirection, remaining: Int, total: Float): Float {
        if (total <= 0f) return 1f
        return when (direction) {
            FadeDirection.IN -> {
                // progress goes 0→1 as remaining goes total→0
                val progress = 1f - remaining / total
                sin(progress.toDouble() * PI / 2.0).toFloat()
            }
            FadeDirection.OUT -> {
                // progress goes 1→0 as remaining goes total→0
                val progress = remaining / total
                sin(progress.toDouble() * PI / 2.0).toFloat()
            }
            FadeDirection.NONE -> 1f
        }
    }
}
