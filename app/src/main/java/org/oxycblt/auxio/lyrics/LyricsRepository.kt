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
import java.io.BufferedInputStream
import java.io.InputStream
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
 *     - If synced (LRC format): returned immediately.
 *     - If plain text and [LyricsSettings.lrclibPreferSynced] is enabled: LRCLIB is checked for a
 *       synced version only. LRCLIB plain text is NOT accepted as a replacement — embedded plain is
 *       returned as fallback in that case.
 *     - If plain text and [LyricsSettings.lrclibPreferSynced] is disabled: returned immediately.
 * 4. LRCLIB (Room disk cache → network) — only if enabled in settings
 *
 * Call [clearMemoryCache] when settings that affect the lookup order change so cached results are
 * re-evaluated on next playback.
 *
 * FLAC files are parsed incrementally — only the metadata blocks at the start of the file are read,
 * never the audio data. This prevents OOM crashes on large FLAC files (90–120 MB).
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
                // When lrclibPreferSynced is on, only accept LRCLIB if it has a synced version.
                // If LRCLIB only has plain text, use the embedded lyrics.
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
     * discarded. Used by the lrclibPreferSynced path.
     */
    private suspend fun tryLrclibSyncedOnly(song: Song): LyricsResult? {
        val artist = song.artists.firstOrNull()?.name?.resolve(context) ?: ""
        val title = song.name.resolve(context)
        val album = song.album.name.resolve(context)
        val duration = (song.durationMs / 1000).toInt()
        val remote = fetchFromLrclib(artist, title, album, duration) ?: return null
        val synced = remote.syncedLyrics ?: return null
        val lines = LrcParser.parse(synced)
        return if (lines.isNotEmpty())
            LyricsResult(lines, isSynced = true, source = LyricsSource.LRCLIB_SYNCED)
        else null
    }

    /**
     * Queries LRCLIB (Room cache first, then network) and returns the best available result (synced
     * preferred, plain as fallback), or null if nothing is found.
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
    // FLAC Vorbis Comment parser — incremental (no full-file load)
    // -------------------------------------------------------------------------

    /**
     * Reads FLAC Vorbis Comment lyrics from a file path using an incremental stream reader. Only
     * the metadata blocks at the start of the file are read — the audio data is never touched. This
     * prevents OOM errors on large FLAC files.
     */
    private fun readFlacVorbisLyrics(path: String): String? {
        return try {
            java.io.FileInputStream(path).use { fis -> parseFlacStream(BufferedInputStream(fis)) }
        } catch (e: Exception) {
            L.d("FLAC lyrics via path failed for $path: $e")
            null
        }
    }

    /**
     * Reads FLAC Vorbis Comment lyrics via a content URI using an incremental stream reader. Only
     * the metadata blocks are read — the audio data is never loaded into memory.
     */
    private fun readFlacVorbisLyricsFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                parseFlacStream(BufferedInputStream(raw))
            }
        } catch (e: Exception) {
            L.d("FLAC lyrics via URI failed for $uri: $e")
            null
        }
    }

    /**
     * Parses FLAC metadata blocks from a stream without loading the audio data.
     *
     * Reads exactly as many bytes as needed to walk the metadata block chain, then stops. A 1 MB
     * safety cap prevents pathological inputs from consuming too much memory.
     *
     * FLAC structure: 4 bytes magic: "fLaC" Sequence of metadata blocks, each with: 1 byte: bit7 =
     * last-block flag, bits6-0 = block type 3 bytes: block data length (big-endian) N bytes: block
     * data Block type 4 = VORBIS_COMMENT
     */
    private fun parseFlacStream(stream: BufferedInputStream): String? {
        val magic = ByteArray(4)
        if (stream.read(magic) != 4) return null
        // Verify FLAC magic: f L a C
        if (
            magic[0] != 0x66.toByte() ||
                magic[1] != 0x4C.toByte() ||
                magic[2] != 0x61.toByte() ||
                magic[3] != 0x43.toByte()
        )
            return null

        val header = ByteArray(4)
        var totalRead = 4
        // 1 MB safety cap — metadata blocks are never this large in practice
        val maxBytes = 1 * 1024 * 1024

        while (totalRead < maxBytes) {
            if (stream.read(header) != 4) break
            totalRead += 4

            val blockHeader = header[0].toInt() and 0xFF
            val isLast = (blockHeader and 0x80) != 0
            val blockType = blockHeader and 0x7F
            val length =
                ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)

            if (length <= 0 || totalRead + length > maxBytes) break

            if (blockType == 4) {
                // Vorbis Comment block — read it fully and parse
                val blockData = readStreamIncrementally(stream, length) ?: break
                totalRead += length
                val result = parseVorbisCommentBlock(blockData, 0, length)
                if (result != null) return result
            } else {
                // Skip this block by reading and discarding its bytes
                val skipped = stream.skip(length.toLong())
                totalRead += skipped.toInt()
                if (skipped < length) break
            }

            if (isLast) break
        }
        return null
    }

    /**
     * Reads exactly [length] bytes from [stream] into a new ByteArray. Returns null if the stream
     * ends before [length] bytes are available.
     */
    private fun readStreamIncrementally(stream: InputStream, length: Int): ByteArray? {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = stream.read(buf, offset, length - offset)
            if (n < 0) return null
            offset += n
        }
        return buf
    }

    /**
     * Parses a Vorbis Comment block and returns the value of the LYRICS or UNSYNCEDLYRICS field, or
     * null if neither is present. All field name comparisons are case-insensitive.
     */
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

    /** Reads a 4-byte little-endian unsigned integer from [bytes] at [offset]. */
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
            try {
                retriever.release()
            } catch (_: Exception) {}
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
            try {
                retriever.release()
            } catch (_: Exception) {}
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
