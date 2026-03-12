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
 * Strategy: given a song with path "Music/Artist/song.mp3", look for "Music/Artist/song.lrc" in
 * MediaStore. This works with both internal storage and SD cards without direct file access.
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
            val lrcUri = findLrcUri(song) ?: return@withContext null
            readUri(context.contentResolver, lrcUri)?.let { content ->
                val lines = LrcParser.parse(content)
                if (lines.isEmpty()) null else lines
            }
        }

    /** Looks up the MediaStore URI for the .lrc file that matches the given song path. */
    private fun findLrcUri(song: Song): Uri? {
        val songFileName = song.path.name ?: return null

        // Replace the audio extension with .lrc  (e.g. "track.mp3" -> "track.lrc")
        val lrcFileName = songFileName.replaceAfterLast('.', "lrc", "$songFileName.lrc")

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection =
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} IN (?, ?, ?)"
        val selectionArgs =
            arrayOf(lrcFileName, "text/plain", "application/octet-stream", "text/lrc")

        return try {
            context.contentResolver
                .query(collection, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                            )
                        Uri.withAppendedPath(collection, id.toString())
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            L.e("Failed to query MediaStore for LRC file: $e")
            null
        }
    }

    /** Reads the full text content of a URI. Returns null on failure. */
    private fun readUri(contentResolver: ContentResolver, uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            L.e("Failed to read LRC URI $uri: $e")
            null
        }
    }
}
