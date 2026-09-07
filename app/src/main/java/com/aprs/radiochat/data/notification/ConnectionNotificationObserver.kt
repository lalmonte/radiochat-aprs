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
package com.aprs.radiochat.data.notification

import com.aprs.radiochat.data.aprsis.AprsIsClient
import com.aprs.radiochat.data.ble.BleUartManager
import com.aprs.radiochat.data.model.AprsIsConnectionState
import com.aprs.radiochat.data.model.BleConnectionState
import com.aprs.radiochat.data.model.TcpTncConnectionState
import com.aprs.radiochat.data.repository.SettingsRepository
import com.aprs.radiochat.data.tnc.TcpKissTncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Warns when a link that was connected goes to disconnected or error.
 */
class ConnectionNotificationObserver(
    ble: BleUartManager,
    tcp: TcpKissTncClient,
    aprsIs: AprsIsClient,
    private val settings: SettingsRepository,
    private val notifications: AprsNotificationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            ble.connectionState.collect { state ->
                onBleState(state)
            }
        }
        scope.launch {
            tcp.connectionState.collect { state ->
                onTcpState(state)
            }
        }
        scope.launch {
            aprsIs.connectionState.collect { state ->
                onIsState(state)
            }
        }
    }

    private var bleWasConnected = false
    private var tcpWasConnected = false
    private var isWasConnected = false

    private fun onBleState(state: BleConnectionState) {
        val connected = state is BleConnectionState.Connected
        if (bleWasConnected && !connected) {
            val detail = when (state) {
                is BleConnectionState.Error -> state.message
                BleConnectionState.Disconnected -> "Desconectado"
                else -> state.toString()
            }
            maybeNotifyConnection("BLE", detail)
        }
        bleWasConnected = connected
    }

    private fun onTcpState(state: TcpTncConnectionState) {
        val connected = state is TcpTncConnectionState.Connected
        if (tcpWasConnected && !connected) {
            val detail = when (state) {
                is TcpTncConnectionState.Error -> state.message
                TcpTncConnectionState.Disconnected -> "Desconectado"
                else -> state.toString()
            }
            maybeNotifyConnection("KISS TCP", detail)
        }
        tcpWasConnected = connected
    }

    private fun onIsState(state: AprsIsConnectionState) {
        val connected = state is AprsIsConnectionState.Connected
        if (isWasConnected && !connected) {
            val detail = when (state) {
                is AprsIsConnectionState.Error -> state.message
                AprsIsConnectionState.Disconnected -> "Desconectado"
                else -> state.toString()
            }
            maybeNotifyConnection("APRS-IS", detail)
        }
        isWasConnected = connected
    }

    private fun maybeNotifyConnection(label: String, detail: String) {
        if (!settings.notifyConnection.value) return
        notifications.showConnectionLost(label, detail)
    }
}
