/*
 * Copyright (c) 2026 Fluxio Project
 * StatsModule.kt is part of Fluxio.
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

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the stats database and its DAO to the rest of the app via Hilt. */
@Module
@InstallIn(SingletonComponent::class)
class StatsModule {

    @Singleton
    @Provides
    fun statsDatabase(@ApplicationContext context: Context): StatsDatabase =
        Room.databaseBuilder(
                context.applicationContext,
                StatsDatabase::class.java,
                "fluxio_stats.db",
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun playbackRecordDao(database: StatsDatabase): PlaybackRecordDao = database.playbackRecordDao()
}
