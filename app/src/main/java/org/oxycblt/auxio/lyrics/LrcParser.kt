/*
 * Copyright (c) 2026 Fluxio Project
 * LrcParser.kt is part of Fluxio.
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

import java.util.regex.Pattern

/**
 * Parses LRC lyric files into a list of [LrcLine] objects sorted by timestamp.
 *
 * Supports both standard LRC ([mm:ss.xx]) and extended word-by-word LRC. Unknown lines (metadata
 * tags, blank lines) are silently ignored.
 */
object LrcParser {

    // Matches timestamps like [01:23.45] or [01:23.456]
    private val TIMESTAMP_PATTERN: Pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})]")

    /**
     * Parses raw LRC text into a sorted list of [LrcLine].
     *
     * @param content The raw text content of an .lrc file.
     * @return A list of [LrcLine] sorted by [LrcLine.startMs], or empty if unparseable.
     */
    fun parse(content: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()

        for (raw in content.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // Find all timestamps on this line (there can be multiple)
            val matcher = TIMESTAMP_PATTERN.matcher(line)
            val timestamps = mutableListOf<Long>()

            while (matcher.find()) {
                val minutes = matcher.group(1)?.toLongOrNull() ?: continue
                val seconds = matcher.group(2)?.toLongOrNull() ?: continue
                val centisRaw = matcher.group(3) ?: continue
                // Normalize to milliseconds: 2-digit = centiseconds, 3-digit = milliseconds
                val millis =
                    if (centisRaw.length == 2) {
                        centisRaw.toLongOrNull()?.times(10) ?: continue
                    } else {
                        centisRaw.toLongOrNull() ?: continue
                    }
                timestamps.add(minutes * 60_000 + seconds * 1_000 + millis)
            }

            if (timestamps.isEmpty()) continue

            // Strip all timestamp tags and word-level tags (<mm:ss.xx>) to get clean text
            val text =
                line
                    .replace(Regex("\\[\\d{2}:\\d{2}\\.\\d+]"), "")
                    .replace(Regex("<\\d{2}:\\d{2}\\.\\d+>"), "")
                    .trim()

            if (text.isEmpty()) continue

            // A line can repeat with multiple timestamps (e.g. chorus repeats)
            for (ts in timestamps) {
                lines.add(LrcLine(startMs = ts, text = text))
            }
        }

        return lines.sortedBy { it.startMs }
    }
}
