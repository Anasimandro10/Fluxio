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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    var onSongAnalyzed: ((uid: String, rmsDb: Float) -> Unit)? = null

    // Never cancelled — @Singleton
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Queues ────────────────────────────────────────────────────────────────
    // highPriority: LinkedHashSet for O(1) contains() + FIFO order
    // lowPriority:  ArrayDeque — append/remove front only, no membership checks
    private val highPriority = LinkedHashSet<Song>()
    private val lowPriority = ArrayDeque<Song>()
    private val inProgress = HashSet<String>()

    // ── Worker management ─────────────────────────────────────────────────────
    private val workerJobs = mutableListOf<Job>()
    private var currentWorkerCount = 0
    private var isPlaying = false

    // ── Bulk scan state ───────────────────────────────────────────────────────
    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()
    private var bulkTotal = 0
    private var bulkAnalyzed = 0
    private var bulkActive = false
    private val recentDurationsMs = ArrayDeque<Long>(10)

    // ── Pending batch insert ──────────────────────────────────────────────────
    // Accumulates results and flushes every BATCH_SIZE records — reduces DB
    // transactions from N to N/BATCH_SIZE (10x fewer writes).
    private val pendingRecords = mutableListOf<NormalizationRecord>()

    private val workAvailable = Channel<Unit>(Channel.CONFLATED)

    // ─── Public API ───────────────────────────────────────────────────────────

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        adjustWorkers()
    }

    fun requestHighPriority(songs: List<Song>) {
        if (songs.isEmpty()) return
        scope.launch {
            val uids = songs.map { it.uid.toString() }
            val cached = normalizationDao.getUidsIn(uids).toHashSet()
            val toAdd = mutableListOf<Song>()
            for (song in songs) {
                val uid = song.uid.toString()
                if (uid in cached) continue
                synchronized(this@NormalizationScanner) {
                    if (uid !in inProgress && song !in highPriority) toAdd.add(song)
                }
            }
            if (toAdd.isNotEmpty()) {
                synchronized(this@NormalizationScanner) { highPriority.addAll(toAdd) }
                adjustWorkers()
                workAvailable.trySend(Unit)
            }
        }
    }

    fun startBulkScan(songs: Collection<Song>) {
        scope.launch {
            val allUids = songs.map { it.uid.toString() }
            val cached = normalizationDao.getUidsIn(allUids).toHashSet()
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

    fun cancelBulkScan() {
        synchronized(this@NormalizationScanner) {
            lowPriority.clear()
            bulkActive = false
        }
        flushPendingRecords()
        scope.launch { withContext(Dispatchers.Main) { _scanProgress.value = null } }
        L.d("NormalizationScanner: bulk scan cancelled")
    }

    /**
     * Suspends until the bulk scan completes (progress becomes null).
     * Called by [NormalizationWorker] to keep the WorkManager worker alive until done.
     */
    suspend fun awaitBulkScanComplete() {
        scanProgress.first { it == null }
    }

    // ─── Worker management ────────────────────────────────────────────────────

    private fun workerCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // Reserve at least 2 cores for system + playback.
        // UFS storage throughput caps at ~3-4 parallel readers on typical hardware.
        val base = when {
            cores <= 4 -> 1   // low-end: 1 worker to avoid thermal throttling
            cores <= 7 -> 2   // mid-range
            else -> 4          // high-end (8+ cores): 4 workers — I/O bound, not CPU bound
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
        while (currentCoroutineContext().isActive) {
            val song = nextSong() ?: run {
                workAvailable.receive()
                return@run null
            } ?: continue

            val uid = song.uid.toString()
            val startMs = System.currentTimeMillis()
            val rmsDb = loudnessAnalyzer.analyze(song)
            val elapsedMs = System.currentTimeMillis() - startMs

            synchronized(this@NormalizationScanner) { inProgress.remove(uid) }

            if (rmsDb != null) {
                L.d("NormalizationScanner: analyzed uid=$uid rmsDb=$rmsDb (${elapsedMs}ms)")

                val record = NormalizationRecord(uid, rmsDb, System.currentTimeMillis())

                val wasBulk = synchronized(this@NormalizationScanner) {
                    if (bulkActive) {
                        // Batch insert: accumulate records, flush every BATCH_SIZE
                        pendingRecords.add(record)
                        bulkAnalyzed++
                        if (recentDurationsMs.size >= 10) recentDurationsMs.removeFirst()
                        recentDurationsMs.addLast(elapsedMs)
                        pendingRecords.size >= BATCH_SIZE
                    } else {
                        // High-priority songs: write immediately (user needs them now)
                        false
                    }
                }

                if (wasBulk) {
                    flushPendingRecords()
                    updateBulkProgress()
                } else if (!synchronized(this@NormalizationScanner) { bulkActive }) {
                    // Not in bulk scan — write single record immediately
                    normalizationDao.put(record)
                    updateBulkProgress()
                }

                withContext(Dispatchers.Main) { onSongAnalyzed?.invoke(uid, rmsDb) }
            }
        }
    }

    private fun flushPendingRecords() {
        val toFlush = synchronized(this@NormalizationScanner) {
            if (pendingRecords.isEmpty()) return
            val copy = pendingRecords.toList()
            pendingRecords.clear()
            copy
        }
        scope.launch {
            normalizationDao.putAll(toFlush)
            L.d("NormalizationScanner: flushed ${toFlush.size} records to DB")
        }
    }

    private fun nextSong(): Song? {
        synchronized(this) {
            if (highPriority.isNotEmpty()) {
                val song = highPriority.iterator().next()
                highPriority.remove(song)
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
        val (analyzed, total, eta) = synchronized(this) {
            val avgMs =
                if (recentDurationsMs.isNotEmpty()) recentDurationsMs.average().toLong() else 0L
            val remaining = maxOf(0, bulkTotal - bulkAnalyzed)
            val etaSec =
                if (avgMs > 0) ((remaining * avgMs) / (1000L * workerCount())).toInt() else null
            Triple(bulkAnalyzed, bulkTotal, etaSec)
        }
        withContext(Dispatchers.Main) {
            _scanProgress.value =
                if (analyzed >= total) {
                    flushPendingRecords()  // flush any remaining records when scan completes
                    null
                } else {
                    ScanProgress(analyzed, total, eta)
                }
        }
    }

    private companion object {
        // Number of analysis results to accumulate before a single DB transaction.
        // Reduces DB writes from N to N/10 during bulk scans.
        const val BATCH_SIZE = 10
    }
}

data class ScanProgress(
    val analyzed: Int,
    val total: Int,
    val estimatedSecondsRemaining: Int?,
)
