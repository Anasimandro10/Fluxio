/*
 * Copyright (c) 2026 Fluxio Project
 * LrcLine.kt is part of Fluxio.
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

import androidx.compose.runtime.Immutable

/** Represents a single word/syllable and its timing. */
@Immutable
data class WordTiming(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val startChar: Int = 0,
    val endChar: Int = 0,
)

/**
 * A single line of a synced lyric file.
 *
 * @param startMs The timestamp in milliseconds when this line should be highlighted.
 * @param endMs The timestamp when this line ends (if known), otherwise 0.
 * @param text The lyric text to display. An empty string means an instrumental silence marker.
 * @param words The list of words with individual timings, if available (for word-by-word sync).
 */
@Immutable
data class LrcLine(
    val startMs: Long,
    val endMs: Long = 0L,
    val text: String,
    val words: List<WordTiming> = emptyList(),
) {
    /** True when this line represents an instrumental silence (empty text body in the LRC file). */
    val isSilence: Boolean
        get() = text.isEmpty()
}
