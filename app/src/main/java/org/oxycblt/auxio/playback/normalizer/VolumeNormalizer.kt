/*
 * Copyright (c) 2026 Fluxio Project
 * VolumeNormalizer.kt is part of Fluxio.
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

package org.oxycblt.auxio.playback.normalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.replaygain.ReplayGainMode
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Applies ITU-R BS.1770-4 loudness normalization to the audio stream.
 *
 * Gain values come from [NormalizationScanner], which analyzes files offline using
 * MediaExtractor + MediaCodec. The first time a song plays without a cached measurement,
 * gain = 1.0 (unmodified). When the scanner finishes, it notifies this processor and
 * the gain is applied with a smooth transition. On subsequent plays the gain is applied
 * from the first sample.
 *
 * ## Thread model
 * Audio thread (exclusive): [currentGain], [targetGain].
 * @Volatile (main → audio): [pendingTargetGain].
 * Main thread (exclusive): [memCache], [currentSongUid].
 */
@Singleton
class VolumeNormalizer
@Inject
constructor(
    private val playbackSettings: PlaybackSettings,
    private val playbackManager: PlaybackStateManager,
    private val normalizationDao: NormalizationDao,
    private val scanner: NormalizationScanner,
) : BaseAudioProcessor(), PlaybackSettings.Listener, PlaybackStateManager.Listener {

    // ─── Audio thread only ────────────────────────────────────────────────────

    private var currentGain = 1.0f
    private var targetGain = 1.0f

    // ─── Main → Audio thread ──────────────────────────────────────────────────

    @Volatile private var pendingTargetGain = NO_PENDING

    // ─── Main thread only ─────────────────────────────────────────────────────

    private val memCache =
        object : LinkedHashMap<String, Float>(256, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Float>) = size > 200
        }

    private var currentSongUid: String? = null

    // Never cancelled — @Singleton
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun attach() {
        playbackSettings.registerListener(this)
        playbackManager.addListener(this)

        // NormalizationScanner calls this on the main thread when a song finishes analyzing
        scanner.onSongAnalyzed = { uid, rmsDb ->
            memCache[uid] = rmsDb
            if (currentSongUid == uid) {
                pendingTargetGain = computeGainFromRmsDb(rmsDb)
                L.d("VolumeNormalizer: applied gain mid-song uid=$uid")
            }
        }
    }

    fun release() {
        playbackSettings.unregisterListener(this)
        playbackManager.removeListener(this)
        scanner.onSongAnalyzed = null
        // Do NOT cancel scope — @Singleton
    }

    // ─── PlaybackStateManager.Listener ───────────────────────────────────────

    override fun onIndexMoved(index: Int) = handleNewSong()

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        if (change.type == QueueChange.Type.SONG) handleNewSong()
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) = handleNewSong()

    // ─── PlaybackSettings.Listener ────────────────────────────────────────────

    override fun onNormalizationSettingsChanged() {
        memCache.clear()
        handleNewSong()
    }

    // ─── Main thread ──────────────────────────────────────────────────────────

    private fun handleNewSong() {
        val song = playbackManager.currentSong

        scanner.setPlaying(playbackManager.progression.isPlaying)

        if (song == null) {
            currentSongUid = null
            pendingTargetGain = 1.0f
            return
        }

        val hasRgTags = song.replayGainAdjustment.run { track != null || album != null }
        val rgActive = playbackSettings.replayGainMode != ReplayGainMode.OFF

        if (!playbackSettings.normalizationEnabled || (hasRgTags && rgActive)) {
            currentSongUid = null
            pendingTargetGain = 1.0f
            return
        }

        val uid = song.uid.toString()
        currentSongUid = uid

        val queue = playbackManager.queue
        val nextSongs = buildList {
            val idx = queue.indexOf(song)
            for (i in 1..3) queue.getOrNull(idx + i)?.let { add(it) }
        }

        // Memory cache hit — instant, no coroutine needed
        val cached = memCache[uid]
        if (cached != null) {
            pendingTargetGain = computeGainFromRmsDb(cached)
            scanner.requestHighPriority(nextSongs)
            return
        }

        // Start unmodified — scanner will notify us when analysis is done
        pendingTargetGain = 1.0f

        scope.launch {
            val record = normalizationDao.getForSong(uid)
            withContext(Dispatchers.Main) {
                if (currentSongUid != uid) return@withContext
                if (record != null) {
                    memCache[uid] = record.measuredRmsDb
                    pendingTargetGain = computeGainFromRmsDb(record.measuredRmsDb)
                    scanner.requestHighPriority(nextSongs)
                } else {
                    // Not in DB — request high-priority analysis for this song + next
                    scanner.requestHighPriority(listOf(song) + nextSongs)
                }
            }
        }
    }

    /** Clears all cached measurements. Called from [AudioPreferenceFragment]. Main thread. */
    fun clearCache() {
        memCache.clear()
        scope.launch { normalizationDao.deleteAll() }
    }

    // ─── BaseAudioProcessor ───────────────────────────────────────────────────

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val output = replaceOutputBuffer(limit - pos)

        val pending = pendingTargetGain
        if (pending != NO_PENDING) {
            pendingTargetGain = NO_PENDING
            targetGain = pending
        }

        var i = pos
        while (i < limit) {
            val rawS = inputBuffer.getLeShort(i).toInt()
            currentGain += (targetGain - currentGain) * SMOOTHING_COEFF
            output.putLeShort(softLimit(rawS.toFloat() * currentGain).toShort())
            i += 2
        }

        inputBuffer.position(limit)
        output.flip()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun softLimit(sample: Float): Int {
        val a = abs(sample)
        val sign = if (sample >= 0f) 1f else -1f
        val limited =
            if (a <= LIMITER_KNEE) a
            else {
                val e = a - LIMITER_KNEE
                val h = PCM_MAX - LIMITER_KNEE
                LIMITER_KNEE + h * (1f - 1f / (1f + e / h))
            }
        return (sign * limited.coerceAtMost(PCM_MAX)).toInt()
    }

    private fun computeGainFromRmsDb(rmsDb: Float): Float {
        val gainDb = playbackSettings.normalizationTarget.targetRmsDb - rmsDb
        return (10f.pow(gainDb / 20f)).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    private fun ByteBuffer.getLeShort(at: Int) =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }

    private companion object {
        const val SMOOTHING_COEFF = 0.0005f
        const val MAX_GAIN = 4.0f
        const val MIN_GAIN = 0.125f
        const val LIMITER_KNEE = 27_852f
        const val PCM_MAX = 32_767f
        val NO_PENDING = Float.MIN_VALUE
    }
}
