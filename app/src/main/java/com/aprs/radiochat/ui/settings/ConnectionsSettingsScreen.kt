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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aprs.radiochat.data.model.AprsIsConnectionState
import com.aprs.radiochat.data.model.BleConnectionState
import com.aprs.radiochat.data.model.TcpTncConnectionState
import com.aprs.radiochat.ui.connection.ConnectionViewModel

private enum class RfTab(val label: String) {
    BLE("Bluetooth"),
    TCP("DireWolf")
}

@Composable
fun ConnectionsSettingsScreen(
    viewModel: ConnectionViewModel,
    onBack: () -> Unit
) {
    val bleState by viewModel.connectionState.collectAsStateWithLifecycle()
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val passcode by viewModel.passcode.collectAsStateWithLifecycle()
    val server by viewModel.aprsIsServer.collectAsStateWithLifecycle()
    val rangeKm by viewModel.rangeKm.collectAsStateWithLifecycle()
    val callsign by viewModel.myCallsign.collectAsStateWithLifecycle()
    val aprsIsState by viewModel.aprsIsState.collectAsStateWithLifecycle()
    val iGateEnabled by viewModel.iGateEnabled.collectAsStateWithLifecycle()
    val tcpHost by viewModel.tcpTncHost.collectAsStateWithLifecycle()
    val tcpPort by viewModel.tcpTncPort.collectAsStateWithLifecycle()
    val tcpState by viewModel.tcpTncState.collectAsStateWithLifecycle()
    val tcpActivity by viewModel.tcpTncActivity.collectAsStateWithLifecycle()
    val ownPosition by viewModel.ownPosition.collectAsStateWithLifecycle()
    val computed = viewModel.computedPasscode()

    var rfTab by rememberSaveable { mutableIntStateOf(0) }

    SettingsScaffold(title = "Links & network", onBack = onBack) { padding ->
        SettingsScrollColumn(padding) {
            SectionCard(title = "Radio (RF)") {
                Text(
                    "Receives traffic over Bluetooth (RT-950) or KISS TCP (DireWolf). You can use both.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RfTab.entries.forEachIndexed { index, tab ->
                        FilterChip(
                            selected = rfTab == index,
                            onClick = { rfTab = index },
                            label = { Text(tab.label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                when (rfTab) {
                    0 -> BleSection(
                        state = bleState,
                        devices = devices,
                        onScan = viewModel::startScan,
                        onDisconnect = viewModel::disconnectBle,
                        onConnect = viewModel::connect
                    )
                    else -> TcpSection(
                        host = tcpHost,
                        port = tcpPort,
                        state = tcpState,
                        activity = tcpActivity,
                        onHostChange = viewModel::setTcpHost,
                        onPortChange = viewModel::setTcpPortText,
                        onConnect = viewModel::connectTcpTnc,
                        onDisconnect = viewModel::disconnectTcpTnc
                    )
                }
            }

            SectionCard(title = "APRS-IS (Internet)") {
                OutlinedTextField(
                    value = passcode.toString(),
                    onValueChange = viewModel::setPasscodeText,
                    label = { Text("Passcode") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Computed for $callsign: $computed") },
                    trailingIcon = {
                        TextButton(onClick = viewModel::useComputedPasscode) {
                            Text("Auto")
                        }
                    }
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = viewModel::setServer,
                    label = { Text("Server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("rotate.aprs2.net:14580") }
                )
                OutlinedTextField(
                    value = rangeKm.toString(),
                    onValueChange = viewModel::setRangeKmText,
                    label = { Text("Receive radius (km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            if (ownPosition != null) {
                                "$rangeKm km radius + messages to your callsign and regional prefix"
                            } else {
                                "No position: messages to your callsign and prefix (e.g. HI3*)"
                            }
                        )
                    }
                )
                StatusLine("Status", aprsIsLabel(aprsIsState))
                val isConnected = aprsIsState is AprsIsConnectionState.Connected
                val isConnecting = aprsIsState is AprsIsConnectionState.Connecting
                val isError = aprsIsState is AprsIsConnectionState.Error
                Button(
                    onClick = viewModel::connectAprsIs,
                    enabled = (!isConnected && !isConnecting) && callsign != "N0CALL",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isError) "Reconnect APRS-IS" else "Connect APRS-IS")
                }
                OutlinedButton(
                    onClick = viewModel::disconnectAprsIs,
                    enabled = isConnected || isConnecting || isError,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("iGate RF → Internet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Forwards BLE/TCP packets to APRS-IS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = iGateEnabled,
                        onCheckedChange = viewModel::setIGateEnabled,
                        enabled = isConnected &&
                            (aprsIsState as? AprsIsConnectionState.Connected)?.verified == true
                    )
                }
                if (iGateEnabled) {
                    val gatedCount by viewModel.gatedCount.collectAsStateWithLifecycle()
                    val lastGated by viewModel.lastGated.collectAsStateWithLifecycle()
                    Text(
                        "Gated: $gatedCount" + (lastGated?.let { " · last: $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BleSection(
    state: BleConnectionState,
    devices: List<com.aprs.radiochat.data.model.BleDeviceInfo>,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onConnect: (String) -> Unit
) {
    StatusLine("BLE status", bleLabel(state))
    Button(
        onClick = onScan,
        enabled = state !is BleConnectionState.Scanning &&
            state !is BleConnectionState.Connecting &&
            state !is BleConnectionState.Connected,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Scan for RT-950 Pro") }
    OutlinedButton(
        onClick = onDisconnect,
        enabled = state is BleConnectionState.Connected ||
            state is BleConnectionState.Connecting ||
            state is BleConnectionState.Scanning,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Disconnect / Cancel") }
    if (devices.isNotEmpty()) {
        Text("Devices", style = MaterialTheme.typography.labelLarge)
        devices.forEach { device ->
            DeviceRow(
                device = device,
                enabled = state !is BleConnectionState.Connecting &&
                    state !is BleConnectionState.Connected,
                onClick = { onConnect(device.address) }
            )
        }
    }
}

@Composable
private fun TcpSection(
    host: String,
    port: Int,
    state: TcpTncConnectionState,
    activity: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Text(
        "DireWolf KISSPORT (direwolf.conf: KISSPORT 8001)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = host,
        onValueChange = onHostChange,
        label = { Text("Host / IP") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = port.toString(),
        onValueChange = onPortChange,
        label = { Text("KISS port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    StatusLine("TCP status", tcpLabel(state))
    if (activity != null) {
        StatusLine("Activity", activity)
    }
    val connected = state is TcpTncConnectionState.Connected
    val connecting = state is TcpTncConnectionState.Connecting
    val error = state is TcpTncConnectionState.Error
    Button(
        onClick = onConnect,
        enabled = !connected && !connecting,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (error) "Reconnect TCP TNC" else "Connect TCP TNC") }
    OutlinedButton(
        onClick = onDisconnect,
        enabled = connected || connecting || error,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Disconnect") }
}

private fun bleLabel(state: BleConnectionState): String = when (state) {
    BleConnectionState.Idle -> "Idle"
    BleConnectionState.Scanning -> "Scanning…"
    is BleConnectionState.Connecting -> "Connecting…"
    is BleConnectionState.Connected -> "Connected: ${state.deviceName}"
    BleConnectionState.Disconnected -> "Disconnected"
    is BleConnectionState.Error -> state.message
}

private fun tcpLabel(state: TcpTncConnectionState): String = when (state) {
    TcpTncConnectionState.Disconnected -> "Disconnected"
    TcpTncConnectionState.Connecting -> "Connecting…"
    is TcpTncConnectionState.Connected -> "${state.host}:${state.port}"
    is TcpTncConnectionState.Error -> state.message
}

private fun aprsIsLabel(state: AprsIsConnectionState): String = when (state) {
    AprsIsConnectionState.Disconnected -> "Disconnected"
    AprsIsConnectionState.Connecting -> "Connecting…"
    is AprsIsConnectionState.Connected ->
        "${state.server}" + if (state.verified) " · verified" else " · unverified"
    is AprsIsConnectionState.Error -> state.message
}
