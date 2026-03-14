/*
 * Copyright (c) 2026 Fluxio Project
 * LyricsPreferenceFragment.kt is part of Fluxio.
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
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.lyrics.LyricsRepository
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference

/** Lyrics settings screen. */
@AndroidEntryPoint
class LyricsPreferenceFragment : BasePreferenceFragment(R.xml.preferences_lyrics) {

    @Inject lateinit var lyricsRepository: LyricsRepository

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        // No dialog preferences in this screen.
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == getString(R.string.set_key_lrclib_clear_cache)) {
            clearLrclibCache()
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun clearLrclibCache() {
        lifecycleScope.launch {
            val count = lyricsRepository.lrclibCacheCount()
            lyricsRepository.clearLrclibCache()
            val message =
                if (count > 0) {
                    "Cleared $count cached lyrics"
                } else {
                    "Lyrics cache is already empty"
                }
            view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
        }
    }
}