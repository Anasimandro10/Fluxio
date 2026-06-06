/*
 * Copyright (c) 2026 Fluxio Project
 * FluxioTheme.kt is part of Fluxio.
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
package org.oxycblt.auxio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.oxycblt.auxio.R

// Typography
val InterFontFamily =
    FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )

val FluxioTypography =
    Typography(
        // display: 22sp SemiBold
        displayLarge =
            TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
            ),
        // headline: 17sp Regular
        headlineMedium =
            TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 17.sp,
            ),
        // title: 17sp Regular
        titleMedium =
            TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 17.sp,
            ),
        // body: 15sp Regular
        bodyMedium =
            TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
            ),
        // label: 13sp SemiBold
        labelMedium =
            TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            ),
        // caption: 12sp Regular
        bodySmall =
            TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
            ),
        // nav: 11sp Regular
        labelSmall =
            TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
            ),
    )

// Colors
data class FluxioColors(
    val bg: Color,
    val surface: Color,
    val element: Color,
    val separator: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val destructive: Color,
)

val LocalFluxioColors = staticCompositionLocalOf<FluxioColors> { error("No FluxioColors provided") }

object FluxioTheme {
    val colors: FluxioColors
        @Composable @ReadOnlyComposable get() = LocalFluxioColors.current

    val typography: Typography
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography
}

@Composable
fun FluxioTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // We map directly from XML colors since they handle Day/Night automatically.
    val fluxioColors =
        FluxioColors(
            bg = colorResource(id = R.color.fluxio_bg),
            surface = colorResource(id = R.color.fluxio_surface),
            element = colorResource(id = R.color.fluxio_element),
            separator = colorResource(id = R.color.fluxio_separator),
            text1 = colorResource(id = R.color.fluxio_text1),
            text2 = colorResource(id = R.color.fluxio_text2),
            text3 = colorResource(id = R.color.fluxio_text3),
            destructive = colorResource(id = R.color.fluxio_destructive),
        )

    // Map into Material3 scheme so default components look native to Fluxio
    val colorScheme =
        if (darkTheme) {
            darkColorScheme(
                background = fluxioColors.bg,
                surface = fluxioColors.surface,
                surfaceVariant = fluxioColors.element,
                onBackground = fluxioColors.text1,
                onSurface = fluxioColors.text1,
                onSurfaceVariant = fluxioColors.text2,
                error = fluxioColors.destructive,
                onError = Color.White,
            )
        } else {
            lightColorScheme(
                background = fluxioColors.bg,
                surface = fluxioColors.surface,
                surfaceVariant = fluxioColors.element,
                onBackground = fluxioColors.text1,
                onSurface = fluxioColors.text1,
                onSurfaceVariant = fluxioColors.text2,
                error = fluxioColors.destructive,
                onError = Color.White,
            )
        }

    CompositionLocalProvider(LocalFluxioColors provides fluxioColors) {
        MaterialTheme(colorScheme = colorScheme, typography = FluxioTypography, content = content)
    }
}
