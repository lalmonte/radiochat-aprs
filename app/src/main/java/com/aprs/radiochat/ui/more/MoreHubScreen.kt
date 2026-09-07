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
package com.aprs.radiochat.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aprs.radiochat.data.model.AprsIsConnectionState
import com.aprs.radiochat.data.model.BleConnectionState
import com.aprs.radiochat.data.model.TcpTncConnectionState
import com.aprs.radiochat.ui.connection.ConnectionViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MoreHubScreen(
    viewModel: ConnectionViewModel,
    onOpenStation: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenBeacon: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val callsign by viewModel.myCallsign.collectAsStateWithLifecycle()
    val bleState by viewModel.connectionState.collectAsStateWithLifecycle()
    val tcpState by viewModel.tcpTncState.collectAsStateWithLifecycle()
    val isState by viewModel.aprsIsState.collectAsStateWithLifecycle()
    val beaconEnabled by viewModel.beaconEnabled.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("More")
                        Text(
                            callsign,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Link status", style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinkChip(
                            label = "BLE",
                            ok = bleState is BleConnectionState.Connected,
                            detail = linkBleLabel(bleState)
                        )
                        LinkChip(
                            label = "TCP",
                            ok = tcpState is TcpTncConnectionState.Connected,
                            detail = linkTcpLabel(tcpState)
                        )
                        LinkChip(
                            label = "IS",
                            ok = isState is AprsIsConnectionState.Connected,
                            detail = linkIsLabel(isState)
                        )
                        LinkChip(
                            label = "Beacon",
                            ok = beaconEnabled,
                            detail = if (beaconEnabled) "On" else "Off"
                        )
                    }
                    Text(
                        "Set up your links in \"Links & network\". Chat and map work " +
                            "as soon as at least one link is up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "Settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )

            SettingsEntry(
                title = "Your station",
                subtitle = "Callsign and position on the map",
                icon = Icons.Filled.Person,
                onClick = onOpenStation
            )
            SettingsEntry(
                title = "Links & network",
                subtitle = "Bluetooth, DireWolf, APRS-IS and iGate",
                icon = Icons.Filled.Radio,
                onClick = onOpenConnections
            )
            SettingsEntry(
                title = "GPS beacon",
                subtitle = "Automatic position in the background",
                icon = Icons.Filled.LocationOn,
                onClick = onOpenBeacon
            )
            SettingsEntry(
                title = "Notifications",
                subtitle = "Messages, ACKs and dropped links",
                icon = Icons.Filled.Notifications,
                onClick = onOpenNotifications
            )
            SettingsEntry(
                title = "About",
                subtitle = "Version and credits",
                icon = Icons.Filled.Info,
                onClick = onOpenAbout
            )

            Text(
                "Day to day: Chat → Map → Logs. This section is for setting up the station.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        )
    }
}

@Composable
private fun LinkChip(label: String, ok: Boolean, detail: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
            )
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

private fun linkBleLabel(state: BleConnectionState): String = when (state) {
    is BleConnectionState.Connected -> "Connected"
    BleConnectionState.Scanning, is BleConnectionState.Connecting -> "Connecting…"
    is BleConnectionState.Error -> "Error"
    else -> "Disconnected"
}

private fun linkTcpLabel(state: TcpTncConnectionState): String = when (state) {
    is TcpTncConnectionState.Connected -> "Connected"
    TcpTncConnectionState.Connecting -> "Connecting…"
    is TcpTncConnectionState.Error -> "Error"
    else -> "Disconnected"
}

private fun linkIsLabel(state: AprsIsConnectionState): String = when (state) {
    is AprsIsConnectionState.Connected ->
        if (state.verified) "Verified" else "Unverified"
    AprsIsConnectionState.Connecting -> "Connecting…"
    is AprsIsConnectionState.Error -> "Error"
    else -> "Disconnected"
}
