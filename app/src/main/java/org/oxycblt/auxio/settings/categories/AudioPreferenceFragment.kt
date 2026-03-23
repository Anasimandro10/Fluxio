/*
 * Copyright (c) 2023 Fluxio Project
 * AudioPreferenceFragment.kt is part of Fluxio.
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
package org.oxycblt.auxio.settings.categories

import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.normalizer.NormalizationScanner
import org.oxycblt.auxio.playback.normalizer.VolumeNormalizer
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.navigateSafe
import timber.log.Timber as L

/** Audio settings interface. */
@AndroidEntryPoint
class AudioPreferenceFragment : BasePreferenceFragment(R.xml.preferences_audio) {

    @Inject lateinit var volumeNormalizer: VolumeNormalizer
    @Inject lateinit var normalizationScanner: NormalizationScanner
    @Inject lateinit var musicRepository: MusicRepository

    override fun onStart() {
        super.onStart()
        // Observe scan progress and update the button summary in real time
        normalizationScanner.scanProgress
            .onEach { progress ->
                val pref =
                    findPreference<Preference>(getString(R.string.set_key_analyze_library))
                        ?: return@onEach
                if (progress == null) {
                    pref.summary = getString(R.string.set_analyze_library_desc)
                } else {
                    val eta = progress.estimatedSecondsRemaining
                    pref.summary =
                        if (eta != null && eta > 0) {
                            val min = eta / 60
                            val sec = eta % 60
                            getString(
                                R.string.set_analyze_library_progress_eta,
                                progress.analyzed,
                                progress.total,
                                min,
                                sec,
                            )
                        } else {
                            getString(
                                R.string.set_analyze_library_progress,
                                progress.analyzed,
                                progress.total,
                            )
                        }
                }
            }
            .launchIn(lifecycleScope)
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_pre_amp)) {
            L.d("Navigating to pre-amp dialog")
            findNavController().navigateSafe(AudioPreferenceFragmentDirections.preAmpSettings())
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            getString(R.string.set_key_clear_normalization_cache) -> {
                L.d("Clearing normalization cache")
                volumeNormalizer.clearCache()
                view?.let {
                    Snackbar.make(
                            it,
                            getString(R.string.set_clear_normalization_cache_done),
                            Snackbar.LENGTH_SHORT,
                        )
                        .show()
                }
                true
            }
            getString(R.string.set_key_analyze_library) -> {
                val songs = musicRepository.library?.songs
                if (songs.isNullOrEmpty()) {
                    view?.let {
                        Snackbar.make(
                                it,
                                getString(R.string.set_analyze_library_no_songs),
                                Snackbar.LENGTH_SHORT,
                            )
                            .show()
                    }
                } else if (normalizationScanner.scanProgress.value != null) {
                    normalizationScanner.cancelBulkScan()
                } else {
                    normalizationScanner.startBulkScan(songs)
                }
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }
}
