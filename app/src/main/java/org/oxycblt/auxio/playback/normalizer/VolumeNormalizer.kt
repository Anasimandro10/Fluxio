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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan
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
 * An [AudioProcessor] implementing ITU-R BS.1770-4 loudness normalization.
 *
 * Measures the K-weighted RMS of the first [MEASUREMENT_SECONDS] seconds of each song and
 * applies a single fixed gain for the rest of the track. The measurement is cached in Room
 * so subsequent plays use the stored value from the first sample. Automatically bypasses
 * when ReplayGain is active and the song has RG tags.
 *
 * ## No flush() calls
 * Uses exponential interpolation ([SMOOTHING_COEFF]) sample-by-sample — transitions are
 * inaudible (~150 ms). flush() is never called for gain changes.
 *
 * ## Thread model
 * **Audio thread** (exclusive): [sumSquares], [totalSamples], [windowSamples], [minSamples],
 * [currentGain], [targetGain], [gainLocked], [measuringUid], [filterState], [kwStage1],
 * [kwStage2], [channelCount], [sampleRate], [currentSampleIndex].
 *
 * **Main thread** (exclusive): [memCache]. All writes via withContext(Dispatchers.Main).
 *
 * **Cross-thread (@Volatile)**: [pendingReset], [pendingTargetGain], [currentSongUid],
 * [shouldSkip]. Written by main thread, read/consumed by audio thread.
 */
