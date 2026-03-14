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
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.music.resolve
import org.oxycblt.musikr.Song
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
    LRCLIB_SYNCED,
    LRCLIB_PLAIN,
}

/**
 * Loads lyrics for a song using this priority order:
 * 1. Local .lrc file next to the audio file → synced → stops here
 * 2. LRCLIB with synced LRC → stops here (only if enabled in settings)
 * 3. LRCLIB with plain text → shows with "no sync" notice
 * 4. Nothing → returns null
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

    /**
     * Attempts to load lyrics for the given song using the priority order above.
     *
     * @param song The song to find lyrics for.
     * @return [LyricsResult] if lyrics were found, null otherwise.
     */
    suspend fun loadLyrics(song: Song): LyricsResult? =
        withContext(Dispatchers.IO) {
            // Step 1: local .lrc file
            val localContent = readLrcForSong(song)
            if (localContent != null) {
                val lines = LrcParser.parse(localContent)
                if (lines.isNotEmpty()) {
                    L.d("Lyrics found: local LRC (${lines.size} lines)")
                    return@withContext
                    LyricsResult(lines, isSynced = true, source = LyricsSource.LOCAL_LRC)
                }
            }

            // Step 2 + 3: LRCLIB fallback (only if enabled in settings)
            if (!lyricsSettings.lrclibEnabled) {
                L.d("LRCLIB disabled — no lyrics for ${song.path.name}")
                return@withContext null
            }

            val artistName = song.artists.firstOrNull()?.name?.resolve(context) ?: ""
            val trackTitle = song.name.resolve(context)
            val albumName = song.album?.name?.resolve(context) ?: ""
            val durationSeconds = (song.durationMs / 1000).toInt()

            val lrclibResult =
                fetchFromLrclib(artistName, trackTitle, albumName, durationSeconds)
                    ?: return@withContext null

            // Prefer synced over plain
            val synced = lrclibResult.syncedLyrics
            if (synced != null) {
                val lines = LrcParser.parse(synced)
                if (lines.isNotEmpty()) {
                    L.d("Lyrics found: LRCLIB synced (${lines.size} lines)")
                    return@withContext
                    LyricsResult(lines, isSynced = true, source = LyricsSource.LRCLIB_SYNCED)
                }
            }

            val plain = lrclibResult.plainLyrics
            if (plain != null && plain.isNotBlank()) {
                val lines =
                    plain
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { LrcLine(startMs = 0L, text = it) }
                if (lines.isNotEmpty()) {
                    L.d("Lyrics found: LRCLIB plain text (${lines.size} lines)")
                    return@withContext
                    LyricsResult(lines, isSynced = false, source = LyricsSource.LRCLIB_PLAIN)
                }
            }

            L.d("No lyrics found for ${song.path.name}")
            null
        }

    /**
     * Deletes the LRCLIB cache entry for this song, forcing a fresh fetch next time. Used by the
     * "refresh" button in the lyrics screen.
     */
    suspend fun invalidateLrclibCache(song: Song) {
        val artistName = song.artists.firstOrNull()?.name?.resolve(context) ?: ""
        val trackTitle = song.name.resolve(context)
        lrclibCache.invalidate(artistName, trackTitle)
    }

    /** Deletes all cached LRCLIB entries. Called from Settings. */
    suspend fun clearLrclibCache() = lrclibCache.clearAll()

    /** Returns the number of entries in the LRCLIB cache. */
    suspend fun lrclibCacheCount(): Int = lrclibCache.count()

    // -------------------------------------------------------------------------
    // LRCLIB helpers
    // -------------------------------------------------------------------------

    /**
     * Fetches lyrics from the LRCLIB cache or network. Returns null if the song was already looked
     * up and nothing was found.
     */
    private suspend fun fetchFromLrclib(
        artistName: String,
        trackTitle: String,
        albumName: String,
        durationSeconds: Int,
    ): LrclibResult? {
        // Check cache first
        val cached = lrclibCache.get(artistName, trackTitle)
        if (cached != null) {
            return if (cached.noResult) {
                L.d("LRCLIB cache: no result for '$trackTitle'")
                null
            } else {
                L.d("LRCLIB cache hit for '$trackTitle'")
                LrclibResult(syncedLyrics = cached.syncedLyrics, plainLyrics = cached.plainLyrics)
            }
        }

        // Cache miss — call the API
        val result = lrclibApi.search(trackTitle, artistName, albumName, durationSeconds)
        if (result != null) {
            lrclibCache.saveResult(artistName, trackTitle, result)
        } else {
            lrclibCache.saveNoResult(artistName, trackTitle)
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Local LRC file helpers (unchanged from original)
    // -------------------------------------------------------------------------

    private fun readLrcForSong(song: Song): String? {
        val songFileName = song.path.name ?: return null
        val lrcFileName = songFileName.replaceAfterLast('.', "lrc", "$songFileName.lrc")
        L.d("Looking for LRC: $lrcFileName alongside ${song.uri}")

        val dataPath = getDataPath(song.uri)
        if (dataPath != null) {
            val lrcPath = dataPath.replaceAfterLast('.', "lrc", "$dataPath.lrc")
            L.d("Trying direct path: $lrcPath")
            val result = readFile(lrcPath)
            if (result != null) return result
        }

        L.d("Trying MediaStore Files search for: $lrcFileName")
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
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            L.d("DATA column unavailable for $uri: $e")
            null
        }
    }

    private fun readFile(path: String): String? {
        return try {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            L.d("Could not read file $path: $e")
            null
        }
    }

    private fun searchMediaStore(lrcFileName: String): String? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(lrcFileName)
        return try {
            context.contentResolver
                .query(collection, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                            )
                        val uri = Uri.withAppendedPath(collection, id.toString())
                        readUri(context.contentResolver, uri)
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            L.e("MediaStore search failed for $lrcFileName: $e")
            null
        }
    }

    private fun readUri(contentResolver: ContentResolver, uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            L.e("Failed to read URI $uri: $e")
            null
        }
    }
}
