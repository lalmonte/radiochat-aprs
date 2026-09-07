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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aprs.radiochat.data.location.BatteryExemption
import com.aprs.radiochat.data.service.AprsTrackingService
import com.aprs.radiochat.ui.connection.ConnectionViewModel

@Composable
fun BeaconSettingsScreen(
    viewModel: ConnectionViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val beaconEnabled by viewModel.beaconEnabled.collectAsStateWithLifecycle()
    val beaconInterval by viewModel.beaconIntervalSec.collectAsStateWithLifecycle()
    val beaconSending by viewModel.beaconSending.collectAsStateWithLifecycle()
    val beaconComment by viewModel.beaconComment.collectAsStateWithLifecycle()
    val beaconSymbol by viewModel.beaconSymbol.collectAsStateWithLifecycle()
    val gpsFix by viewModel.gpsFix.collectAsStateWithLifecycle()
    val beaconStatus by viewModel.beaconStatus.collectAsStateWithLifecycle()

    SettingsScaffold(title = "GPS beacon", onBack = onBack) { padding ->
        SettingsScrollColumn(padding) {
            SectionCard(title = "Automatic beacon") {
                Text(
                    "Sends your position over APRS-IS and/or KISS TCP. Keeps running in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable beacon", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Phone GPS with the screen off",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = beaconEnabled,
                        onCheckedChange = { on ->
                            viewModel.setBeaconEnabled(on)
                            if (on) {
                                AprsTrackingService.start(context)
                                BatteryExemption.requestIfNeeded(context)
                            }
                        }
                    )
                }
                OutlinedTextField(
                    value = beaconInterval.toString(),
                    onValueChange = viewModel::setBeaconIntervalText,
                    label = { Text("Interval (seconds)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Min. 60 · max. 3600") }
                )
                OutlinedTextField(
                    value = beaconSymbol,
                    onValueChange = viewModel::setBeaconSymbol,
                    label = { Text("APRS symbol") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("/\$ = phone · /> = car") }
                )
                OutlinedTextField(
                    value = beaconComment,
                    onValueChange = viewModel::setBeaconComment,
                    label = { Text("Comment") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                StatusLine(
                    "GPS",
                    if (gpsFix != null) {
                        "%.5f, %.5f (±%.0fm)".format(
                            gpsFix!!.latitude,
                            gpsFix!!.longitude,
                            gpsFix!!.accuracyM
                        )
                    } else if (beaconEnabled) {
                        "Waiting for fix…"
                    } else {
                        "Idle"
                    }
                )
                if (!beaconStatus.isNullOrBlank()) {
                    Text(
                        beaconStatus!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = viewModel::sendBeaconNow,
                    enabled = !beaconSending,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (beaconSending) "Sending…" else "Send beacon now")
                }
            }
        }
    }
}
