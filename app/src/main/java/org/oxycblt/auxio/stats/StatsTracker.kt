/*
 * Copyright (c) 2026 Fluxio Project
 * StatsTracker.kt is part of Fluxio.
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

package org.oxycblt.auxio.stats

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * Listens to playback and saves a [PlaybackRecord] when a song has been played for 30 seconds or
 * more. Attach it to [PlaybackStateManager] once at app startup.
 */
@Singleton
class StatsTracker
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val dao: PlaybackRecordDao,
) : PlaybackStateManager.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The song currently being tracked. */
    private var trackedSong: Song? = null

    /** Unix timestamp (ms) when playback of the current song started. */
    private var trackingStartMs: Long = 0L

    /** Accumulated seconds the user has actually been listening (pauses excluded). */
    private var secondsListened: Int = 0

    /** Running job that increments [secondsListened] every second while the song is playing. */
    private var tickJob: Job? = null

    /** Whether the player is currently advancing (actively producing audio). */
    private var isAdvancing: Boolean = false

    // -------------------------------------------------------------------------
    // PlaybackStateManager.Listener
    // -------------------------------------------------------------------------

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        val newSong = queue.getOrNull(index)
        switchTrackingTo(newSong)
    }

    override fun onIndexMoved(index: Int) {
        // We don't have direct queue access here — the manager will call onNewPlayback
        // for the next song, so nothing extra is needed.
    }

    override fun onProgressionChanged(progression: Progression) {
        val advancing = progression.isPlaying
        if (advancing == isAdvancing) return
        isAdvancing = advancing
        if (advancing) {
            startTick()
        } else {
            stopTick()
        }
    }

    override fun onSessionEnded() {
        commitIfEligible()
        resetTracking()
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Begin tracking a new song, committing the previous one if it qualifies. */
    private fun switchTrackingTo(song: Song?) {
        commitIfEligible()
        resetTracking()
        trackedSong = song ?: return
        trackingStartMs = System.currentTimeMillis()
    }

    /** Save the current record to the database if the user listened for >= 30 seconds. */
    private fun commitIfEligible() {
        val song = trackedSong ?: return
        if (secondsListened < MINIMUM_SECONDS) return
        val record =
            PlaybackRecord(
                songTitle = song.name.resolve(context),
                artistName = song.artists.firstOrNull()?.name?.resolve(context) ?: "",
                albumName = song.album.name.resolve(context),
                startedAt = trackingStartMs,
                secondsPlayed = secondsListened,
            )
        scope.launch { dao.insert(record) }
    }

    /** Reset all tracking state to zero, without committing. */
    private fun resetTracking() {
        stopTick()
        trackedSong = null
        trackingStartMs = 0L
        secondsListened = 0
        isAdvancing = false
    }

    /** Launch a coroutine that increments [secondsListened] every second. */
    private fun startTick() {
        if (tickJob?.isActive == true) return
        tickJob =
            scope.launch {
                while (true) {
                    delay(1_000)
                    secondsListened++
                }
            }
    }

    /** Cancel the tick coroutine. */
    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
    }

    private companion object {
        /** Minimum seconds listened before a play is recorded (Spotify / Last.fm standard). */
        const val MINIMUM_SECONDS = 30
    }
}