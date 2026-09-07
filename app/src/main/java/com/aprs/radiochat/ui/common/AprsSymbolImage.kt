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
package com.aprs.radiochat.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aprs.radiochat.data.aprs.AprsSymbolIcons

/**
 * APRS icon (hessu sheet) for Compose. The bitmap is already cached in [AprsSymbolIcons].
 */
@Composable
fun AprsSymbolImage(
    symbol: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    contentDescription: String? = symbol.ifBlank { "APRS" }
) {
    val context = LocalContext.current
    val image = remember(symbol) {
        AprsSymbolIcons.bitmap(context, symbol.ifBlank { "/." }).asImageBitmap()
    }
    Image(
        bitmap = image,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        filterQuality = FilterQuality.None
    )
}
