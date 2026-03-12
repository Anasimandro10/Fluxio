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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Exposes LRC lyrics to the UI, synchronized with the current playback position.
 *
 * Observes [PlaybackStateManager] for song and position changes, loads the matching .lrc file via
 * [LyricsRepository], and emits the index of the currently active line via [currentLineIndex].
 */
@HiltViewModel
class LyricsViewModel
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val lyricsRepository: LyricsRepository,
) : ViewModel(), PlaybackStateManager.Listener {

    private val _lines = MutableStateFlow<List<LrcLine>>(emptyList())

    /** The full list of lyric lines for the current song. Empty if no LRC was found. */
    val lines: StateFlow<List<LrcLine>>
        get() = _lines

    private val _currentLineIndex = MutableStateFlow(-1)

    /** Index of the line that should be highlighted right now. -1 means none. */
    val currentLineIndex: StateFlow<Int>
        get() = _currentLineIndex

    private var loadJob: Job? = null

    init {
        playbackManager.addListener(this)
    }

    override fun onCleared() {
        playbackManager.removeListener(this)
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        val song = queue.getOrNull(index)
        loadLyricsFor(song)
    }

    override fun onIndexMoved(index: Int) {
        loadLyricsFor(playbackManager.currentSong)
    }

    override fun onProgressionChanged(progression: Progression) {
        val posMs = progression.calculateElapsedPositionMs()
        updateCurrentLine(posMs)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Cancels any pending load and starts a new one for the given song. */
    private fun loadLyricsFor(song: Song?) {
        loadJob?.cancel()
        _lines.value = emptyList()
        _currentLineIndex.value = -1

        if (song == null) return

        loadJob =
            viewModelScope.launch {
                L.d("Loading LRC for ${song.path.name}")
                val loaded = lyricsRepository.loadLrc(song)
                if (loaded != null) {
                    L.d("LRC loaded: ${loaded.size} lines")
                    _lines.value = loaded
                    // Sync immediately with current position
                    updateCurrentLine(playbackManager.progression.calculateElapsedPositionMs())
                } else {
                    L.d("No LRC found for ${song.path.name}")
                }
            }
    }

    /**
     * Finds the index of the line whose timestamp is <= posMs and the next line's timestamp is >
     * posMs. This is the line that should be highlighted.
     */
    private fun updateCurrentLine(posMs: Long) {
        val linesSnapshot = _lines.value
        if (linesSnapshot.isEmpty()) return

        var active = -1
        for (i in linesSnapshot.indices) {
            if (linesSnapshot[i].startMs <= posMs) {
                active = i
            } else {
                break
            }
        }

        if (_currentLineIndex.value != active) {
            _currentLineIndex.value = active
        }
    }
}
