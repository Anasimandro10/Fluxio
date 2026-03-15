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
 * Supports both standard LRC ([mm:ss.xx]) and extended word-by-word LRC. Decimal part is optional
 * so [mm:ss] without centiseconds is also accepted — common in embedded tags. Minutes field accepts
 * 1–3 digits to handle files longer than 99 minutes. Unknown lines (metadata tags, blank lines) are
 * silently ignored.
 *
 * Lines with an empty text body after stripping tags are kept as instrumental silence markers. They
 * cause the active-line highlight to turn off when reached ([LrcLine.isSilence] = true).
 */
object LrcParser {

    // Matches [mm:ss] and [mm:ss.xx] / [mm:ss.xxx] — decimals are optional.
    // Minutes: 1–3 digits. Seconds: exactly 2 digits. Decimals: 2–3 digits if present.
    private val TIMESTAMP_PATTERN: Pattern =
        Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{2,3}))?]")

    // Strips timestamp tags from the lyric text line (same tolerant rules as above).
    private val TIMESTAMP_STRIP_REGEX = Regex("\\[\\d{1,3}:\\d{2}(?:\\.\\d+)?]")

    // Strips word-level timing tags like <00:01.23>.
    private val WORD_TAG_STRIP_REGEX = Regex("<\\d{1,3}:\\d{2}(?:\\.\\d+)?>")

    /**
     * Parses raw LRC text into a sorted list of [LrcLine].
     *
     * Lines whose text is empty after stripping all tags are kept as instrumental silence markers
     * ([LrcLine.isSilence] = true). The UI turns off the active-line highlight for these.
     *
     * @param content The raw text content of an .lrc file or embedded tag.
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
                // Decimals are optional — treat absence as 0 ms
                val decRaw = matcher.group(3)
                val millis =
                    when {
                        decRaw == null -> 0L
                        decRaw.length == 2 -> (decRaw.toLongOrNull() ?: continue) * 10L
                        else -> decRaw.toLongOrNull() ?: continue
                    }
                timestamps.add(minutes * 60_000L + seconds * 1_000L + millis)
            }

            if (timestamps.isEmpty()) continue

            // Strip all timestamp tags and word-level tags to get clean text.
            // An empty result is an instrumental silence marker — kept intentionally.
            val text =
                line.replace(TIMESTAMP_STRIP_REGEX, "").replace(WORD_TAG_STRIP_REGEX, "").trim()

            // A line can repeat with multiple timestamps (e.g. chorus repeats)
            for (ts in timestamps) {
                lines.add(LrcLine(startMs = ts, text = text))
            }
        }

        return lines.sortedBy { it.startMs }
    }
}
