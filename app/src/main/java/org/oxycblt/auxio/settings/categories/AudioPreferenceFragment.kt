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

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.crossfade.CrossfadeSettings
import org.oxycblt.auxio.playback.stereowidening.StereoWideningSettings
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.navigateSafe

/** Audio settings interface. */
@AndroidEntryPoint
class AudioPreferenceFragment : BasePreferenceFragment(R.xml.preferences_audio) {

    @Inject lateinit var crossfadeSettings: CrossfadeSettings
    @Inject lateinit var stereoWideningSettings: StereoWideningSettings

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<Preference>(getString(R.string.set_key_equalizer))
            ?.setOnPreferenceClickListener {
                findNavController().navigate(R.id.equalizer_fragment)
                true
            }

        // Push crossfade enabled state to the processor immediately when the switch changes.
        // The Preference framework writes the new value to SharedPreferences before this
        // listener fires (we return true), so CrossfadeSettings.enabled reflects the
        // new state on the next read.
        findPreference<SwitchPreferenceCompat>(getString(R.string.set_key_crossfade_enabled))
            ?.setOnPreferenceChangeListener { _, newValue ->
                crossfadeSettings.setEnabled(newValue as Boolean)
                true
            }

        // Push the new duration to the processor immediately when the slider moves.
        // newValue is the raw Int from SeekBarPreference [0–12].
        findPreference<SeekBarPreference>(getString(R.string.set_key_crossfade_duration))
            ?.setOnPreferenceChangeListener { _, newValue ->
                crossfadeSettings.setDuration(newValue as Int)
                true
            }

        // Push stereo widening intensity to the processor immediately when the slider moves.
        // newValue is the raw Int from SeekBarPreference [0–100].
        findPreference<SeekBarPreference>(getString(R.string.set_key_stereo_widening))
            ?.setOnPreferenceChangeListener { _, newValue ->
                stereoWideningSettings.setAmount(newValue as Int)
                true
            }

        setupSpatializer()
    }

    /**
     * Hides the Spatializer category on devices running Android 11 or earlier, where the system
     * does not expose a spatial audio settings panel. On Android 12+ (API 31), tapping the entry
     * opens the system Sound settings page which contains the Spatializer controls.
     *
     * Fluxio owns no audio processor here — the Spatializer is managed entirely by Android.
     */
    private fun setupSpatializer() {
        val category = findPreference<PreferenceCategory>("fluxio_spatializer_category") ?: return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Spatial audio UI did not exist before Android 12; hide the whole section.
            category.isVisible = false
            return
        }

        findPreference<Preference>(getString(R.string.set_key_spatializer))
            ?.setOnPreferenceClickListener {
                val intent = Intent(Settings.ACTION_SOUND_SETTINGS)
                // Guard against devices that may not resolve this intent (very unlikely for
                // SOUND_SETTINGS, but a crashed settings app should never take Fluxio down).
                if (intent.resolveActivity(requireContext().packageManager) != null) {
                    startActivity(intent)
                }
                true
            }
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        when (preference.key) {
            getString(R.string.set_key_pre_amp) ->
                findNavController().navigateSafe(AudioPreferenceFragmentDirections.preAmpSettings())
            getString(R.string.set_key_device_profiles) ->
                DeviceProfileDialog().show(childFragmentManager, DeviceProfileDialog.TAG)
        }
    }
}
