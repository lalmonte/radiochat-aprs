/*
 * RadioChat APRS — Android APRS client for KISS BLE radios,
 * DireWolf (KISS TCP) and APRS-IS.
 * Copyright (C) 2026 Luis Almonte (HI3LAG)
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
package com.aprs.radiochat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GreenPrimary = Color(0xFF1B5E40)
private val GreenSecondary = Color(0xFF2E7D5B)
private val Amber = Color(0xFFC47B1A)

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    secondary = GreenSecondary,
    tertiary = Amber,
    background = Color(0xFFF4F7F5),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CBC9A),
    secondary = Color(0xFF5FA883),
    tertiary = Color(0xFFE0A45A),
    background = Color(0xFF101612),
    surface = Color(0xFF1A221D)
)

@Composable
fun RadioChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
