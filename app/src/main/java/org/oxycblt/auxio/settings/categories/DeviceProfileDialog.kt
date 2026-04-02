/*
 * Copyright (c) 2026 Fluxio Project
 * DeviceProfileDialog.kt is part of Fluxio.
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

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.equalizer.EqualizerSettings

/** Dialog for assigning an EQ preset to each audio device type (Bluetooth, wired headset). */
@AndroidEntryPoint
class DeviceProfileDialog : DialogFragment() {
    @Inject lateinit var equalizerSettings: EqualizerSettings

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()

        val entries =
            arrayOf(getString(R.string.set_device_profiles_no_change)) +
                EqualizerSettings.PRESET_NAMES.toTypedArray()
        val values =
            IntArray(entries.size) { i ->
                if (i == 0) EqualizerSettings.DEVICE_PROFILE_NONE else i - 1
            }

        fun makeSpinner(deviceType: EqualizerSettings.DeviceType): Spinner {
            val spinner = Spinner(ctx)
            spinner.adapter =
                ArrayAdapter(ctx, android.R.layout.simple_spinner_item, entries).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
            val saved = equalizerSettings.getDevicePreset(deviceType)
            spinner.setSelection(values.indexOfFirst { it == saved }.coerceAtLeast(0))
            return spinner
        }

        val btSpinner = makeSpinner(EqualizerSettings.DeviceType.BLUETOOTH)
        val wiredSpinner = makeSpinner(EqualizerSettings.DeviceType.WIRED)

        val dp = ctx.resources.displayMetrics.density
        val pad16 = (16 * dp + 0.5f).toInt()
        val pad8 = (8 * dp + 0.5f).toInt()

        val layout =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad16, pad16, pad16, 0)
                addView(TextView(ctx).apply { setText(R.string.set_device_profiles_bt) })
                addView(btSpinner)
                addView(
                    TextView(ctx).apply {
                        setText(R.string.set_device_profiles_wired)
                        setPadding(0, pad8, 0, 0)
                    }
                )
                addView(wiredSpinner)
            }

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.set_device_profiles)
            .setView(layout)
            .setPositiveButton(R.string.lbl_save) { _, _ ->
                equalizerSettings.setDevicePreset(
                    EqualizerSettings.DeviceType.BLUETOOTH,
                    values[btSpinner.selectedItemPosition],
                )
                equalizerSettings.setDevicePreset(
                    EqualizerSettings.DeviceType.WIRED,
                    values[wiredSpinner.selectedItemPosition],
                )
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .create()
    }

    companion object {
        /** Fragment tag for use with [androidx.fragment.app.FragmentManager]. */
        const val TAG = "DeviceProfileDialog"
    }
}
