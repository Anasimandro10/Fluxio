/*
 * Copyright (c) 2026 Fluxio Project
 * NormalizationTarget.kt is part of Fluxio.
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

package org.oxycblt.auxio.playback.normalizer

import org.oxycblt.auxio.IntegerTable

/**
 * Target loudness level for automatic volume normalization.
 *
 * Values follow the ITU-R BS.1770-4 standard (K-weighted RMS). [targetRmsDb] is the target in
 * dBFS; [targetRmsLinear] is its pre-computed 16-bit PCM equivalent used by [VolumeNormalizer]
 * to avoid repeated pow() calls in the audio thread.
 *
 * Approximate LUFS equivalences:
 *   LUFS_14 ≈ -14 LUFS — Spotify / YouTube Music / Apple Music streaming standard
 *   LUFS_16 ≈ -16 LUFS — balanced; recommended for mixed listening environments
 *   LUFS_18 ≈ -18 LUFS — conservative; minimal alteration of original dynamics
 */
enum class NormalizationTarget(
    /** Target K-weighted RMS in dBFS (negative, e.g. -13.31). */
    val targetRmsDb: Float,
) {
    LUFS_14(targetRmsDb = -13.31f),
    LUFS_16(targetRmsDb = -15.31f),
    LUFS_18(targetRmsDb = -17.31f);

    /**
     * Target RMS in linear 16-bit PCM units. Pre-computed to avoid repeated Math.pow() calls
     * in the audio thread.
     */
    val targetRmsLinear: Double = Math.pow(10.0, targetRmsDb / 20.0) * 32768.0

    companion object {
        fun fromIntCode(intCode: Int) =
            when (intCode) {
                IntegerTable.NORMALIZATION_TARGET_14 -> LUFS_14
                IntegerTable.NORMALIZATION_TARGET_16 -> LUFS_16
                IntegerTable.NORMALIZATION_TARGET_18 -> LUFS_18
                else -> null
            }
    }
}
