/*
 * Copyright (c) 2026 Fluxio Project
 * NormalizationScanner.kt is part of Fluxio.
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

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/** Manages the loudness analysis queue, adaptive workers, and bulk library scanning. */
@Singleton
class NormalizationScanner
@Inject
constructor(
    private val loudnessAnalyzer: LoudnessAnalyzer,
    private val normalizationDao: NormalizationDao,
) {
    /** Called on the main thread when a song finishes analyzing. Set by [VolumeNormalizer]. */
    var onSongAnalyzed: ((uid: String, rmsDb: Float) -> Unit)? = null

    // Never cancelled — @Singleton for the app's lifetime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Priority queues — guarded by synchronized(this)
    private val highPriority = ArrayDeque<Song>()
    private val lowPriority = ArrayDeque<Song>()
    private val inProgress = mutableSetOf<String>()

    // Worker management
    private val workerJobs = mutableListOf<Job>()
    private var currentWorkerCount = 0
    private var isPlaying = false

    // Bulk scan tracking
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()
    private var bulkTotal = 0
    private var bulkAnalyzed = 0
    private var bulkActive = false
    private val recentDurationsMs = ArrayDeque<Long>(5)

    // Channel to wake up idle workers when new songs are added
    private val workAvailable = Channel<Unit>(Channel.CONFLATED)

    /** Notify whether music is currently playing. Adjusts worker count. */
    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        adjustWorkers()
    }

    /**
     * Request high-priority analysis for [songs] (current + next 3 in queue).
     * These jump to the front of the queue. Called from main thread.
     */
    fun requestHighPriority(songs: List<Song>) {
        scope.launch {
            val toAdd = mutableListOf<Song>()
            for (song in songs) {
                val uid = song.uid.toString()
                if (normalizationDao.getForSong(uid) != null) continue
                synchronized(this@NormalizationScanner) {
                    if (
                        !inProgress.contains(uid) &&
                            !highPriority.any { it.uid.toString() == uid }
                    ) {
                        toAdd.add(song)
                    }
                }
            }
            if (toAdd.isNotEmpty()) {
                synchronized(this@NormalizationScanner) {
                    for (song in toAdd.reversed()) highPriority.addFirst(song)
                }
                workAvailable.trySend(Unit)
            }
        }
    }

    /**
     * Start bulk analysis of [songs] that don't have a cached result yet.
     * Called from main thread when user taps "Analyze entire library".
     */
    fun startBulkScan(songs: Collection<Song>) {
        scope.launch {
            val uids = songs.map { it.uid.toString() }.toSet()
            val cached = mutableSetOf<String>()
            for (uid in uids) {
                if (normalizationDao.getForSong(uid) != null) cached.add(uid)
            }
            val toScan = songs.filter { it.uid.toString() !in cached }
            if (toScan.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _scanProgress.value = ScanProgress(songs.size, songs.size, 0)
                }
                return@launch
            }

            synchronized(this@NormalizationScanner) {
                lowPriority.clear()
                lowPriority.addAll(toScan)
                bulkTotal = toScan.size
                bulkAnalyzed = 0
                bulkActive = true
                recentDurationsMs.clear()
            }

            withContext(Dispatchers.Main) {
                _scanProgress.value = ScanProgress(0, toScan.size, null)
            }

            adjustWorkers()
            workAvailable.trySend(Unit)
            L.d("NormalizationScanner: bulk scan started, ${toScan.size} songs")
        }
    }

    /** Cancels any ongoing bulk scan. High-priority analysis is not affected. */
    fun cancelBulkScan() {
        synchronized(this@NormalizationScanner) {
            lowPriority.clear()
            bulkActive = false
        }
        scope.launch { withContext(Dispatchers.Main) { _scanProgress.value = null } }
        L.d("NormalizationScanner: bulk scan cancelled")
    }

    // ─── Worker management ────────────────────────────────────────────────────

    private fun workerCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val base =
            when {
                cores <= 4 -> 1
                cores <= 7 -> 2
                else -> 3
            }
        return if (isPlaying) maxOf(1, base - 1) else base
    }

    private fun adjustWorkers() {
        val desired = workerCount()
        if (desired == currentWorkerCount) return
        if (desired > currentWorkerCount) {
            repeat(desired - currentWorkerCount) {
                workerJobs.add(scope.launch { workerLoop() })
            }
        } else {
            val toRemove = currentWorkerCount - desired
            repeat(toRemove) { workerJobs.removeLastOrNull()?.cancel() }
        }
        currentWorkerCount = desired
    }

    private suspend fun workerLoop() {
        while (isActive) {
            val song =
                nextSong()
                    ?: run {
                        workAvailable.receive()
                        return@run null
                    }
                    ?: continue

            val uid = song.uid.toString()
            val startMs = System.currentTimeMillis()
            val rmsDb = loudnessAnalyzer.analyze(song)
            val elapsedMs = System.currentTimeMillis() - startMs

            synchronized(this@NormalizationScanner) { inProgress.remove(uid) }

            if (rmsDb != null) {
                normalizationDao.put(
                    NormalizationRecord(uid, rmsDb, System.currentTimeMillis())
                )
                L.d("NormalizationScanner: analyzed uid=$uid rmsDb=$rmsDb (${elapsedMs}ms)")

                val wasBulk =
                    synchronized(this@NormalizationScanner) {
                        if (bulkActive) {
                            bulkAnalyzed++
                            if (recentDurationsMs.size >= 5) recentDurationsMs.removeFirst()
                            recentDurationsMs.addLast(elapsedMs)
                            true
                        } else false
                    }

                if (wasBulk) updateBulkProgress()

                withContext(Dispatchers.Main) { onSongAnalyzed?.invoke(uid, rmsDb) }
            }
        }
    }

    private fun nextSong(): Song? {
        synchronized(this) {
            if (highPriority.isNotEmpty()) {
                val song = highPriority.removeFirst()
                inProgress.add(song.uid.toString())
                return song
            }
            if (lowPriority.isNotEmpty()) {
                val song = lowPriority.removeFirst()
                inProgress.add(song.uid.toString())
                return song
            }
            return null
        }
    }

    private suspend fun updateBulkProgress() {
        val (analyzed, total, eta) =
            synchronized(this) {
                val avgMs =
                    if (recentDurationsMs.isNotEmpty()) recentDurationsMs.average().toLong()
                    else 0L
                val remaining = maxOf(0, bulkTotal - bulkAnalyzed)
                val etaSec =
                    if (avgMs > 0) ((remaining * avgMs) / (1000L * workerCount())).toInt()
                    else null
                Triple(bulkAnalyzed, bulkTotal, etaSec)
            }
        withContext(Dispatchers.Main) {
            _scanProgress.value =
                if (analyzed >= total) null else ScanProgress(analyzed, total, eta)
        }
    }
}

/** Progress state for the bulk library scan. */
data class ScanProgress(
    val analyzed: Int,
    val total: Int,
    val estimatedSecondsRemaining: Int?,
)
