/*
 * Copyright (c) 2026 Fluxio Project
 * LyricsRepository.kt is part of Fluxio.
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

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Format
import timber.log.Timber as L

/**
 * Result of a lyrics search, including the lines to display and whether they are synced.
 *
 * @param lines The parsed lyric lines.
 * @param isSynced True if the lyrics have timestamps (LRC format). False for plain text.
 * @param source Where the lyrics came from (for debugging).
 */
data class LyricsResult(val lines: List<LrcLine>, val isSynced: Boolean, val source: LyricsSource)

/** Indicates where a set of lyrics was obtained from. */
enum class LyricsSource {
    LOCAL_LRC,
    EMBEDDED_SYNCED,
    EMBEDDED_PLAIN,
    LRCLIB_SYNCED,
    LRCLIB_PLAIN,
}

/**
 * Loads lyrics for a song using this priority order:
 * 1. In-memory LRU cache (instant — no I/O at all)
 * 2. Local .lrc file next to the audio file
 * 3. Embedded lyrics in the audio file tags (ID3v2 USLT, Vorbis LYRICS, MP4 ©lyr)
 *    - If synced (LRC format): returned immediately.
 *    - If plain text and [LyricsSettings.lrclibPreferSynced] is enabled: LRCLIB is checked for a
 *      synced version only. LRCLIB plain text is NOT accepted as a replacement — embedded plain
 *      is returned as fallback in that case.
 *    - If plain text and [LyricsSettings.lrclibPreferSynced] is disabled: returned immediately.
 * 4. LRCLIB (Room disk cache → network) — only if enabled in settings
 *
 * Call [clearMemoryCache] when settings that affect the lookup order change so cached results
 * are re-evaluated on next playback.
 */
