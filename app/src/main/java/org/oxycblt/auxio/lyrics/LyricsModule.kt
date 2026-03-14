/*
 * Copyright (c) 2026 Fluxio Project
 * LyricsModule.kt is part of Fluxio.
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

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt module for the lyrics feature. Provides Room database and DAO for LRCLIB cache. */
@Module
@InstallIn(SingletonComponent::class)
object LyricsModule {

    @Provides
    @Singleton
    fun provideLrclibDatabase(@ApplicationContext context: Context): LrclibDatabase =
        Room.databaseBuilder(context, LrclibDatabase::class.java, "lrclib_cache.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideLrclibCacheDao(database: LrclibDatabase): LrclibCacheDao = database.lrclibCacheDao()
}
