/*
 * Copyright (c) 2026 Fluxio Project
 * EqualizerViewModel.kt is part of Fluxio.
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

package org.oxycblt.auxio.playback.equalizer

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Exposes equalizer state to the UI and forwards changes to [EqualizerAudioProcessor]. */
@HiltViewModel
class EqualizerViewModel
@Inject
constructor(
    private val equalizerSettings: EqualizerSettings,
    private val equalizerProcessor: EqualizerAudioProcessor,
) : ViewModel() {

    private val _bands = MutableStateFlow(equalizerSettings.getBands())

    /** Current gain for each of the 10 bands (dB). */
    val bands: StateFlow<FloatArray> = _bands.asStateFlow()

    private val _activePreset = MutableStateFlow(equalizerSettings.activePreset)

    /** Active factory preset index, or [EqualizerSettings.PRESET_CUSTOM]. */
    val activePreset: StateFlow<Int> = _activePreset.asStateFlow()

    private val _enabled = MutableStateFlow(equalizerSettings.enabled)

    /** Whether the equalizer is currently on. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        // Restore persisted state into the audio processor on first creation.
        equalizerProcessor.setBands(_bands.value, _enabled.value)
    }

    /** Turns the equalizer on or off. */
    fun setEnabled(enabled: Boolean) {
        equalizerSettings.enabled = enabled
        _enabled.value = enabled
        equalizerProcessor.setBands(_bands.value, enabled)
    }

    /** Updates a single band. Marks the preset as custom. */
    fun setBand(index: Int, gainDb: Float) {
        val newBands = _bands.value.copyOf()
        newBands[index] = gainDb
        _bands.value = newBands
        equalizerSettings.saveBands(newBands)
        equalizerSettings.activePreset = EqualizerSettings.PRESET_CUSTOM
        _activePreset.value = EqualizerSettings.PRESET_CUSTOM
        equalizerProcessor.setBands(newBands, _enabled.value)
    }

    /** Applies a factory preset. Resets all bands to the preset values. */
    fun applyPreset(presetIndex: Int) {
        equalizerSettings.applyPreset(presetIndex)
        val newBands = equalizerSettings.getBands()
        _bands.value = newBands
        _activePreset.value = presetIndex
        equalizerProcessor.setBands(newBands, _enabled.value)
    }
}
