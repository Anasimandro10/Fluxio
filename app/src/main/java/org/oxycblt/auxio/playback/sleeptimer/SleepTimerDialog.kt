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

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogSleepTimerBinding
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment

/**
 * Dialog that lets the user set or cancel the sleep timer.
 *
 * Shows preset buttons (15 / 30 / 45 / 60 min) and a custom number input.
 * If the timer is already active, the dialog shows a Cancel button instead.
 */
@AndroidEntryPoint
class SleepTimerDialog : ViewBindingMaterialDialogFragment<DialogSleepTimerBinding>() {

    private val timerModel: SleepTimerViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogSleepTimerBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder.setTitle(R.string.lbl_sleep_timer)
    }

    override fun onBindingCreated(
        binding: DialogSleepTimerBinding,
        savedInstanceState: Bundle?,
    ) {
        val isActive = timerModel.remainingMs.value != null

        if (isActive) {
            // Timer is running — show remaining time and a cancel button
            val remaining = timerModel.remainingMs.value ?: 0L
            val totalMinutes = (remaining / 60_000L).toInt()
            val seconds = ((remaining % 60_000L) / 1_000L).toInt()
            binding.timerStatus.text =
                requireContext()
                    .getString(R.string.fmt_sleep_timer_remaining, totalMinutes, seconds)
            binding.timerPresets.alpha = 0.4f
            binding.timerPresets.isEnabled = false
            binding.timerCustomInput.isEnabled = false

            binding.timerCancelButton.isEnabled = true
            binding.timerCancelButton.setOnClickListener {
                timerModel.cancelTimer()
                dismiss()
            }
            binding.timerStartButton.isEnabled = false
        } else {
            binding.timerStatus.text = requireContext().getString(R.string.lbl_sleep_timer_off)
            binding.timerCancelButton.isEnabled = false

            binding.timerStartButton.setOnClickListener {
                val minutes = resolveSelectedMinutes(binding)
                if (minutes != null && minutes > 0) {
                    timerModel.startTimer(minutes)
                    dismiss()
                } else {
                    Toast.makeText(
                            requireContext(),
                            R.string.err_sleep_timer_invalid,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            }
        }

        // Preset buttons
        binding.timerPreset15.setOnClickListener { selectPreset(binding, 15) }
        binding.timerPreset30.setOnClickListener { selectPreset(binding, 30) }
        binding.timerPreset45.setOnClickListener { selectPreset(binding, 45) }
        binding.timerPreset60.setOnClickListener { selectPreset(binding, 60) }
    }

    private fun selectPreset(binding: DialogSleepTimerBinding, minutes: Int) {
        binding.timerCustomInput.setText(minutes.toString())
    }

    private fun resolveSelectedMinutes(binding: DialogSleepTimerBinding): Int? {
        val text = binding.timerCustomInput.text?.toString()?.trim() ?: return null
        return text.toIntOrNull()
    }
}
