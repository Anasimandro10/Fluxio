/*
 * Copyright (c) 2026 Fluxio Project
 * AudioTabFragment.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback

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
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentAudioTabBinding
import org.oxycblt.auxio.playback.crossfade.CrossfadeSettings
import org.oxycblt.auxio.playback.equalizer.AutoEqBrowserDialog
import org.oxycblt.auxio.playback.equalizer.EqualizerSettings
import org.oxycblt.auxio.playback.equalizer.EqualizerViewModel
import org.oxycblt.auxio.playback.equalizer.ListeningMode
import org.oxycblt.auxio.playback.equalizer.ListeningModeManager
import org.oxycblt.auxio.playback.speed.PlaybackSpeedSettings
import org.oxycblt.auxio.playback.stereowidening.StereoWideningSettings
import org.oxycblt.auxio.settings.categories.DeviceProfileDialog
import org.oxycblt.auxio.ui.ViewBindingFragment

@AndroidEntryPoint
class AudioTabFragment : ViewBindingFragment<FragmentAudioTabBinding>() {

    private val viewModel: EqualizerViewModel by viewModels()

    @Inject lateinit var crossfadeSettings: CrossfadeSettings
    @Inject lateinit var stereoSettings: StereoWideningSettings
    @Inject lateinit var speedSettings: PlaybackSpeedSettings

    private val seekBars = arrayOfNulls<SeekBar>(10)
    private var ignoreSpinner = false

    // Suppress internal SeekBar listener triggers during programmatic init.
    private var ignoreCrossfadeSlider = false
    private var ignoreStereoSlider = false
    private var ignoreSpeedSlider = false

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentAudioTabBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentAudioTabBinding, savedInstanceState: Bundle?) {
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

        setupCrossfadeCard(binding)
        setupStereoCard(binding)
        setupSpeedCard(binding)

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

    override fun onDestroyBinding(binding: FragmentAudioTabBinding) {
        seekBars.fill(null)
    }

    // ---- EQ band views ----

    private fun buildBandViews(binding: FragmentAudioTabBinding) {
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

    // ---- EQ setup ----

    private fun setupPresetSpinner(binding: FragmentAudioTabBinding) {
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

    private fun setupSwitch(binding: FragmentAudioTabBinding) {
        binding.eqSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setEnabled(checked) }
    }

    private fun setupAutoEqBrowse(binding: FragmentAudioTabBinding) {
        binding.eqBtnAutoeqBrowse.setOnClickListener {
            AutoEqBrowserDialog().show(childFragmentManager, AutoEqBrowserDialog.TAG)
        }
    }

    private fun setupDeviceProfilesButton(binding: FragmentAudioTabBinding) {
        binding.eqBtnDeviceProfiles.setOnClickListener {
            DeviceProfileDialog().show(childFragmentManager, DeviceProfileDialog.TAG)
        }
    }

    private fun setupListeningModes(binding: FragmentAudioTabBinding) {
        binding.eqBtnSaveMode.setOnClickListener {
            if (!viewModel.canSaveListeningMode()) return@setOnClickListener
            showSaveModeDialog()
        }
    }

    // ---- Crossfade card ----

    private fun setupCrossfadeCard(binding: FragmentAudioTabBinding) {
        val initialSeconds = crossfadeSettings.durationSeconds
        ignoreCrossfadeSlider = true
        binding.audioCrossfadeSlider.post {
            binding.audioCrossfadeSlider.progress = initialSeconds
            ignoreCrossfadeSlider = false
        }
        // Show "Off" if crossfade is disabled, otherwise show the stored duration.
        binding.audioCrossfadeValue.text =
            if (!crossfadeSettings.enabled) getString(R.string.lbl_audio_off)
            else crossfadeValueLabel(initialSeconds)

        binding.audioCrossfadeSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (ignoreCrossfadeSlider) return
                    binding.audioCrossfadeValue.text = crossfadeValueLabel(progress)
                    crossfadeSettings.setEnabled(progress > 0)
                    crossfadeSettings.setDuration(progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    sb.parent.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(sb: SeekBar) {}
            }
        )
    }

    /** "Off" when seconds == 0, otherwise "Ns". */
    private fun crossfadeValueLabel(seconds: Int): String =
        if (seconds == 0) getString(R.string.lbl_audio_off) else "${seconds}s"

    // ---- Stereo Widening card ----

    private fun setupStereoCard(binding: FragmentAudioTabBinding) {
        val initialPercent = stereoSettings.amountPercent
        ignoreStereoSlider = true
        binding.audioStereoSlider.post {
            binding.audioStereoSlider.progress = initialPercent
            ignoreStereoSlider = false
        }
        binding.audioStereoValue.text = stereoValueLabel(initialPercent)

        binding.audioStereoSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (ignoreStereoSlider) return
                    binding.audioStereoValue.text = stereoValueLabel(progress)
                    stereoSettings.setAmount(progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    sb.parent.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(sb: SeekBar) {}
            }
        )
    }

    /** "Off" when percent == 0, otherwise "N%". */
    private fun stereoValueLabel(percent: Int): String =
        if (percent == 0) getString(R.string.lbl_audio_off) else "${percent}%"

    // ---- Speed card ----

    /**
     * Maps a SeekBar progress value [0–55] to a playback speed [0.25–3.0].
     * Step size: 0.05× per unit. SeekBar max = 55 → 0.25 + 55 × 0.05 = 3.0.
     */
    private fun progressToSpeed(progress: Int): Float = 0.25f + progress * 0.05f

    /** Maps a playback speed [0.25–3.0] back to a SeekBar progress [0–55]. */
    private fun speedToProgress(speed: Float): Int =
        ((speed - 0.25f) / 0.05f).roundToInt().coerceIn(0, 55)

    /** Formats a speed as a clean string: 1.0 → "1×", 1.5 → "1.5×", 0.25 → "0.25×". */
    private fun speedLabel(speed: Float): String {
        val s = String.format("%.2f", speed).trimEnd('0').trimEnd('.')
        return "${s}×"
    }

    private fun setupSpeedCard(binding: FragmentAudioTabBinding) {
        val initialSpeed = speedSettings.speedX
        val initialProgress = speedToProgress(initialSpeed)

        ignoreSpeedSlider = true
        binding.audioSpeedSlider.post {
            binding.audioSpeedSlider.progress = initialProgress
            ignoreSpeedSlider = false
        }
        binding.audioSpeedValue.text = speedLabel(initialSpeed)
        updateSpeedChips(binding, initialSpeed)

        binding.audioSpeedSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (ignoreSpeedSlider) return
                    val speed = progressToSpeed(progress)
                    binding.audioSpeedValue.text = speedLabel(speed)
                    updateSpeedChips(binding, speed)
                    speedSettings.setSpeed(speed)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    sb.parent.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(sb: SeekBar) {}
            }
        )

        val chipPresets =
            listOf(
                binding.audioChipSpeed050 to 0.50f,
                binding.audioChipSpeed075 to 0.75f,
                binding.audioChipSpeed100 to 1.00f,
                binding.audioChipSpeed150 to 1.50f,
                binding.audioChipSpeed200 to 2.00f,
            )
        chipPresets.forEach { (chip, speed) ->
            chip.setOnClickListener {
                ignoreSpeedSlider = true
                binding.audioSpeedSlider.progress = speedToProgress(speed)
                ignoreSpeedSlider = false
                binding.audioSpeedValue.text = speedLabel(speed)
                updateSpeedChips(binding, speed)
                speedSettings.setSpeed(speed)
            }
        }
    }

    /**
     * Marks the chip that exactly matches [speed] as checked; clears all others.
     * Uses kotlin.math.abs for idiomatic Kotlin float comparison.
     */
    private fun updateSpeedChips(binding: FragmentAudioTabBinding, speed: Float) {
        val tolerance = 0.001f
        binding.audioChipSpeed050.isChecked = abs(speed - 0.50f) < tolerance
        binding.audioChipSpeed075.isChecked = abs(speed - 0.75f) < tolerance
        binding.audioChipSpeed100.isChecked = abs(speed - 1.00f) < tolerance
        binding.audioChipSpeed150.isChecked = abs(speed - 1.50f) < tolerance
        binding.audioChipSpeed200.isChecked = abs(speed - 2.00f) < tolerance
    }

    // ---- EQ state handlers ----

    private fun onEnabledChanged(binding: FragmentAudioTabBinding, enabled: Boolean) {
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

    private fun onPresetChanged(binding: FragmentAudioTabBinding, preset: Int) {
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
        binding: FragmentAudioTabBinding,
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
        binding: FragmentAudioTabBinding,
        modes: List<ListeningMode>,
    ) {
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
