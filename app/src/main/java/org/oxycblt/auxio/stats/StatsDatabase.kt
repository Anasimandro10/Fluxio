/*
 * Copyright (c) 2024 Fluxio Project
 * StatsDatabase.kt is part of Fluxio.
 *
 * This program is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License.
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
 * Base de datos Room que almacena el historial de reproducciones.
 * Cada fila = una canción escuchada al menos 30 segundos.
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
 * Representa una reproducción registrada.
 *
 * @param id            Identificador único (autogenerado)
 * @param songTitle     Nombre de la canción
 * @param artistName    Nombre del artista
 * @param albumName     Nombre del álbum
 * @param startedAt     Timestamp Unix en milisegundos (cuándo empezó)
 * @param secondsPlayed Segundos reales escuchados (mínimo 30)
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

/**
 * Interfaz de acceso a la tabla playback_records.
 */
@Dao
interface PlaybackRecordDao {

    /** Guarda un nuevo registro de reproducción. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PlaybackRecord)

    /** Todos los registros, del más reciente al más antiguo. */
    @Query("SELECT * FROM playback_records ORDER BY startedAt DESC")
    fun getAllRecords(): Flow<List<PlaybackRecord>>

    /** Registros a partir de un momento dado (para filtrar semana/mes/año). */
    @Query("SELECT * FROM playback_records WHERE startedAt >= :fromTimestamp ORDER BY startedAt DESC")
    fun getRecordsSince(fromTimestamp: Long): Flow<List<PlaybackRecord>>

    /** Número total de registros guardados. */
    @Query("SELECT COUNT(*) FROM playback_records")
    suspend fun count(): Int

    /** Borra todo el historial. */
    @Query("DELETE FROM playback_records")
    suspend fun nukeAll()
}