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
import javax.inject.Singleton
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings

/** User settings for the lyrics feature. */
interface LyricsSettings : Settings<LyricsSettings.Listener> {
    /** Whether LRCLIB online lyrics search is enabled. Disabled by default. */
    val lrclibEnabled: Boolean

    /**
     * When enabled, if embedded lyrics have no timestamps (plain text), LRCLIB is searched first
     * for a synced version. Falls back to the embedded plain text if LRCLIB has nothing.
     * Disabled by default.
     */
    val lrclibPreferSynced: Boolean

    interface Listener {
        /** Called when [lrclibEnabled] changes. */
        fun onLrclibEnabledChanged() {}
        /** Called when [lrclibPreferSynced] changes. */
        fun onLrclibPreferSyncedChanged() {}
    }
}

@Singleton
class LyricsSettingsImpl @Inject constructor(@ApplicationContext private val context: Context) :
    Settings.Impl<LyricsSettings.Listener>(context), LyricsSettings {

    override val lrclibEnabled: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_lrclib_enabled), false)

    override val lrclibPreferSynced: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_lrclib_prefer_synced), false)

    override fun onSettingChanged(key: String, listener: LyricsSettings.Listener) {
        when (key) {
            getString(R.string.set_key_lrclib_enabled) -> listener.onLrclibEnabledChanged()
            getString(R.string.set_key_lrclib_prefer_synced) -> listener.onLrclibPreferSyncedChanged()
        }
    }
}