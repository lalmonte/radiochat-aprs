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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aprs.radiochat.ui.connection.ConnectionViewModel

@Composable
fun NotificationSettingsScreen(
    viewModel: ConnectionViewModel,
    onBack: () -> Unit
) {
    val notifyMessages by viewModel.notifyMessages.collectAsStateWithLifecycle()
    val notifyAck by viewModel.notifyAck.collectAsStateWithLifecycle()
    val notifyConnection by viewModel.notifyConnection.collectAsStateWithLifecycle()
    val notifyBulletins by viewModel.notifyBulletins.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Notifications", onBack = onBack) { padding ->
        SettingsScrollColumn(padding) {
            SectionCard(title = "Alerts") {
                Text(
                    "Require the notification permission (Android 13+). " +
                        "You are not alerted while you are already in that chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NotifySwitch("Incoming messages", notifyMessages, viewModel::setNotifyMessages)
                NotifySwitch("ACK / REJ received", notifyAck, viewModel::setNotifyAck)
                NotifySwitch("Link lost", notifyConnection, viewModel::setNotifyConnection)
                NotifySwitch("BLN* bulletins", notifyBulletins, viewModel::setNotifyBulletins)
            }
        }
    }
}

@Composable
private fun NotifySwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
