/*
 * Copyright (c) 2026 Fluxio Project
 * SleepTimerViewModel.kt is part of Fluxio.
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

package org.oxycblt.auxio.playback.sleeptimer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that manages the sleep timer countdown.
 *
 * The timer counts down in seconds and exposes the remaining time. When it reaches zero,
 * [timerFired] becomes true so that the playback layer knows to stop after the current song.
 */
@HiltViewModel
class SleepTimerViewModel @Inject constructor() : ViewModel() {

    private val _remainingMs = MutableStateFlow<Long?>(null)

    /** Remaining milliseconds, or null if the timer is not active. */
    val remainingMs: StateFlow<Long?> = _remainingMs

    private val _timerFired = MutableStateFlow(false)

    /** True when the countdown has reached zero. Reset automatically when timer is cancelled. */
    val timerFired: StateFlow<Boolean> = _timerFired

    private var countdownJob: Job? = null

    /** Start the timer with the given duration in minutes. */
    fun startTimer(minutes: Int) {
        cancelTimer()
        val totalMs = minutes * 60_000L
        _remainingMs.value = totalMs
        _timerFired.value = false
        countdownJob =
            viewModelScope.launch {
                var remaining = totalMs
                while (remaining > 0) {
                    delay(1_000L)
                    remaining -= 1_000L
                    _remainingMs.value = remaining.coerceAtLeast(0L)
                }
                _timerFired.value = true
            }
    }

    /** Cancel the active timer. */
    fun cancelTimer() {
        countdownJob?.cancel()
        countdownJob = null
        _remainingMs.value = null
        _timerFired.value = false
    }

    /** Called by playback layer after it has handled the fired timer (stopped after song end). */
    fun acknowledgeTimerFired() {
        _timerFired.value = false
        _remainingMs.value = null
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}