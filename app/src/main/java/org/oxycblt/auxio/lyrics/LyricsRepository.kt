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
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Loads LRC lyric files from local storage.
 *
 * Strategy: given the song's MediaStore URI (content://media/external/audio/media/ID), query
 * MediaStore for the file's DATA path, replace the audio extension with .lrc, then try to open that
 * path directly. Falls back to a display-name search if needed.
 */
@Singleton
class LyricsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Attempts to load and parse an LRC file for the given song.
     *
     * @param song The song to find lyrics for.
     * @return A sorted list of [LrcLine], or null if no LRC file was found.
     */
    suspend fun loadLrc(song: Song): List<LrcLine>? =
        withContext(Dispatchers.IO) {
            val content = readLrcForSong(song) ?: return@withContext null
            val lines = LrcParser.parse(content)
            if (lines.isEmpty()) null else lines
        }

    /**
     * Tries two strategies to find and read the .lrc file:
     * 1. Get the file path from MediaStore DATA column, swap extension to .lrc, open directly.
     * 2. Search MediaStore Files table by display name as fallback.
     */
    private fun readLrcForSong(song: Song): String? {
        val songFileName = song.path.name ?: return null
        val lrcFileName = songFileName.replaceAfterLast('.', "lrc", "$songFileName.lrc")

        L.d("Looking for LRC: $lrcFileName alongside ${song.uri}")

        // Strategy 1: get the real file path via DATA column and open it directly
        val dataPath = getDataPath(song.uri)
        if (dataPath != null) {
            val lrcPath = dataPath.replaceAfterLast('.', "lrc", "$dataPath.lrc")
            L.d("Trying direct path: $lrcPath")
            val result = readFile(lrcPath)
            if (result != null) return result
        }

        // Strategy 2: search MediaStore Files table by display name
        L.d("Trying MediaStore Files search for: $lrcFileName")
        return searchMediaStore(lrcFileName)
    }

    /**
     * Reads the DATA (file system path) for a MediaStore audio URI. Returns null if the column is
     * unavailable (e.g. SAF URI).
     */
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

    /** Opens a file by absolute path and reads its text content. */
    private fun readFile(path: String): String? {
        return try {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) {
                file.readText(Charsets.UTF_8)
            } else {
                null
            }
        } catch (e: Exception) {
            L.d("Could not read file $path: $e")
            null
        }
    }

    /**
     * Searches MediaStore Files table for a file with the given display name. Tries multiple MIME
     * types since .lrc files are often unrecognized.
     */
    private fun searchMediaStore(lrcFileName: String): String? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        // Cast a wide net — .lrc files may be indexed with any of these types
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

    /** Reads the full text content of a content URI. Returns null on failure. */
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
