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
package com.aprs.radiochat.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.aprs.radiochat.BuildConfig

@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val qrzUrl = "https://www.qrz.com/db/HI3LAG"

    SettingsScaffold(title = "About", onBack = onBack) { padding ->
        SettingsScrollColumn(padding) {
            SectionCard(title = "RadioChat APRS") {
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "APRS client for the Radtel RT-950 Pro (KISS BLE), " +
                        "DireWolf (KISS TCP) and APRS-IS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Developed by: Luis Almonte (HI3LAG)")
                TextButton(onClick = { uriHandler.openUri(qrzUrl) }) {
                    Text("QRZ: $qrzUrl")
                }
            }
        }
    }
}
