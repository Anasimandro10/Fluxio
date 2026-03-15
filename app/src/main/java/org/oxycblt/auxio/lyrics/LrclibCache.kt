/*
 * Copyright (c) 2026 Fluxio Project
 * LrclibCache.kt is part of Fluxio.
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

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room database that stores lyrics fetched from LRCLIB.
 *
 * Each row stores the result (or the fact that no result was found) for a song identified by
 * artist + title. This prevents repeated network calls for the same song.
 */
@Database(entities = [LrclibCacheEntry::class], version = 1, exportSchema = false)
abstract class LrclibDatabase : RoomDatabase() {
    abstract fun lrclibCacheDao(): LrclibCacheDao
}

/**
 * A single cached LRCLIB result.
 *
 * @param cacheKey Unique key: "$artistName||$trackTitle" (lowercase).
 * @param syncedLyrics LRC-format synced lyrics, or null.
 * @param plainLyrics Plain text lyrics, or null.
 * @param fetchedAt Unix timestamp in milliseconds when this entry was saved.
 * @param noResult True if we searched LRCLIB and found nothing — avoids re-querying.
 */
@Entity(tableName = "lrclib_cache")
data class LrclibCacheEntry(
    @PrimaryKey val cacheKey: String,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val fetchedAt: Long,
    val noResult: Boolean,
)

/** Provides access to the lrclib_cache table. */
@Dao
interface LrclibCacheDao {
    /** Insert or replace an entry. */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: LrclibCacheEntry)

    /** Get a cached entry by its key. Returns null if not cached yet. */
    @Query("SELECT * FROM lrclib_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): LrclibCacheEntry?

    /** Delete all cached entries. Called from the "Clear lyrics cache" settings button. */
    @Query("DELETE FROM lrclib_cache") suspend fun nukeAll()

    /** Delete only entries where no result was found. Called when LRCLIB is enabled so previously
     *  missed songs get a fresh attempt on next playback. */
    @Query("DELETE FROM lrclib_cache WHERE noResult = 1") suspend fun deleteNoResults()

    /** Delete the cached entry for a specific song (for the "refresh" button per song). */
    @Query("DELETE FROM lrclib_cache WHERE cacheKey = :key") suspend fun delete(key: String)

    /** Count total cached entries. */
    @Query("SELECT COUNT(*) FROM lrclib_cache") suspend fun count(): Int
}

/**
 * Repository wrapper around [LrclibCacheDao] for use in [LyricsRepository].
 *
 * Provides the cache key logic and a clean API for storing/reading LRCLIB results.
 */
@Singleton
class LrclibCache @Inject constructor(private val dao: LrclibCacheDao) {

    /** Builds the cache key for a song. Lowercase to avoid case mismatches. */
    fun keyFor(artistName: String, trackTitle: String): String =
        "${artistName.lowercase()}||${trackTitle.lowercase()}"

    /** Returns the cached entry for this song, or null if not yet cached. */
    suspend fun get(artistName: String, trackTitle: String): LrclibCacheEntry? =
        dao.get(keyFor(artistName, trackTitle))

    /** Saves a successful LRCLIB result to the cache. */
    suspend fun saveResult(artistName: String, trackTitle: String, result: LrclibResult) {
        dao.upsert(
            LrclibCacheEntry(
                cacheKey = keyFor(artistName, trackTitle),
                syncedLyrics = result.syncedLyrics,
                plainLyrics = result.plainLyrics,
                fetchedAt = System.currentTimeMillis(),
                noResult = false,
            )
        )
    }

    /** Saves a "no result" marker so we don't query LRCLIB again for this song. */
    suspend fun saveNoResult(artistName: String, trackTitle: String) {
        dao.upsert(
            LrclibCacheEntry(
                cacheKey = keyFor(artistName, trackTitle),
                syncedLyrics = null,
                plainLyrics = null,
                fetchedAt = System.currentTimeMillis(),
                noResult = true,
            )
        )
    }

    /** Deletes the cache entry for this song (used by the "refresh" button). */
    suspend fun invalidate(artistName: String, trackTitle: String) =
        dao.delete(keyFor(artistName, trackTitle))

    /** Deletes all "no result" entries so previously missed songs get a fresh search. */
    suspend fun clearNoResults() = dao.deleteNoResults()

    /** Deletes all cached entries. */
    suspend fun clearAll() = dao.nukeAll()

    /** Returns the number of cached entries. */
    suspend fun count(): Int = dao.count()
}