@Singleton
class LyricsRepository
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val lrclibApi: LrclibApi,
    private val lrclibCache: LrclibCache,
    private val lyricsSettings: LyricsSettings,
) {

    // LRU in-memory cache. Null value = "we looked and found nothing" (avoids repeated lookups).
    private val memoryCache =
        object : LinkedHashMap<String, LyricsResult?>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, LyricsResult?>?) = size > 30
        }

    /**
     * Returns lyrics for [song], using memory cache if available. For songs not yet seen this
     * session, performs the full lookup chain (local → embedded → LRCLIB).
     */
    suspend fun loadLyrics(song: Song): LyricsResult? {
        val key = song.uri.toString()
        if (memoryCache.containsKey(key)) {
            val hit = memoryCache[key]
            L.d("Memory ${if (hit != null) "HIT" else "MISS(no lyrics)"} ${song.path.name}")
            return hit
        }
        val result = lookup(song)
        memoryCache[key] = result
        return result
    }

    /**
     * Pre-warms the memory cache for [song] without blocking the caller's result. Safe to call
     * concurrently — does nothing if the song is already cached.
     */
    suspend fun prefetch(song: Song) {
        val key = song.uri.toString()
        if (!memoryCache.containsKey(key)) {
            L.d("Prefetching ${song.path.name}")
            memoryCache[key] = lookup(song)
        }
    }

    /** Compatibility wrapper — returns only the lines list or null. */
    suspend fun loadLrc(song: Song): List<LrcLine>? = loadLyrics(song)?.lines

    /** Forces a fresh lookup next time — removes both the Room and memory entries. */
    suspend fun invalidateLrclibCache(song: Song) {
        val artist = song.artists.firstOrNull()?.name?.resolve(context) ?: ""
        val title = song.name.resolve(context)
        lrclibCache.invalidate(artist, title)
        memoryCache.remove(song.uri.toString())
    }

    /** Clears all LRCLIB data (Room + memory). */
    suspend fun clearLrclibCache() {
        lrclibCache.clearAll()
        memoryCache.clear()
    }

    /**
     * Clears only the in-memory cache without touching Room. Called by [LyricsViewModel] when
     * settings that affect the lookup order change (lrclibEnabled, lrclibPreferSynced).
     */
    fun clearMemoryCache() {
        L.d("Clearing lyrics memory cache (settings changed)")
        memoryCache.clear()
    }

    /** Returns the number of entries in the Room LRCLIB cache. */
    suspend fun lrclibCacheCount(): Int = lrclibCache.count()

    // -------------------------------------------------------------------------
    // Full lookup — no cache involved
    // -------------------------------------------------------------------------

    private suspend fun lookup(song: Song): LyricsResult? {
        // 1. Local .lrc file
        val localContent = withContext(Dispatchers.IO) { readLrcForSong(song) }
        if (localContent != null) {
            val lines = LrcParser.parse(localContent)
            if (lines.isNotEmpty()) {
                L.d("Lyrics: local LRC (${lines.size} lines)")
                return LyricsResult(lines, isSynced = true, source = LyricsSource.LOCAL_LRC)
            }
        }

        // 2. Embedded tags
        val embedded =
            withTimeoutOrNull(3_000L) {
                withContext(Dispatchers.IO) {
                    val dataPath = getDataPath(song.uri)
                    if (song.format is Format.FLAC) {
                        if (dataPath != null) readFlacVorbisLyrics(dataPath)
                        else readFlacVorbisLyricsFromUri(song.uri)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        if (dataPath != null) readEmbeddedFromPath(dataPath)
                        else readEmbeddedFromUri(song.uri, song)
                    } else {
                        null
                    }
                }
            }

        if (embedded != null) {
            val lrcLines = LrcParser.parse(embedded)
            if (lrcLines.isNotEmpty()) {
                L.d("Lyrics: embedded tag, LRC format (${lrcLines.size} lines)")
                return LyricsResult(
                    lrcLines,
                    isSynced = true,
                    source = LyricsSource.EMBEDDED_SYNCED,
                )
            }
            val plainLines =
                embedded
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { LrcLine(startMs = 0L, text = it) }
            if (plainLines.isNotEmpty()) {
                // Bug 4 fix: when lrclibPreferSynced is on, only accept LRCLIB if it has a
                // synced version. If LRCLIB only has plain text, use the embedded lyrics —
                // they are the official lyrics of the file and LRCLIB plain may differ.
                if (lyricsSettings.lrclibEnabled && lyricsSettings.lrclibPreferSynced) {
                    L.d("Lyrics: embedded plain — checking LRCLIB for synced version only")
                    val syncedFromLrclib = tryLrclibSyncedOnly(song)
                    if (syncedFromLrclib != null) {
                        L.d("Lyrics: LRCLIB synced preferred over embedded plain")
                        return syncedFromLrclib
                    }
                }
                L.d("Lyrics: embedded tag, plain text (${plainLines.size} lines)")
                return LyricsResult(
                    plainLines,
                    isSynced = false,
                    source = LyricsSource.EMBEDDED_PLAIN,
                )
            }
        }

        // 3. LRCLIB (full — synced preferred, plain as fallback)
        if (!lyricsSettings.lrclibEnabled) return null
        return tryLrclib(song)
    }

    /**
     * Queries LRCLIB and returns a result ONLY if it has synced lyrics. Plain-text results are
     * discarded. Used by the lrclibPreferSynced path so we never replace embedded plain text with
     * LRCLIB plain text (which could be a different or lower-quality version).
     */
    private suspend fun tryLrclibSyncedOnly(song: Song): LyricsResult? {
        val artist = song.artists.firstOrNull()?.name?.resolve(context) ?: ""
        val title = song.name.resolve(context)
        val album = song.album.name.resolve(context)
        val duration = (song.durationMs / 1000).toInt()
        val remote = fetchFromLrclib(artist, title, album, duration) ?: return null
        val synced = remote.syncedLyrics ?: return null
        val lines = LrcParser.parse(synced)
        return if (lines.isNotEmpty()) LyricsResult(lines, isSynced = true, source = LyricsSource.LRCLIB_SYNCED) else null
    }

    /**
     * Queries LRCLIB (Room cache first, then network) and returns the best available result
     * (synced preferred, plain as fallback), or null if nothing is found.
     */
    private suspend fun tryLrclib(song: Song): LyricsResult? {
        val artist = song.artists.firstOrNull()?.name?.resolve(context) ?: ""
        val title = song.name.resolve(context)
        val album = song.album.name.resolve(context)
        val duration = (song.durationMs / 1000).toInt()
        val remote = fetchFromLrclib(artist, title, album, duration) ?: return null
        val synced = remote.syncedLyrics
        if (synced != null) {
            val lines = LrcParser.parse(synced)
            if (lines.isNotEmpty()) {
                L.d("Lyrics: LRCLIB synced (${lines.size} lines)")
                return LyricsResult(lines, isSynced = true, source = LyricsSource.LRCLIB_SYNCED)
            }
        }
        val plain = remote.plainLyrics
        if (!plain.isNullOrBlank()) {
            val lines =
                plain
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { LrcLine(startMs = 0L, text = it) }
            if (lines.isNotEmpty()) {
                L.d("Lyrics: LRCLIB plain text (${lines.size} lines)")
                return LyricsResult(lines, isSynced = false, source = LyricsSource.LRCLIB_PLAIN)
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // FLAC Vorbis Comment parser
    // -------------------------------------------------------------------------

    private fun readFlacVorbisLyrics(path: String): String? {
        return try {
            val bytes = java.io.File(path).readBytes()
            parseFlacLyrics(bytes)
        } catch (e: Exception) {
            L.d("FLAC lyrics via path failed for $path: $e")
            null
        }
    }

    private fun readFlacVorbisLyricsFromUri(uri: Uri): String? {
        return try {
            val bytes =
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return null
            parseFlacLyrics(bytes)
        } catch (e: Exception) {
            L.d("FLAC lyrics via URI failed for $uri: $e")
            null
        }
    }

    private fun parseFlacLyrics(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        if (
            bytes[0] != 0x66.toByte() ||
                bytes[1] != 0x4C.toByte() ||
                bytes[2] != 0x61.toByte() ||
                bytes[3] != 0x43.toByte()
        )
            return null
        var pos = 4
        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val isLast = (header and 0x80) != 0
            val blockType = header and 0x7F
            val length =
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                    (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (pos + length > bytes.size) break
            if (blockType == 4) {
                val lyrics = parseVorbisCommentBlock(bytes, pos, length)
                if (lyrics != null) return lyrics
            }
            pos += length
            if (isLast) break
        }
        return null
    }

    private fun parseVorbisCommentBlock(bytes: ByteArray, start: Int, length: Int): String? {
        var pos = start
        val end = start + length
        if (pos + 4 > end) return null
        val vendorLen = readLeInt(bytes, pos)
        pos += 4 + vendorLen
        if (pos + 4 > end) return null
        val commentCount = readLeInt(bytes, pos)
        pos += 4
        repeat(commentCount) {
            if (pos + 4 > end) return null
            val commentLen = readLeInt(bytes, pos)
            pos += 4
            if (pos + commentLen > end) return null
            val comment = String(bytes, pos, commentLen, Charsets.UTF_8)
            pos += commentLen
            val upper = comment.uppercase()
            if (upper.startsWith("LYRICS=") || upper.startsWith("UNSYNCEDLYRICS=")) {
                val sepIdx = comment.indexOf('=')
                if (sepIdx >= 0) {
                    val value = comment.substring(sepIdx + 1).trim()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return null
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    // -------------------------------------------------------------------------
    // MediaMetadataRetriever — MP3 / M4A / OGG / Opus (Android 9+ only)
    // -------------------------------------------------------------------------

    private fun readEmbeddedFromPath(path: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val raw = retriever.extractMetadata(28)
            if (raw.isNullOrBlank()) null else raw.trim()
        } catch (e: Exception) {
            L.d("Embedded lyrics unavailable via path $path: $e")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun readEmbeddedFromUri(uri: Uri, song: Song): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val raw = retriever.extractMetadata(28)
            if (raw.isNullOrBlank()) null else raw.trim()
        } catch (e: Exception) {
            L.d("Embedded lyrics unavailable via URI for ${song.path.name}: $e")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // -------------------------------------------------------------------------
    // LRCLIB — Room cache then network
    // -------------------------------------------------------------------------

    private suspend fun fetchFromLrclib(
        artist: String,
        title: String,
        album: String,
        duration: Int,
    ): LrclibResult? {
        val cached = lrclibCache.get(artist, title)
        if (cached != null) {
            return if (cached.noResult) null
            else LrclibResult(syncedLyrics = cached.syncedLyrics, plainLyrics = cached.plainLyrics)
        }
        val result = lrclibApi.search(title, artist, album, duration)
        if (result != null) lrclibCache.saveResult(artist, title, result)
        else lrclibCache.saveNoResult(artist, title)
        return result
    }

    // -------------------------------------------------------------------------
    // Local .lrc helpers
    // -------------------------------------------------------------------------

    private fun readLrcForSong(song: Song): String? {
        val songFileName = song.path.name ?: return null
        val lrcFileName = songFileName.replaceAfterLast('.', "lrc", "$songFileName.lrc")
        val dataPath = getDataPath(song.uri)
        if (dataPath != null) {
            val lrcPath = dataPath.replaceAfterLast('.', "lrc", "$dataPath.lrc")
            val result = readFile(lrcPath)
            if (result != null) return result
        }
        return searchMediaStore(lrcFileName)
    }

    @Suppress("DEPRECATION")
    private fun getDataPath(uri: Uri): String? {
        return try {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (e: Exception) {
            L.d("DATA column unavailable: $e")
            null
        }
    }

    private fun readFile(path: String): String? {
        return try {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            L.d("Could not read $path: $e")
            null
        }
    }

    private fun searchMediaStore(lrcFileName: String): String? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
        return try {
            context.contentResolver
                .query(collection, projection, selection, arrayOf(lrcFileName), null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                            )
                        readUri(
                            context.contentResolver,
                            Uri.withAppendedPath(collection, id.toString()),
                        )
                    } else null
                }
        } catch (e: Exception) {
            L.e("MediaStore search failed for $lrcFileName: $e")
            null
        }
    }

    private fun readUri(contentResolver: ContentResolver, uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use {
                it.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            L.e("Failed to read URI $uri: $e")
            null
        }
    }
}