/*
 * Copyright (c) 2026 Fluxio Project
 * SleepTimerDialog.kt is part of Fluxio.
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

import android.app.Dialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.PlaybackViewModel

/**
 * Dialog that lets the user set or cancel the sleep timer.
 *
 * Shows preset buttons (15 / 30 / 45 / 60 min) and a custom number input. If the timer is already
 * active, shows the remaining time updating in real time and a cancel button. Timer logic lives in
 * [PlaybackViewModel] so it keeps running even when this dialog or the playback panel are not
 * visible.
 */
@AndroidEntryPoint
class SleepTimerDialog : DialogFragment() {

    private val playbackModel: PlaybackViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()

        val view =
            requireActivity().layoutInflater.inflate(R.layout.dialog_sleep_timer, null, false)

        val statusText = view.findViewById<TextView>(R.id.timer_status)
        val presetsGroup = view.findViewById<LinearLayout>(R.id.timer_presets)
        val preset15 = view.findViewById<MaterialButton>(R.id.timer_preset_15)
        val preset30 = view.findViewById<MaterialButton>(R.id.timer_preset_30)
        val preset45 = view.findViewById<MaterialButton>(R.id.timer_preset_45)
        val preset60 = view.findViewById<MaterialButton>(R.id.timer_preset_60)
        val customInput = view.findViewById<EditText>(R.id.timer_custom_input)
        val cancelButton = view.findViewById<Button>(R.id.timer_cancel_button)
        val startButton = view.findViewById<Button>(R.id.timer_start_button)

        if (playbackModel.timerRemainingMs.value != null) {
            // Timer is running — dim presets, disable input, show live countdown
            presetsGroup.alpha = 0.4f
            presetsGroup.isEnabled = false
            customInput.isEnabled = false
            startButton.isEnabled = false
            cancelButton.isEnabled = true

            cancelButton.setOnClickListener {
                playbackModel.cancelSleepTimer()
                dismiss()
            }

            // Update the countdown every second while the dialog is open
            lifecycleScope.launch {
                playbackModel.timerRemainingMs.collect { remaining ->
                    if (!isAdded) return@collect
                    if (remaining == null) {
                        dismiss()
                        return@collect
                    }
                    val mins = (remaining / 60_000L).toInt()
                    val secs = ((remaining % 60_000L) / 1_000L).toInt()
                    statusText.text = ctx.getString(R.string.fmt_sleep_timer_remaining, mins, secs)
                }
            }
        } else {
            // No timer active — show setup UI
            statusText.text = ctx.getString(R.string.lbl_sleep_timer_off)
            cancelButton.isEnabled = false

            preset15.setOnClickListener { customInput.setText("15") }
            preset30.setOnClickListener { customInput.setText("30") }
            preset45.setOnClickListener { customInput.setText("45") }
            preset60.setOnClickListener { customInput.setText("60") }

            startButton.setOnClickListener {
                val minutes = customInput.text?.toString()?.trim()?.toIntOrNull()
                if (minutes != null && minutes > 0) {
                    playbackModel.startSleepTimer(minutes)
                    dismiss()
                } else {
                    Toast.makeText(ctx, R.string.err_sleep_timer_invalid, Toast.LENGTH_SHORT).show()
                }
            }
        }

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lbl_sleep_timer)
            .setView(view)
            .create()
    }
}
