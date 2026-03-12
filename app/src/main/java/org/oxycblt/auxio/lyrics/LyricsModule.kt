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

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for the lyrics feature.
 *
 * [LyricsRepository] and [LyricsViewModel] are injected automatically
 * via @Singleton and @HiltViewModel — no manual @Provides needed here.
 * This module exists as the anchor for the lyrics package in the DI graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object LyricsModule