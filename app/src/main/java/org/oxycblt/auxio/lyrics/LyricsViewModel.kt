/*
 * Copyright (c) 2026 Fluxio Project
 * LyricsViewModel.kt is part of Fluxio.
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
package org.oxycblt.auxio.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Exposes lyrics to the UI, synchronized with the current playback position when synced.
 *
 * Observes [PlaybackStateManager] for song and position changes, loads lyrics via
 * [LyricsRepository], and emits the index of the currently active line via [currentLineIndex].
 *
 * For plain-text lyrics (isSynced = false), [currentLineIndex] is always -1 — the UI shows all
 * lines at full opacity.
 *
 * On every queue change, the next song in the queue is prefetched in the background so its lyrics
 * are ready the moment it starts playing.
 *
 * Sync strategy:
 * - [updateCurrentLine] uses binary search (O(log n)) instead of a linear scan.
 * - The ticker is always cancelled and restarted on every [onProgressionChanged] call while
 *   playing. This ensures the wakeup deadline is recalculated after any seek, preventing the
 *   highlight from sticking on the wrong line for several seconds after the user scrubs.
 */
@HiltViewModel
class LyricsViewModel
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val lyricsRepository: LyricsRepository,
    private val lyricsSettings: LyricsSettings,
) : ViewModel(), PlaybackStateManager.Listener, LyricsSettings.Listener {

    private val _lines = MutableStateFlow<List<LrcLine>>(emptyList())

    /** The full list of lyric lines for the current song. Empty if no lyrics were found. */
    val lines: StateFlow<List<LrcLine>>
        get() = _lines

    private val _isSynced = MutableStateFlow(true)

    /**
     * True when the current lyrics have timestamps. False for plain text. The UI uses this to show
     * all lines at full opacity instead of dimming inactive ones.
     */
    val isSynced: StateFlow<Boolean>
        get() = _isSynced

    private val _currentLineIndex = MutableStateFlow(-1)

    /** Index of the line that should be highlighted right now. -1 means none. */
    val currentLineIndex: StateFlow<Int>
        get() = _currentLineIndex

    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var tickerJob: Job? = null
    private var currentProgression: Progression? = null

    init {
        playbackManager.addListener(this)
        lyricsSettings.registerListener(this)
    }

    override fun onCleared() {
        playbackManager.removeListener(this)
        lyricsSettings.unregisterListener(this)
        stopTicker()
    }

    // -------------------------------------------------------------------------
    // LyricsSettings.Listener
    // -------------------------------------------------------------------------

    /**
     * LRCLIB was enabled or disabled.
     *
     * When enabled: clear "no result" entries from Room so previously missed songs get a fresh
     * search, clear the memory cache, and reload the current song immediately.
     *
     * When disabled: just clear the memory cache and reload so the current song stops showing
     * LRCLIB lyrics if it had them.
     */
    override fun onLrclibEnabledChanged() {
        L.d("lrclibEnabled changed — refreshing lyrics")
        viewModelScope.launch {
            if (lyricsSettings.lrclibEnabled) {
                // LRCLIB just turned ON: clear stale "no result" entries from Room
                // so songs that previously returned nothing get another chance.
                lyricsRepository.clearNoResultsCache()
            }
            lyricsRepository.clearMemoryCache()
            reloadCurrentSong()
        }
    }

    /**
     * The prefer-synced option changed. Clear the memory cache and reload the current song so the
     * new lookup order takes effect immediately.
     */
    override fun onLrclibPreferSyncedChanged() {
        L.d("lrclibPreferSynced changed — refreshing lyrics")
        lyricsRepository.clearMemoryCache()
        reloadCurrentSong()
    }

    /**
     * Reloads lyrics for the song that is currently playing, if any. Called after settings change
     * so the new configuration takes effect immediately without the user having to skip to the next
     * song.
     */
    private fun reloadCurrentSong() {
        val song = playbackManager.currentSong ?: return
        L.d("Reloading lyrics for current song after settings change: ${song.path.name}")
        loadLyricsFor(song)
    }

    // -------------------------------------------------------------------------
    // PlaybackStateManager.Listener
    // -------------------------------------------------------------------------

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        loadLyricsFor(queue.getOrNull(index))
        prefetchNext(queue, index)
    }

    override fun onIndexMoved(index: Int) {
        val queue = playbackManager.queue
        loadLyricsFor(queue.getOrNull(index))
        prefetchNext(queue, index)
    }

    /**
     * Called whenever the playback position or play/pause state changes, including after a seek.
     *
     * The ticker is always restarted here when playing so that its next wakeup deadline is
     * calculated from the new position — this prevents the highlight from staying on the wrong line
     * after the user scrubs forward or backward.
     */
    override fun onProgressionChanged(progression: Progression) {
        currentProgression = progression
        updateCurrentLine(progression.calculateElapsedPositionMs())
        if (progression.isPlaying && _lines.value.isNotEmpty() && _isSynced.value) {
            // Always restart: this recalculates the wakeup time from the current position,
            // which may have jumped significantly if the user just seeked.
            stopTicker()
            startTicker()
        } else if (!progression.isPlaying) {
            stopTicker()
        }
    }

    override fun onSessionEnded() {
        stopTicker()
        prefetchJob?.cancel()
        currentProgression = null
        _lines.value = emptyList()
        _isSynced.value = true
        _currentLineIndex.value = -1
    }

    // -------------------------------------------------------------------------
    // Lyrics loading
    // -------------------------------------------------------------------------

    private fun loadLyricsFor(song: Song?) {
        loadJob?.cancel()
        stopTicker()
        _lines.value = emptyList()
        _isSynced.value = true
        _currentLineIndex.value = -1

        if (song == null) return

        loadJob =
            viewModelScope.launch {
                L.d("Loading lyrics for ${song.path.name}")
                val result = lyricsRepository.loadLyrics(song)
                if (result != null) {
                    L.d("Lyrics loaded: ${result.lines.size} lines synced=${result.isSynced}")
                    _lines.value = result.lines
                    _isSynced.value = result.isSynced
                    if (result.isSynced) {
                        val posMs =
                            currentProgression?.calculateElapsedPositionMs()
                                ?: playbackManager.progression.calculateElapsedPositionMs()
                        updateCurrentLine(posMs)
                        val prog = currentProgression ?: playbackManager.progression
                        if (prog.isPlaying) startTicker()
                    }
                }
            }
    }

    /**
     * Kicks off a low-priority background prefetch for the next song in the queue. Runs after a
     * short delay so it doesn't compete with the current song's load.
     */
    private fun prefetchNext(queue: List<Song>, currentIndex: Int) {
        prefetchJob?.cancel()
        val nextSong = queue.getOrNull(currentIndex + 1) ?: return
        prefetchJob =
            viewModelScope.launch {
                delay(500) // Let the current song's load finish first
                lyricsRepository.prefetch(nextSong)
            }
    }

    // -------------------------------------------------------------------------
    // Sync ticker
    // -------------------------------------------------------------------------

    /**
     * Starts the sync ticker. Always creates a fresh job — callers must call [stopTicker] first if
     * a ticker might already be running.
     */
    private fun startTicker() {
        tickerJob =
            viewModelScope.launch(Dispatchers.Default) {
                while (true) {
                    val posMs = currentProgression?.calculateElapsedPositionMs() ?: break
                    updateCurrentLine(posMs)
                    // Sleep until just before the next line's timestamp so the highlight
                    // lands as close as possible to the correct moment.
                    val lines = _lines.value
                    val nextLineMs = lines.firstOrNull { it.startMs > posMs }?.startMs
                    val delayMs =
                        if (nextLineMs != null) {
                            // Wake 50ms early: tight enough for accurate highlighting,
                            // generous enough to absorb scheduler jitter on slow devices.
                            (nextLineMs - posMs - 50L).coerceIn(20L, 2000L)
                        } else {
                            // No next line — check every 2 seconds.
                            2000L
                        }
                    delay(delayMs)
                }
            }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * Finds the index of the active lyric line for [posMs] using binary search (O(log n)).
     *
     * The active line is the last one whose [LrcLine.startMs] is ≤ [posMs]. If that line is a
     * silence marker ([LrcLine.isSilence] = true), -1 is returned so the UI turns off the
     * highlight.
     *
     * Emits to [currentLineIndex] only when the value actually changes to avoid triggering
     * unnecessary recompositions or adapter updates.
     */
    private fun updateCurrentLine(posMs: Long) {
        if (!_isSynced.value) return
        val snapshot = _lines.value
        if (snapshot.isEmpty()) return

        // Binary search: find the rightmost line with startMs <= posMs.
        var lo = 0
        var hi = snapshot.size - 1
        var active = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (snapshot[mid].startMs <= posMs) {
                active = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        val reported = if (active >= 0 && snapshot[active].isSilence) -1 else active
        if (_currentLineIndex.value != reported) _currentLineIndex.value = reported
    }
}
