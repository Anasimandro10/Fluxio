/*
 * Copyright (c) 2026 Fluxio Project
 * EqualizerFragment.kt is part of Fluxio.
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

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentEqualizerBinding
import org.oxycblt.auxio.settings.categories.DeviceProfileDialog
import org.oxycblt.auxio.ui.ViewBindingFragment

@AndroidEntryPoint
class EqualizerFragment : ViewBindingFragment<FragmentEqualizerBinding>() {

    private val viewModel: EqualizerViewModel by viewModels()
    private val seekBars = arrayOfNulls<SeekBar>(10)
    private var ignoreSpinner = false

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentEqualizerBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentEqualizerBinding, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(binding.eqScroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top)
            insets
        }

        buildBandViews(binding)
        setupPresetSpinner(binding)
        setupSwitch(binding)
        setupAutoEqBrowse(binding)
        setupDeviceProfilesButton(binding)
        setupListeningModes(binding)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.refreshFromSettings()

                launch { viewModel.enabled.collect { onEnabledChanged(binding, it) } }
                launch { viewModel.bands.collect { onBandsChanged(it) } }
                launch { viewModel.activePreset.collect { onPresetChanged(binding, it) } }
                launch {
                    combine(viewModel.autoEqProfileName, viewModel.isModifiedFromProfile) {
                            name,
                            modified ->
                            Pair(name, modified)
                        }
                        .collect { (name, modified) ->
                            onProfileLabelChanged(binding, name, modified)
                        }
                }
                launch { viewModel.listeningModes.collect { onListeningModesChanged(binding, it) } }
            }
        }
    }

    override fun onDestroyBinding(binding: FragmentEqualizerBinding) {
        seekBars.fill(null)
    }

    // ---- Build ----

    private fun buildBandViews(binding: FragmentEqualizerBinding) {
        val density = resources.displayMetrics.density
        val trackLenPx = (172 * density + 0.5f).toInt()
        val thumbSizePx = (32 * density + 0.5f).toInt()
        val freqLabels = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

        binding.eqBandsContainer.removeAllViews()
        binding.eqFreqLabels.removeAllViews()
        binding.eqDbLabels.removeAllViews()

        for (i in 0 until 10) {
            val frame =
                FrameLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, trackLenPx, 1f)
                }

            val seekBar =
                SeekBar(requireContext()).apply {
                    max = 240
                    progress = 120
                    rotation = -90f
                    layoutParams =
                        FrameLayout.LayoutParams(trackLenPx, thumbSizePx).apply {
                            gravity = Gravity.CENTER
                        }
                    setOnTouchListener { v, _ ->
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                sb: SeekBar,
                                progress: Int,
                                fromUser: Boolean,
                            ) {
                                if (fromUser) viewModel.setBand(i, (progress - 120) / 10f)
                            }

                            override fun onStartTrackingTouch(sb: SeekBar) {
                                sb.parent.requestDisallowInterceptTouchEvent(true)
                            }

                            override fun onStopTrackingTouch(sb: SeekBar) {}
                        }
                    )
                }

            seekBars[i] = seekBar
            frame.addView(seekBar)
            binding.eqBandsContainer.addView(frame)

            binding.eqFreqLabels.addView(
                TextView(requireContext()).apply {
                    text = freqLabels[i]
                    textSize = 9f
                    gravity = Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
        }
    }

    // ---- Setup ----

    private fun setupPresetSpinner(binding: FragmentEqualizerBinding) {
        val names = EqualizerSettings.PRESET_NAMES + listOf(getString(R.string.lbl_eq_custom))
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        binding.eqPresetSpinner.adapter = adapter
        binding.eqPresetSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (ignoreSpinner) return
                    val presetIdx =
                        if (position < EqualizerSettings.PRESET_NAMES.size) position
                        else EqualizerSettings.PRESET_CUSTOM
                    if (presetIdx != EqualizerSettings.PRESET_CUSTOM) {
                        viewModel.applyPreset(presetIdx)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupSwitch(binding: FragmentEqualizerBinding) {
        binding.eqSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setEnabled(checked) }
    }

    /** Opens [AutoEqBrowserDialog] when the user taps Browse Presets. */
    private fun setupAutoEqBrowse(binding: FragmentEqualizerBinding) {
        binding.eqBtnAutoeqBrowse.setOnClickListener {
            AutoEqBrowserDialog().show(childFragmentManager, AutoEqBrowserDialog.TAG)
        }
    }

    /** Opens [DeviceProfileDialog] from the shortcut button inside the EQ screen. */
    private fun setupDeviceProfilesButton(binding: FragmentEqualizerBinding) {
        binding.eqBtnDeviceProfiles.setOnClickListener {
            DeviceProfileDialog().show(childFragmentManager, DeviceProfileDialog.TAG)
        }
    }

    /**
     * Wires the Save button to show a name dialog; chips are rendered in [onListeningModesChanged].
     */
    private fun setupListeningModes(binding: FragmentEqualizerBinding) {
        binding.eqBtnSaveMode.setOnClickListener {
            if (!viewModel.canSaveListeningMode()) return@setOnClickListener
            showSaveModeDialog()
        }
    }

    // ---- State handlers ----

    private fun onEnabledChanged(binding: FragmentEqualizerBinding, enabled: Boolean) {
        binding.eqSwitch.isChecked = enabled
        seekBars.forEach { it?.isEnabled = enabled }
        binding.eqPresetSpinner.isEnabled = enabled
    }

    private fun onBandsChanged(bands: FloatArray) {
        for (i in 0 until 10) {
            val seekBar = seekBars[i] ?: continue
            val progress = (bands[i] * 10 + 120).toInt().coerceIn(0, 240)
            seekBar.post { seekBar.progress = progress }
        }
    }

    private fun onPresetChanged(binding: FragmentEqualizerBinding, preset: Int) {
        ignoreSpinner = true
        val pos =
            if (preset == EqualizerSettings.PRESET_CUSTOM) EqualizerSettings.PRESET_NAMES.size
            else preset
        binding.eqPresetSpinner.post {
            binding.eqPresetSpinner.setSelection(pos)
            binding.eqPresetSpinner.post { ignoreSpinner = false }
        }
    }

    private fun onProfileLabelChanged(
        binding: FragmentEqualizerBinding,
        name: String?,
        modified: Boolean,
    ) {
        if (name == null) {
            binding.eqAutoeqProfileLabel.visibility = View.GONE
            return
        }
        binding.eqAutoeqProfileLabel.text =
            if (modified) getString(R.string.lbl_autoeq_modified, name)
            else getString(R.string.lbl_autoeq_profile, name)
        binding.eqAutoeqProfileLabel.visibility = View.VISIBLE
    }

    private fun onListeningModesChanged(
        binding: FragmentEqualizerBinding,
        modes: List<ListeningMode>,
    ) {
        // Update Save button: disabled at capacity so the user never hits a silent failure.
        binding.eqBtnSaveMode.isEnabled = modes.size < ListeningModeManager.MAX_MODES

        val chipGroup = binding.eqListeningModesChips
        chipGroup.removeAllViews()

        if (modes.isEmpty()) {
            chipGroup.visibility = View.GONE
            binding.eqListeningModesEmpty.visibility = View.VISIBLE
            return
        }

        binding.eqListeningModesEmpty.visibility = View.GONE
        chipGroup.visibility = View.VISIBLE

        modes.forEach { mode ->
            val chip =
                Chip(requireContext()).apply {
                    text = mode.name
                    isCheckable = false
                    isCloseIconVisible = true
                    setOnClickListener { viewModel.applyListeningMode(mode) }
                    setOnCloseIconClickListener { showDeleteModeDialog(mode) }
                }
            chipGroup.addView(chip)
        }
    }

    // ---- Dialogs ----

    private fun showSaveModeDialog() {
        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        val editText =
            EditText(requireContext()).apply {
                hint = getString(R.string.hint_listening_mode_name)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2)
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_save_listening_mode)
            .setView(editText)
            .setPositiveButton(R.string.lbl_save) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) viewModel.saveCurrentAsListeningMode(name)
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun showDeleteModeDialog(mode: ListeningMode) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(mode.name)
            .setMessage(R.string.lbl_confirm_delete_mode)
            .setPositiveButton(R.string.lbl_delete) { _, _ -> viewModel.deleteListeningMode(mode) }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }
}
