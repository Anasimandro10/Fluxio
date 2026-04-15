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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** UI state for the full AutoEQ profile index download. */
sealed class AllProfilesState {
    /** Not yet requested. */
    object Idle : AllProfilesState()

    /** Download in progress. */
    object Loading : AllProfilesState()

    /** Full list available for local filtering. */
    data class Ready(val profiles: List<AutoEqResult>) : AllProfilesState()

    /** Network error — index could not be downloaded. */
    object Error : AllProfilesState()
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

    private val _recentProfiles = MutableStateFlow<List<AutoEqResult>>(emptyList())
    val recentProfiles: StateFlow<List<AutoEqResult>> = _recentProfiles

    private val _allProfilesState = MutableStateFlow<AllProfilesState>(AllProfilesState.Idle)
    val allProfilesState: StateFlow<AllProfilesState> = _allProfilesState

    private val _autoEqProfileName = MutableStateFlow(autoEqRepository.getCachedHeadphoneName())
    val autoEqProfileName: StateFlow<String?> = _autoEqProfileName

    private val _isModifiedFromProfile = MutableStateFlow(false)
    val isModifiedFromProfile: StateFlow<Boolean> = _isModifiedFromProfile

    private val _isApplyingProfile = MutableStateFlow(false)
    val isApplyingProfile: StateFlow<Boolean> = _isApplyingProfile

    init {
        equalizerProcessor.setBands(_bands.value, _enabled.value)
        _recentProfiles.value = autoEqRepository.getRecentProfiles()
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
        autoEqRepository.clearProfileCache()
    }

    fun refreshFromSettings() {
        val bands = equalizerSettings.getBands()
        val preset = equalizerSettings.activePreset
        val isEnabled = equalizerSettings.enabled
        _bands.value = bands
        _activePreset.value = preset
        _enabled.value = isEnabled
        equalizerProcessor.setBands(bands, isEnabled)
        if (preset != EqualizerSettings.PRESET_CUSTOM) {
            _autoEqProfileName.value = null
            _isModifiedFromProfile.value = false
        }
    }

    // ---- All-profiles index ----

    /**
     * Downloads the complete AutoEQ profile index if not already cached. Safe to call multiple
     * times — subsequent calls while [AllProfilesState.Ready] are no-ops.
     */
    fun loadAllProfiles() {
        if (_allProfilesState.value is AllProfilesState.Ready) return
        viewModelScope.launch {
            _allProfilesState.value = AllProfilesState.Loading
            val list = autoEqRepository.loadAllProfiles()
            _allProfilesState.value =
                if (list != null) AllProfilesState.Ready(list) else AllProfilesState.Error
        }
    }

    /**
     * Filters the in-memory profile list by [query] (case-insensitive match on name and source).
     * Returns the first [MAX_DISPLAY] matches sorted by relevance (starts-with first). If [query]
     * is blank returns the first [MAX_DISPLAY] profiles alphabetically. Returns an empty list if
     * the index is not yet ready.
     */
    fun filterProfiles(query: String): List<AutoEqResult> {
        val state = _allProfilesState.value
        if (state !is AllProfilesState.Ready) return emptyList()
        val q = query.trim()
        return if (q.isBlank()) {
            state.profiles.take(MAX_DISPLAY)
        } else {
            state.profiles
                .filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.source.contains(q, ignoreCase = true)
                }
                .sortedBy { if (it.name.startsWith(q, ignoreCase = true)) 0 else 1 }
                .take(MAX_DISPLAY)
        }
    }

    fun applyAutoEqProfile(result: AutoEqResult) {
        viewModelScope.launch {
            _isApplyingProfile.value = true
            val gains = autoEqRepository.fetchProfile(result)
            if (gains != null) {
                if (!_enabled.value) {
                    _enabled.value = true
                    equalizerSettings.enabled = true
                }
                _bands.value = gains
                _activePreset.value = EqualizerSettings.PRESET_CUSTOM
                equalizerSettings.activePreset = EqualizerSettings.PRESET_CUSTOM
                equalizerSettings.saveBands(gains)
                equalizerProcessor.setBands(gains, true)
                _autoEqProfileName.value = result.name
                _isModifiedFromProfile.value = false
            }
            _isApplyingProfile.value = false
            _recentProfiles.value = autoEqRepository.getRecentProfiles()
        }
    }

    companion object {
        /** Maximum number of results shown at once to avoid rendering thousands of buttons. */
        private const val MAX_DISPLAY = 30
    }
}
