/*
 * Copyright (c) 2026 Fluxio Project
 * LyricsSettings.kt is part of Fluxio.
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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings

/** User settings for the lyrics feature. */
interface LyricsSettings : Settings<LyricsSettings.Listener> {
    /** Whether LRCLIB online lyrics search is enabled. Disabled by default. */
    val lrclibEnabled: Boolean

    interface Listener {
        /** Called when [lrclibEnabled] changes. */
        fun onLrclibEnabledChanged() {}
    }
}

class LyricsSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<LyricsSettings.Listener>(context), LyricsSettings {

    override val lrclibEnabled: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_lrclib_enabled), false)

    override fun onSharedPreferenceChanged(prefs: android.content.SharedPreferences, key: String?) {
        super.onSharedPreferenceChanged(prefs, key)
        when (key) {
            getString(R.string.set_key_lrclib_enabled) ->
                listeners.forEach { it.onLrclibEnabledChanged() }
        }
    }
}
