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
 * along with this program.  If not, see .
 */

package org.oxycblt.auxio.playback.normalizer

import org.oxycblt.auxio.IntegerTable

/**
 * The target loudness level for automatic volume normalization.
 *
 * Values are approximate LUFS equivalents matched to PCM 16-bit RMS analysis targets.
 */
enum class NormalizationTarget {
    /** -14 LUFS — matches streaming services (Spotify, YouTube Music, Apple Music). */
    LUFS_14,
    /** -16 LUFS — balanced, slightly less modification than the streaming standard. */
    LUFS_16,
    /** -18 LUFS — conservative, minimal alteration of the original dynamics. */
    LUFS_18;

    companion object {
        /**
         * Convert a [NormalizationTarget] integer representation into an instance.
         *
         * @param intCode An integer representation of a [NormalizationTarget]
         * @return The corresponding [NormalizationTarget], or null if invalid.
         */
        fun fromIntCode(intCode: Int) =
            when (intCode) {
                IntegerTable.NORMALIZATION_TARGET_14 -> LUFS_14
                IntegerTable.NORMALIZATION_TARGET_16 -> LUFS_16
                IntegerTable.NORMALIZATION_TARGET_18 -> LUFS_18
                else -> null
            }
    }
}