@Singleton
class VolumeNormalizer
@Inject
constructor(
    private val playbackSettings: PlaybackSettings,
    private val playbackManager: PlaybackStateManager,
    private val normalizationDao: NormalizationDao,
) : BaseAudioProcessor(), PlaybackSettings.Listener, PlaybackStateManager.Listener {

    // ─── Audio thread only ────────────────────────────────────────────────────

    private var sumSquares = 0.0
    private var totalSamples = 0L
    private var windowSamples = 0L
    private var minSamples = 0L
    private var currentGain = 1.0f
    private var targetGain = 1.0f
    private var gainLocked = false
    private var channelCount = 0
    private var sampleRate = 0
    private var currentSampleIndex = 0L

    // Tracks which song the audio thread is currently measuring — separate from
    // currentSongUid to avoid the race where the main thread updates currentSongUid
    // to the new song before the audio thread has saved the previous song's measurement.
    private var measuringUid: String? = null

    // K-weighting biquad filter state: [channel][stage 0=preemph 1=highpass][x1, x2, y1, y2]
    private val filterState = Array(8) { Array(2) { FloatArray(4) } }
    private var kwStage1 = KW_STAGE1_44100
    private var kwStage2 = KW_STAGE2_44100

    // ─── Main → Audio thread (@Volatile) ─────────────────────────────────────

    @Volatile private var pendingReset = false
    @Volatile private var pendingTargetGain = NO_PENDING
    @Volatile private var currentSongUid: String? = null
    @Volatile private var shouldSkip = false

    // ─── Main thread only ─────────────────────────────────────────────────────

    private val memCache =
        object : LinkedHashMap<String, Float>(256, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Float>) = size > 200
        }

    // ─── Coroutine scope ─────────────────────────────────────────────────────

    // Never cancelled — @Singleton that lives for the app's lifetime (same as StatsTracker.kt).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun attach() {
        playbackSettings.registerListener(this)
        playbackManager.addListener(this)
    }

    fun release() {
        playbackSettings.unregisterListener(this)
        playbackManager.removeListener(this)
        // Do NOT cancel scope — this is a @Singleton; the service may restart.
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
        L.d("VolumeNormalizer: settings changed")
        memCache.clear()
        handleNewSong()
    }

    // ─── Main thread: song change logic ──────────────────────────────────────

    private fun handleNewSong() {
        val song = playbackManager.currentSong
        val queue = playbackManager.queue
        val nextSong = queue.getOrNull(queue.indexOf(song) + 1)

        if (song == null) {
            shouldSkip = true
            pendingReset = true
            return
        }

        val hasRgTags = song.replayGainAdjustment.run { track != null || album != null }
        val rgActive = playbackSettings.replayGainMode != ReplayGainMode.OFF

        if (!playbackSettings.normalizationEnabled || (hasRgTags && rgActive)) {
            L.d("VolumeNormalizer: bypass song=${song.uid} rgActive=$rgActive hasRg=$hasRgTags")
            shouldSkip = true
            currentSongUid = null
            pendingReset = true
            return
        }

        shouldSkip = false
        val uid = song.uid.toString()
        currentSongUid = uid

        val cached = memCache[uid]
        if (cached != null) {
            L.d("VolumeNormalizer: memory hit uid=$uid")
            pendingTargetGain = computeGainFromRmsDb(cached)
            pendingReset = true
            nextSong?.let { prefetchSong(it) }
            return
        }

        pendingReset = true

        scope.launch {
            val record = normalizationDao.getForSong(uid)
            withContext(Dispatchers.Main) {
                if (currentSongUid != uid) return@withContext
                if (record != null) {
                    L.d("VolumeNormalizer: DB hit uid=$uid rmsDb=${record.measuredRmsDb}")
                    memCache[uid] = record.measuredRmsDb
                    pendingTargetGain = computeGainFromRmsDb(record.measuredRmsDb)
                }
                nextSong?.let { prefetchSong(it) }
            }
        }
    }

    private fun prefetchSong(song: Song) {
        val uid = song.uid.toString()
        if (memCache.containsKey(uid)) return
        scope.launch {
            val record = normalizationDao.getForSong(uid)
            if (record != null) {
                withContext(Dispatchers.Main) { memCache[uid] = record.measuredRmsDb }
                L.d("VolumeNormalizer: prefetched uid=$uid")
            }
        }
    }

    /** Clears cache. Called from [AudioPreferenceFragment]. Must run on the main thread. */
    fun clearCache() {
        memCache.clear()
        scope.launch { normalizationDao.deleteAll() }
        L.d("VolumeNormalizer: cache cleared")
    }

    // ─── BaseAudioProcessor ───────────────────────────────────────────────────

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        windowSamples = (MEASUREMENT_SECONDS * sampleRate * channelCount).toLong()
        minSamples = (MIN_MEASUREMENT_SECONDS * sampleRate * channelCount).toLong()
        when (sampleRate) {
            44100 -> {
                kwStage1 = KW_STAGE1_44100
                kwStage2 = KW_STAGE2_44100
            }
            48000 -> {
                kwStage1 = KW_STAGE1_48000
                kwStage2 = KW_STAGE2_48000
            }
            else -> computeKWeightingCoeffs(sampleRate)
        }
        for (ch in filterState) for (stage in ch) stage.fill(0f)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val output = replaceOutputBuffer(limit - pos)

        if (pendingReset) {
            pendingReset = false
            handleResetInAudioThread()
        }

        val pending = pendingTargetGain
        if (pending != NO_PENDING) {
            pendingTargetGain = NO_PENDING
            targetGain = pending
            gainLocked = true
        }

        var i = pos
        while (i < limit) {
            val rawS = inputBuffer.getLeShort(i).toInt()
            val channel = (currentSampleIndex % channelCount).toInt()

            if (!gainLocked && !shouldSkip) {
                val filtered = applyKWeighting(rawS.toFloat(), channel)
                sumSquares += (filtered * filtered).toDouble()
                totalSamples++
                if (totalSamples >= windowSamples) lockGain()
            }

            currentGain += (targetGain - currentGain) * SMOOTHING_COEFF
            output.putLeShort(softLimit(rawS.toFloat() * currentGain).toShort())
            currentSampleIndex++
            i += 2
        }

        inputBuffer.position(limit)
        output.flip()
    }

    // ─── Audio thread private helpers ─────────────────────────────────────────

    private fun handleResetInAudioThread() {
        if (!gainLocked && !shouldSkip && totalSamples >= minSamples) {
            val uid = measuringUid
            if (uid != null) {
                val rms = sqrt(sumSquares / totalSamples.toDouble())
                if (rms >= SILENCE_THRESHOLD_RMS) {
                    val rmsDb = (20.0 * log10(rms / 32768.0)).toFloat()
                    val ts = System.currentTimeMillis()
                    scope.launch { normalizationDao.put(NormalizationRecord(uid, rmsDb, ts)) }
                    L.d("VolumeNormalizer: saved partial uid=$uid rmsDb=$rmsDb")
                }
            }
        }
        sumSquares = 0.0
        totalSamples = 0L
        gainLocked = false
        currentSampleIndex = 0L
        for (ch in filterState) for (stage in ch) stage.fill(0f)
        measuringUid = currentSongUid
        if (shouldSkip) targetGain = 1.0f
    }

    private fun lockGain() {
        if (totalSamples == 0L) return
        val rms = sqrt(sumSquares / totalSamples.toDouble())
        if (rms < SILENCE_THRESHOLD_RMS) {
            L.d("VolumeNormalizer: silence, extending measurement uid=$measuringUid")
            sumSquares = 0.0
            totalSamples = 0L
            return
        }
        val rmsDb = (20.0 * log10(rms / 32768.0)).toFloat()
        targetGain = computeGainFromRmsDb(rmsDb)
        gainLocked = true
        val uid = measuringUid ?: return
        val ts = System.currentTimeMillis()
        scope.launch { normalizationDao.put(NormalizationRecord(uid, rmsDb, ts)) }
        L.d("VolumeNormalizer: locked uid=$uid rmsDb=$rmsDb gain=$targetGain")
    }

    private fun applyKWeighting(x: Float, channel: Int): Float {
        val y1 = applyBiquad(x, channel, 0, kwStage1)
        return applyBiquad(y1, channel, 1, kwStage2)
    }

    private fun applyBiquad(x: Float, ch: Int, stage: Int, c: FloatArray): Float {
        val s = filterState[ch][stage]
        val y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
        s[1] = s[0]
        s[0] = x
        s[3] = s[2]
        s[2] = y
        return y
    }

    private fun softLimit(sample: Float): Int {
        val a = abs(sample)
        val sign = if (sample >= 0f) 1f else -1f
        val limited =
            if (a <= LIMITER_KNEE) {
                a
            } else {
                val excess = a - LIMITER_KNEE
                val headroom = PCM_MAX - LIMITER_KNEE
                LIMITER_KNEE + headroom * (1f - 1f / (1f + excess / headroom))
            }
        return (sign * limited.coerceAtMost(PCM_MAX)).toInt()
    }

    private fun computeGainFromRmsDb(measuredRmsDb: Float): Float {
        val gainDb = playbackSettings.normalizationTarget.targetRmsDb - measuredRmsDb
        return (10f.pow(gainDb / 20f)).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    private fun computeKWeightingCoeffs(sr: Int) {
        val fs = sr.toDouble()
        val fc1 = 1681.974450955533
        val g1 = 3.999843853973347
        val q1 = 0.7071752369554196
        val vh1 = 10.0.pow(g1 / 20.0)
        val vb1 = 10.0.pow(g1 / 40.0)
        val k1 = tan(PI * fc1 / fs)
        val den1 = vh1 + vb1 * k1 / q1 + k1 * k1
        val den1a = 1.0 + k1 / q1 + k1 * k1
        kwStage1 =
            floatArrayOf(
                ((vh1 + vb1 * k1 / q1 + k1 * k1) / den1).toFloat(),
                (2.0 * (k1 * k1 - vh1) / den1).toFloat(),
                ((vh1 - vb1 * k1 / q1 + k1 * k1) / den1).toFloat(),
                (2.0 * (k1 * k1 - 1.0) / den1a).toFloat(),
                ((1.0 - k1 / q1 + k1 * k1) / den1a).toFloat(),
            )
        val fc2 = 38.13547087602444
        val q2 = 0.5003270373238773
        val k2 = tan(PI * fc2 / fs)
        val den2 = k2 * k2 + k2 / q2 + 1.0
        kwStage2 =
            floatArrayOf(
                (1.0 / den2).toFloat(),
                (-2.0 / den2).toFloat(),
                (1.0 / den2).toFloat(),
                (2.0 * (k2 * k2 - 1.0) / den2).toFloat(),
                ((k2 * k2 - k2 / q2 + 1.0) / den2).toFloat(),
            )
        L.d("VolumeNormalizer: computed K-weighting coeffs for $sr Hz")
    }

    private fun ByteBuffer.getLeShort(at: Int) =
        get(at + 1).toInt().shl(8).or(get(at).toInt().and(0xFF)).toShort()

    private fun ByteBuffer.putLeShort(short: Short) {
        put(short.toByte())
        put(short.toInt().shr(8).toByte())
    }

    private companion object {
        const val MEASUREMENT_SECONDS = 15.0
        const val MIN_MEASUREMENT_SECONDS = 3.0
        const val SILENCE_THRESHOLD_RMS = 100.0
        const val SMOOTHING_COEFF = 0.0005f
        const val MAX_GAIN = 4.0f
        const val MIN_GAIN = 0.125f
        const val LIMITER_KNEE = 27_852f
        const val PCM_MAX = 32_767f
        val NO_PENDING = Float.MIN_VALUE

        val KW_STAGE1_44100 =
            floatArrayOf(1.53512486f, -2.69169619f, 1.19839281f, -1.69065929f, 0.73248077f)
        val KW_STAGE2_44100 =
            floatArrayOf(1.0f, -2.0f, 1.0f, -1.99004745f, 0.99007225f)
        val KW_STAGE1_48000 =
            floatArrayOf(1.53084123f, -2.65097995f, 1.16907868f, -1.66365511f, 0.71259543f)
        val KW_STAGE2_48000 =
            floatArrayOf(1.0f, -2.0f, 1.0f, -1.99219848f, 0.99225010f)
    }
}
