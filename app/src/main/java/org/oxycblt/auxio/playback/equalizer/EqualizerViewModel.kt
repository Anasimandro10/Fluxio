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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** UI state for the AutoEQ headphone search. */
sealed class AutoEqSearchState {
    /** No query entered yet, or results were cleared. */
    object Idle : AutoEqSearchState()

    /** Query entered; waiting for results (debounce, index download, or local search). */
    object Loading : AutoEqSearchState()

    /** Results ready to display. */
    data class Results(val items: List<AutoEqResult>) : AutoEqSearchState()

    /** Query returned no matching headphones. */
    object NoResults : AutoEqSearchState()

    /** The headphone index could not be loaded (network error, no cache). */
    object Error : AutoEqSearchState()
}

@HiltViewModel
class EqualizerViewModel
@Inject
constructor(
    private val equalizerSettings: EqualizerSettings,
    private val equalizerProcessor: EqualizerAudioProcessor,
    private val autoEqRepository: AutoEqRepository,
) : ViewModel() {

    private val _bands = MutableStateFlow(equalizerSettings.getBands())
    val bands: StateFlow<FloatArray> = _bands

    private val _activePreset = MutableStateFlow(equalizerSettings.activePreset)
    val activePreset: StateFlow<Int> = _activePreset

    private val _enabled = MutableStateFlow(equalizerSettings.enabled)
    val enabled: StateFlow<Boolean> = _enabled

    /** Unified search state — replaces the old searchResults + isSearching flows. */
    private val _searchState = MutableStateFlow<AutoEqSearchState>(AutoEqSearchState.Idle)
    val searchState: StateFlow<AutoEqSearchState> = _searchState

    private val _autoEqProfileName = MutableStateFlow(autoEqRepository.getCachedHeadphoneName())
    val autoEqProfileName: StateFlow<String?> = _autoEqProfileName

    private val _isModifiedFromProfile = MutableStateFlow(false)
    val isModifiedFromProfile: StateFlow<Boolean> = _isModifiedFromProfile

    /** True while a selected profile is being downloaded and applied. */
    private val _isApplyingProfile = MutableStateFlow(false)
    val isApplyingProfile: StateFlow<Boolean> = _isApplyingProfile

    private var searchJob: Job? = null

    init {
        equalizerProcessor.setBands(_bands.value, _enabled.value)
        // Pre-load the headphone index in the background so it is ready when the user types.
        viewModelScope.launch(Dispatchers.IO) { autoEqRepository.ensureIndexLoaded() }
    }

    // ---- EQ controls ----

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        equalizerSettings.enabled = enabled
        equalizerProcessor.setBands(_bands.value, enabled)
    }

    fun setBand(index: Int, gainDb: Float) {
        val current = _bands.value.copyOf()
        current[index] = gainDb
        _bands.value = current
        _activePreset.value = EqualizerSettings.PRESET_CUSTOM
        equalizerSettings.activePreset = EqualizerSettings.PRESET_CUSTOM
        equalizerSettings.saveBands(current)
        equalizerProcessor.setBands(current, _enabled.value)
        if (_autoEqProfileName.value != null) {
            _isModifiedFromProfile.value = true
        }
    }

    fun applyPreset(presetIndex: Int) {
        val gains = EqualizerSettings.PRESETS[presetIndex].copyOf()
        _bands.value = gains
        _activePreset.value = presetIndex
        _autoEqProfileName.value = null
        _isModifiedFromProfile.value = false
        equalizerSettings.applyPreset(presetIndex)
        equalizerProcessor.setBands(gains, _enabled.value)
        // Clear the AutoEQ cache so the headphone name does not reappear after an app restart.
        autoEqRepository.clearProfileCache()
    }

    /**
     * Re-reads all EQ state from [EqualizerSettings] and pushes it to the UI flows. Called by
     * [EqualizerFragment] when it enters the STARTED state, so changes applied automatically by
     * [AudioDeviceListener] (while the screen was off or in the background) are reflected
     * immediately when the user opens the EQ screen.
     *
     * If [AudioDeviceListener] applied a factory preset while the screen was closed, the AutoEQ
     * profile label is also cleared so the UI does not show a stale headphone name.
     */
    fun refreshFromSettings() {
        val bands = equalizerSettings.getBands()
        val preset = equalizerSettings.activePreset
        val isEnabled = equalizerSettings.enabled
        _bands.value = bands
        _activePreset.value = preset
        _enabled.value = isEnabled
        equalizerProcessor.setBands(bands, isEnabled)
        // A factory preset means AutoEQ is no longer active — clear the profile label.
        if (preset != EqualizerSettings.PRESET_CUSTOM) {
            _autoEqProfileName.value = null
            _isModifiedFromProfile.value = false
        }
    }

    // ---- AutoEQ search ----

    /**
     * Called whenever the search field text changes. Triggers a local search after a 300 ms
     * debounce. Clears results immediately if [query] is blank or fewer than 2 characters.
     */
    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _searchState.value = AutoEqSearchState.Idle
            return
        }
        searchJob =
            viewModelScope.launch {
                _searchState.value = AutoEqSearchState.Loading
                delay(DEBOUNCE_MS)
                val loaded = autoEqRepository.ensureIndexLoaded()
                if (!loaded) {
                    _searchState.value = AutoEqSearchState.Error
                    return@launch
                }
                val results = autoEqRepository.searchLocal(trimmed)
                _searchState.value =
                    if (results.isEmpty()) AutoEqSearchState.NoResults
                    else AutoEqSearchState.Results(results)
            }
    }

    /** Clears search results and resets to idle state. */
    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = AutoEqSearchState.Idle
    }

    /**
     * Downloads and applies the EQ profile for [result]. Disables interaction during download via
     * [isApplyingProfile].
     */
    fun applyAutoEqProfile(result: AutoEqResult) {
        viewModelScope.launch(Dispatchers.IO) {
            _isApplyingProfile.value = true
            _searchState.value = AutoEqSearchState.Idle
            val gains = autoEqRepository.fetchProfile(result)
            if (gains != null) {
                _bands.value = gains
                _activePreset.value = EqualizerSettings.PRESET_CUSTOM
                equalizerSettings.activePreset = EqualizerSettings.PRESET_CUSTOM
                equalizerSettings.saveBands(gains)
                equalizerProcessor.setBands(gains, _enabled.value)
                _autoEqProfileName.value = result.name
                _isModifiedFromProfile.value = false
            }
            _isApplyingProfile.value = false
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
