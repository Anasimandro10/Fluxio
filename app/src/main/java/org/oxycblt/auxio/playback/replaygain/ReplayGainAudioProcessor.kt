/*
 * Copyright (c) 2022 Fluxio Project
 * ReplayGainAudioProcessor.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.replaygain

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.math.pow
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * An [AudioProcessor] that handles ReplayGain values and their amplification of the audio stream.
 * Instead of leveraging the volume attribute like other implementations, this system manipulates
 * the bitstream itself to modify the volume, which allows the use of positive ReplayGain values.
 *
 * ## Supported encodings
 *
 * The original implementation only accepted [C.ENCODING_PCM_16BIT] and threw
 * [AudioProcessor.UnhandledAudioFormatException] for everything else. That caused ExoPlayer to
 * discard the entire AudioProcessor chain for MP3, AAC and M4A, because the MediaCodecAudioRenderer
 * pipeline converts audio to [C.ENCODING_PCM_FLOAT] before passing it through the chain. As the
 * first processor in the chain, this exception meant that EQ, StereoWidening and Crossfade were all
 * silently skipped for those formats.
 *
 * This implementation accepts all four PCM encodings that ExoPlayer can deliver:
 * - [C.ENCODING_PCM_16BIT] — signed 16-bit LE (Ffmpeg pipeline: OGG, some MP3)
 * - [C.ENCODING_PCM_FLOAT] — IEEE 754 32-bit LE (MediaCodec pipeline: MP3, AAC, M4A)
 * - [C.ENCODING_PCM_24BIT] — signed 24-bit LE (hi-res FLAC, WAV 24-bit)
 * - [C.ENCODING_PCM_32BIT] — signed 32-bit LE (WAV 32-bit integer)
 *
 * Output encoding is always identical to input encoding — no downstream format change. Truly
 * unsupported encodings (AC3, DTS, PCM_8BIT) return NOT_SET to bypass silently.
 *
 * Note: This audio processor must be attached to a respective [Player] instance as a
 * [Player.Listener] to function properly.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class ReplayGainAudioProcessor
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val playbackSettings: PlaybackSettings,
) : BaseAudioProcessor(), PlaybackStateManager.Listener, PlaybackSettings.Listener {

    /** Indicates whether the processor is actively changing the volume. */
    val isEffectActive: Boolean
        get() = volume != 1f

    /** Fired when the processor transitions between unity gain (1f) and active gain (!= 1f). */
    var onActiveStateChanged: (() -> Unit)? = null

    private var volume = 1f
        set(value) {
            val wasActive = field != 1f
            field = value
            val isActive = value != 1f
            // Processed bytes are no longer valid, flush the stream.
            flush()
            if (wasActive != isActive) {
                onActiveStateChanged?.invoke()
            }
        }

    /** Active encoding, updated in [onConfigure] on the audio thread. */
    private var encoding = C.ENCODING_INVALID

    fun attach() {
        playbackManager.addListener(this)
        playbackSettings.registerListener(this)
    }

    fun release() {
        playbackManager.removeListener(this)
        playbackSettings.unregisterListener(this)
    }

    // -------------------------------------------------------------------------
    // PlaybackStateManager.Listener + PlaybackSettings.Listener
    // -------------------------------------------------------------------------

    override fun onIndexMoved(index: Int) {
        L.d("Index moved, updating current song")
        applyReplayGain(playbackManager.currentSong)
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        if (change.type == QueueChange.Type.SONG) {
            applyReplayGain(playbackManager.currentSong)
        }
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        L.d("New playback started, updating playback information")
        applyReplayGain(playbackManager.currentSong)
    }

    override fun onReplayGainSettingsChanged() {
        applyReplayGain(playbackManager.currentSong)
    }

    // -------------------------------------------------------------------------
    // ReplayGain resolution
    // -------------------------------------------------------------------------

    private fun applyReplayGain(song: Song?) {
        if (song == null) {
            L.d("Nothing playing, disabling adjustment")
            volume = 1f
            return
        }

        L.d("Applying ReplayGain adjustment for $song")

        val gain = song.replayGainAdjustment
        val preAmp = playbackSettings.replayGainPreAmp

        val resolvedAdjustment =
            when (playbackSettings.replayGainMode) {
                ReplayGainMode.OFF -> {
                    L.d("ReplayGain is off")
                    null
                }
                ReplayGainMode.TRACK -> {
                    L.d("Using track strategy")
                    gain.track ?: gain.album
                }
                ReplayGainMode.ALBUM -> {
                    L.d("Using album strategy")
                    gain.album ?: gain.track
                }
                ReplayGainMode.DYNAMIC -> {
                    L.d("Using dynamic strategy")
                    gain.album?.takeIf {
                        playbackManager.parent is Album &&
                            playbackManager.currentSong?.album == playbackManager.parent
                    } ?: gain.track
                }
            }

        val amplifiedAdjustment =
            if (resolvedAdjustment != null) {
                L.d("Applying with pre-amp")
                resolvedAdjustment + preAmp.with
            } else {
                L.d("Applying without pre-amp")
                preAmp.without
            }

        L.d("Applying ReplayGain adjustment ${amplifiedAdjustment}db")
        volume = 10f.pow(amplifiedAdjustment / 20f)
    }

    // -------------------------------------------------------------------------
    // BaseAudioProcessor
    // -------------------------------------------------------------------------

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT -> {
                encoding = inputAudioFormat.encoding
                inputAudioFormat
            }
            // Return NOT_SET instead of throwing — a throw here causes ExoPlayer to discard
            // the entire AudioProcessor chain for the current renderer.
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val buffer = replaceOutputBuffer(limit - pos)

        if (volume == 1f) {
            // Unity gain — copy raw bytes unchanged.
            buffer.put(inputBuffer.slice())
            inputBuffer.position(limit)
            buffer.flip()
            return
        }

        when (encoding) {
            C.ENCODING_PCM_16BIT -> processInt16(inputBuffer, buffer, pos, limit)
            C.ENCODING_PCM_FLOAT -> processFloat32(inputBuffer, buffer, pos, limit)
            C.ENCODING_PCM_24BIT -> processInt24(inputBuffer, buffer, pos, limit)
            C.ENCODING_PCM_32BIT -> processInt32(inputBuffer, buffer, pos, limit)
            else -> buffer.put(inputBuffer.slice())
        }

        inputBuffer.position(limit)
        buffer.flip()
    }

    // -------------------------------------------------------------------------
    // Per-encoding processing paths
    // -------------------------------------------------------------------------

    private fun processInt16(src: ByteBuffer, dst: ByteBuffer, pos: Int, limit: Int) {
        var i = pos
        while (i < limit - 1) {
            val raw = (src.get(i).toInt() and 0xFF) or (src.get(i + 1).toInt() shl 8)
            val scaled =
                (raw.toShort() * volume)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            dst.put(scaled.toByte())
            dst.put((scaled.toInt() shr 8).toByte())
            i += 2
        }
    }

    private fun processFloat32(src: ByteBuffer, dst: ByteBuffer, pos: Int, limit: Int) {
        var i = pos
        while (i < limit - 3) {
            val bits =
                (src.get(i).toInt() and 0xFF) or
                    ((src.get(i + 1).toInt() and 0xFF) shl 8) or
                    ((src.get(i + 2).toInt() and 0xFF) shl 16) or
                    (src.get(i + 3).toInt() shl 24)
            val scaled = (java.lang.Float.intBitsToFloat(bits) * volume).coerceIn(-1f, 1f)
            val outBits = java.lang.Float.floatToRawIntBits(scaled)
            dst.put((outBits and 0xFF).toByte())
            dst.put(((outBits shr 8) and 0xFF).toByte())
            dst.put(((outBits shr 16) and 0xFF).toByte())
            dst.put((outBits shr 24).toByte())
            i += 4
        }
    }

    private fun processInt24(src: ByteBuffer, dst: ByteBuffer, pos: Int, limit: Int) {
        var i = pos
        while (i < limit - 2) {
            // Sign-extend 24-bit to 32-bit via left-shift + right arithmetic shift.
            val v =
                ((src.get(i).toInt() and 0xFF) or
                    ((src.get(i + 1).toInt() and 0xFF) shl 8) or
                    (src.get(i + 2).toInt() shl 16))
            val scaled = (v * volume).toInt().coerceIn(-8_388_608, 8_388_607)
            dst.put((scaled and 0xFF).toByte())
            dst.put(((scaled shr 8) and 0xFF).toByte())
            dst.put(((scaled shr 16) and 0xFF).toByte())
            i += 3
        }
    }

    private fun processInt32(src: ByteBuffer, dst: ByteBuffer, pos: Int, limit: Int) {
        var i = pos
        while (i < limit - 3) {
            val v =
                (src.get(i).toInt() and 0xFF) or
                    ((src.get(i + 1).toInt() and 0xFF) shl 8) or
                    ((src.get(i + 2).toInt() and 0xFF) shl 16) or
                    (src.get(i + 3).toInt() shl 24)
            // Use Long arithmetic to avoid overflow before clamping.
            val scaled =
                (v.toLong() * volume)
                    .toLong()
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    .toInt()
            dst.put((scaled and 0xFF).toByte())
            dst.put(((scaled shr 8) and 0xFF).toByte())
            dst.put(((scaled shr 16) and 0xFF).toByte())
            dst.put((scaled shr 24).toByte())
            i += 4
        }
    }
}
