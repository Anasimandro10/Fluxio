/*
 * Copyright (c) 2026 Fluxio Project
 * StatsDatabase.kt is part of Fluxio.
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
 
package org.oxycblt.auxio.stats

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Provides raw access to the database storing Fluxio's playback history.
 *
 * Each row represents one song listened to for 30 seconds or more.
 */
@Database(
    entities = [PlaybackRecord::class],
    version = 1,
    exportSchema = false,
)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun playbackRecordDao(): PlaybackRecordDao
}

/**
 * A single recorded playback event.
 *
 * @param id auto-generated unique identifier
 * @param songTitle name of the song
 * @param artistName name of the artist
 * @param albumName name of the album
 * @param startedAt Unix timestamp in milliseconds when playback began
 * @param secondsPlayed real seconds listened (minimum 30 to be saved)
 */
@Entity(tableName = "playback_records")
data class PlaybackRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songTitle: String,
    val artistName: String,
    val albumName: String,
    val startedAt: Long,
    val secondsPlayed: Int,
)

/** Provides access to the playback_records table. */
@Dao
interface PlaybackRecordDao {
    /** Insert a new playback record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PlaybackRecord)

    /** Get all records, newest first. */
    @Query("SELECT * FROM playback_records ORDER BY startedAt DESC")
    fun getAllRecords(): Flow<List<PlaybackRecord>>

    /**
     * Get records since a given timestamp (used to filter by week / month / year).
     *
     * @param fromTimestamp Unix timestamp in milliseconds
     */
    @Query("SELECT * FROM playback_records WHERE startedAt >= :fromTimestamp ORDER BY startedAt DESC")
    fun getRecordsSince(fromTimestamp: Long): Flow<List<PlaybackRecord>>

    /** Returns the total number of stored records. */
    @Query("SELECT COUNT(*) FROM playback_records") suspend fun count(): Int

    /** Deletes all records. */
    @Query("DELETE FROM playback_records") suspend fun nukeAll()
}
