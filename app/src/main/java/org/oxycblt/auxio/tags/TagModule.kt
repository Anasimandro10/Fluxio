/*
 * Copyright (c) 2026 Fluxio Project
 * TagModule.kt is part of Fluxio.
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
package org.oxycblt.auxio.tags

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the tag database and its DAO via Hilt. */
@Module
@InstallIn(SingletonComponent::class)
class TagModule {

    @Singleton
    @Provides
    fun tagDatabase(@ApplicationContext context: Context): TagDatabase =
        Room.databaseBuilder(context.applicationContext, TagDatabase::class.java, "fluxio_tags.db")
            .fallbackToDestructiveMigration()
            // WAL allows concurrent reads during writes — avoids blocking the UI
            // when StatsTracker or backup operations write simultaneously.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun tagDao(database: TagDatabase): TagDao = database.tagDao()
}
