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

    // Matches word-level timing tags like <00:01.23>word.
    private val WORD_PATTERN: Pattern =
        Pattern.compile("<(\\d{1,3}):(\\d{2})(?:\\.(\\d{2,3}))?>([^<]*)")

    /**
     * Parses raw LRC text into a sorted list of [LrcLine].
     *
     * @param content The raw text content of an .lrc file or embedded tag.
     * @return A list of [LrcLine] sorted by [LrcLine.startMs], or empty if unparseable.
     */
    fun parse(content: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()

        for (raw in content.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // Find all line-level timestamps (e.g. [mm:ss.xx])
            val matcher = TIMESTAMP_PATTERN.matcher(line)
            val timestamps = mutableListOf<Long>()

            while (matcher.find()) {
                timestamps.add(parseTimeParts(matcher.group(1), matcher.group(2), matcher.group(3)) ?: continue)
            }

            if (timestamps.isEmpty()) continue

            // The remaining text after stripping line-level timestamps
            val textAfterLineTimestamps = line.replace(TIMESTAMP_STRIP_REGEX, "").trim()

            // Try to extract word timings if <mm:ss.xx> tags exist
            val words = mutableListOf<WordTiming>()
            val wordMatcher = WORD_PATTERN.matcher(textAfterLineTimestamps)
            
            var plainTextBuilder = java.lang.StringBuilder()
            
            while (wordMatcher.find()) {
                val startMs = parseTimeParts(wordMatcher.group(1), wordMatcher.group(2), wordMatcher.group(3)) ?: continue
                val wordText = wordMatcher.group(4) ?: ""
                
                if (words.isNotEmpty()) {
                    // Previous word's end is current word's start
                    val prev = words.removeLast()
                    words.add(prev.copy(endMs = startMs))
                }
                val startChar = plainTextBuilder.length
                val endChar = startChar + wordText.length
                words.add(WordTiming(text = wordText, startMs = startMs, endMs = startMs + 1000L, startChar = startChar, endChar = endChar)) // 1s fallback endMs
                plainTextBuilder.append(wordText)
            }

            // If no word tags were found, we just use the text without tags
            val finalPlainText = if (words.isEmpty()) {
                textAfterLineTimestamps.replace(Regex("<[^>]*>"), "")
            } else {
                plainTextBuilder.toString()
            }

            for (ts in timestamps) {
                // If words were found, adjust their times relative to the line if needed, 
                // but usually word timestamps are absolute.
                lines.add(LrcLine(startMs = ts, text = finalPlainText.trim(), words = words.toList()))
            }
        }

        return lines.sortedBy { it.startMs }
    }
    
    private fun parseTimeParts(minutesStr: String?, secondsStr: String?, millisStr: String?): Long? {
        val minutes = minutesStr?.toLongOrNull() ?: return null
        val seconds = secondsStr?.toLongOrNull() ?: return null
        val millis = when {
            millisStr == null -> 0L
            millisStr.length == 2 -> (millisStr.toLongOrNull() ?: return null) * 10L
            else -> millisStr.toLongOrNull() ?: return null
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }
}
