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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aprs.radiochat.ui.connection.ConnectionViewModel

@Composable
fun StationSettingsScreen(
    viewModel: ConnectionViewModel,
    onBack: () -> Unit
) {
    val callsign by viewModel.myCallsign.collectAsStateWithLifecycle()
    val ownPosition by viewModel.ownPosition.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Your station", onBack = onBack) { padding ->
        SettingsScrollColumn(padding) {
            SectionCard(title = "Callsign") {
                OutlinedTextField(
                    value = callsign,
                    onValueChange = viewModel::setCallsign,
                    label = { Text("Callsign with SSID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("Must match the radio / DireWolf (e.g. HI3LAG-7)")
                    }
                )
            }
            SectionCard(title = "Map position") {
                if (ownPosition != null) {
                    Text(
                        "%.5f, %.5f".format(ownPosition!!.latitude, ownPosition!!.longitude),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val origin = when {
                        ownPosition!!.fromPhoneGps -> "Phone GPS"
                        ownPosition!!.fromRadioBeacon -> "RF beacon"
                        else -> "Saved"
                    }
                    Text(
                        "${ownPosition!!.callsign} · ${ownPosition!!.symbol} · $origin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "No position yet. Enable the GPS beacon or connect the radio.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
