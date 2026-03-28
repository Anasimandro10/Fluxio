/*
 * Copyright (c) 2021 Fluxio Project
 * LangUtil.kt is part of Fluxio.
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
package org.oxycblt.auxio.util

import kotlin.reflect.KClass
import org.oxycblt.auxio.BuildConfig

/**
 * Sanitizes a value that is unlikely to be null. On debug builds, this aliases to [requireNotNull],
 * otherwise, it aliases to the unchecked dereference operator (!!). This can be used as a minor
 * optimization in certain cases.
 */
fun <T> unlikelyToBeNull(value: T?) =
    if (BuildConfig.DEBUG) {
        requireNotNull(value)
    } else {
        value!!
    }

/**
 * Aliases a check to ensure that the given number is non-zero.
 *
 * @return The given number if it's non-zero, null otherwise.
 */
fun Int.positiveOrNull() = if (this > 0) this else null

/**
 * Lazily set up a reflected field. Automatically handles visibility changes. Adapted from Material
 * Files: https://github.com/zhanghai/MaterialFiles
 *
 * @param clazz The [KClass] to reflect into.
 * @param field The name of the field to obtain.
 */
fun lazyReflectedField(clazz: KClass<*>, field: String) = lazy {
    clazz.java.getDeclaredField(field).also { it.isAccessible = true }
}

/**
 * Lazily set up a reflected method. Automatically handles visibility changes. Adapted from Material
 * Files: https://github.com/zhanghai/MaterialFiles
 *
 * @param clazz The [KClass] to reflect into.
 * @param method The name of the method to obtain.
 */
fun lazyReflectedMethod(clazz: KClass<*>, method: String, vararg params: KClass<*>) = lazy {
    clazz.java.getDeclaredMethod(method, *params.map { it.java }.toTypedArray()).also {
        it.isAccessible = true
    }
}

/**
 * Split a [String] by the given selector, automatically handling escaped characters that satisfy
 * the selector.
 *
 * @param selector A block that determines if the string should be split at a given character.
 * @return One or more [String]s split by the selector.
 */
/**
 * Split a [String] by the given selector, automatically handling escaped characters that satisfy
 * the selector.
 *
 * @param selector A block that determines if the string should be split at a given character.
 * @return One or more [String]s split by the selector.
 */
internal fun String.splitEscaped(selector: Char): List<String> {
    val split = mutableListOf<String>()
    // StringBuilder avoids creating a new String object on every character append.
    val currentString = StringBuilder()
    var i = 0

    while (i < length) {
        val a = get(i)
        val b = getOrNull(i + 1)

        if (a == selector) {
            // Non-escaped separator — flush the buffer and start a new segment.
            split.add(currentString.toString())
            currentString.clear()
            i++
            continue
        }

        if (b != null && a == '\\' && b == selector) {
            // Escaped separator — add the unescaped character and skip 2 positions.
            currentString.append(b)
            i += 2
        } else {
            // Regular character.
            currentString.append(a)
            i++
        }
    }

    if (currentString.isNotEmpty()) {
        split.add(currentString.toString())
    }

    return split
}
