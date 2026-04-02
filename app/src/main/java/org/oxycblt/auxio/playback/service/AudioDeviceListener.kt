/*
 * Copyright (c) 2026 Fluxio Project
 * AudioDeviceListener.kt is part of Fluxio.
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
package org.oxycblt.auxio.playback.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.playback.equalizer.EqualizerAudioProcessor
import org.oxycblt.auxio.playback.equalizer.EqualizerSettings
import timber.log.Timber as L

/**
 * Registers an [AudioDeviceCallback] and applies the user-assigned EQ preset whenever a
 * Bluetooth A2DP or wired headset device connects.
 *
 * Lifecycle mirrors [ExoPlaybackStateHolder]: [attach] is called in [ExoPlaybackStateHolder.attach]
 * and [release] is called in [ExoPlaybackStateHolder.release].
 */
@Singleton
class AudioDeviceListener
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val equalizerSettings: EqualizerSettings,
    private val equalizerProcessor: EqualizerAudioProcessor,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                for (device in addedDevices) {
                    val deviceType = device.type.toDeviceType() ?: continue
                    val preset = equalizerSettings.getDevicePreset(deviceType)
                    if (preset == EqualizerSettings.DEVICE_PROFILE_NONE) break
                    L.d("Device connected ($deviceType), applying EQ preset $preset")
                    equalizerSettings.applyPreset(preset)
                    equalizerProcessor.setBands(
                        equalizerSettings.getBands(),
                        equalizerSettings.enabled,
                    )
                    break
                }
            }
        }

    /** Registers the audio device callback. Call once the playback service is ready. */
    fun attach() {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        L.d("AudioDeviceListener attached")
    }

    /** Unregisters the audio device callback. Call when the playback service is released. */
    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        L.d("AudioDeviceListener released")
    }

    private fun Int.toDeviceType(): EqualizerSettings.DeviceType? =
        when (this) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> EqualizerSettings.DeviceType.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> EqualizerSettings.DeviceType.WIRED
            else -> null
        }
}
