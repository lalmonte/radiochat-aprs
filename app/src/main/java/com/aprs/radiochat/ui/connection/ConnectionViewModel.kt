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
package com.aprs.radiochat.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aprs.radiochat.data.aprsis.AprsIsClient
import com.aprs.radiochat.data.aprsis.IGateService
import com.aprs.radiochat.data.beacon.BeaconService
import com.aprs.radiochat.data.ble.BleUartManager
import com.aprs.radiochat.data.location.PhoneLocationFix
import com.aprs.radiochat.data.location.PhoneLocationTracker
import com.aprs.radiochat.data.model.AprsIsConnectionState
import com.aprs.radiochat.data.model.BleConnectionState
import com.aprs.radiochat.data.model.BleDeviceInfo
import com.aprs.radiochat.data.model.TcpTncConnectionState
import com.aprs.radiochat.data.repository.SettingsRepository
import com.aprs.radiochat.data.tnc.TcpKissTncClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

class ConnectionViewModel(
    private val ble: BleUartManager,
    private val tcpTnc: TcpKissTncClient,
    private val aprsIs: AprsIsClient,
    private val iGate: IGateService,
    private val settings: SettingsRepository,
    private val beacon: BeaconService,
    private val location: PhoneLocationTracker
) : ViewModel() {

    val connectionState: StateFlow<BleConnectionState> = ble.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BleConnectionState.Idle)

    val discoveredDevices: StateFlow<List<BleDeviceInfo>> = ble.discoveredDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tcpTncState: StateFlow<TcpTncConnectionState> = tcpTnc.connectionState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TcpTncConnectionState.Disconnected
        )

    /**
     * KISS TCP link activity. A half-open socket looks the same as a healthy one
     * in [tcpTncState]; what gives it away is that nothing has gone in or out for a while.
     */
    val tcpTncActivity: StateFlow<String?> = flow {
        while (true) {
            emit(
                if (tcpTnc.connectionState.value !is TcpTncConnectionState.Connected) {
                    null
                } else {
                    val now = System.currentTimeMillis()
                    "RX ${sinceLabel(now, tcpTnc.lastRxAtMs)} · TX ${sinceLabel(now, tcpTnc.lastTxAtMs)}"
                }
            )
            delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun sinceLabel(now: Long, at: Long): String {
        if (at <= 0L) return "—"
        val sec = ((now - at) / 1_000L).coerceAtLeast(0L)
        return when {
            sec < 60 -> "${sec}s"
            sec < 3_600 -> "${sec / 60}m"
            else -> "${sec / 3_600}h"
        }
    }

    val myCallsign: StateFlow<String> = settings.myCallsign
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "N0CALL")

    val passcode: StateFlow<Int> = settings.passcode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val rangeKm: StateFlow<Int> = settings.rangeKm
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_RANGE_KM
        )

    val ownPosition = settings.ownPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val aprsIsServer: StateFlow<String> = settings.aprsIsServer
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AprsIsClient.DEFAULT_SERVER
        )

    val tcpTncHost: StateFlow<String> = settings.tcpTncHost
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TcpKissTncClient.DEFAULT_HOST
        )

    val tcpTncPort: StateFlow<Int> = settings.tcpTncPort
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TcpKissTncClient.DEFAULT_PORT
        )

    val iGateEnabled: StateFlow<Boolean> = settings.iGateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val beaconEnabled: StateFlow<Boolean> = settings.beaconEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val beaconIntervalSec: StateFlow<Int> = settings.beaconIntervalSec
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_BEACON_INTERVAL_SEC
        )

    val beaconComment: StateFlow<String> = settings.beaconComment
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_BEACON_COMMENT
        )

    val beaconSymbol: StateFlow<String> = settings.beaconSymbol
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_BEACON_SYMBOL
        )

    val gpsFix: StateFlow<PhoneLocationFix?> = location.fix
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val beaconSending: StateFlow<Boolean> = beacon.sending
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val beaconStatus: StateFlow<String?> = beacon.lastStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val beaconLastTxAt: StateFlow<Long> = beacon.lastTxAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val aprsIsState: StateFlow<AprsIsConnectionState> = aprsIs.connectionState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AprsIsConnectionState.Disconnected
        )

    val gatedCount: StateFlow<Int> = iGate.gatedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val lastGated: StateFlow<String?> = iGate.lastGated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val notifyMessages: StateFlow<Boolean> = settings.notifyMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val notifyAck: StateFlow<Boolean> = settings.notifyAck
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val notifyConnection: StateFlow<Boolean> = settings.notifyConnection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val notifyBulletins: StateFlow<Boolean> = settings.notifyBulletins
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun startScan() = ble.startScan()
    fun disconnectBle() = ble.disconnect()
    fun connect(address: String) = ble.connect(address)
    fun setCallsign(call: String) = settings.setMyCallsign(call)
    fun setServer(server: String) = settings.setAprsIsServer(server)
    fun setIGateEnabled(enabled: Boolean) = settings.setIGateEnabled(enabled)

    fun setTcpHost(host: String) = settings.setTcpTncHost(host)

    fun setTcpPortText(text: String) {
        val digits = text.filter { it.isDigit() }.take(5)
        if (digits.isEmpty()) return
        settings.setTcpTncPort(digits.toIntOrNull() ?: return)
    }

    fun connectTcpTnc() {
        tcpTnc.connect(
            host = settings.tcpTncHost.value,
            port = settings.tcpTncPort.value
        )
    }

    fun disconnectTcpTnc() = tcpTnc.disconnect()

    fun setPasscodeText(text: String) {
        val digits = text.filter { it.isDigit() }.take(5)
        if (digits.isEmpty()) return
        settings.setPasscode(digits.toIntOrNull() ?: return)
    }

    fun useComputedPasscode() = settings.useComputedPasscode()

    fun computedPasscode(): Int = settings.computedPasscode()

    fun setRangeKmText(text: String) {
        val digits = text.filter { it.isDigit() }.take(5)
        if (digits.isEmpty()) return
        val km = digits.toIntOrNull() ?: return
        settings.setRangeKm(km)
        if (aprsIs.connectionState.value is AprsIsConnectionState.Connected) {
            aprsIs.updateFilter(settings.defaultFilter(), immediate = true)
        }
    }

    fun setBeaconEnabled(enabled: Boolean) = settings.setBeaconEnabled(enabled)

    fun setBeaconIntervalText(text: String) {
        val digits = text.filter { it.isDigit() }.take(4)
        if (digits.isEmpty()) return
        settings.setBeaconIntervalSec(digits.toIntOrNull() ?: return)
    }

    fun setBeaconComment(text: String) = settings.setBeaconComment(text)

    fun setBeaconSymbol(text: String) = settings.setBeaconSymbol(text)

    fun sendBeaconNow() = beacon.sendNow()

    fun setNotifyMessages(enabled: Boolean) = settings.setNotifyMessages(enabled)
    fun setNotifyAck(enabled: Boolean) = settings.setNotifyAck(enabled)
    fun setNotifyConnection(enabled: Boolean) = settings.setNotifyConnection(enabled)
    fun setNotifyBulletins(enabled: Boolean) = settings.setNotifyBulletins(enabled)

    fun connectAprsIs() {
        val call = settings.myCallsign.value
        if (call.isBlank() || call == "N0CALL") return
        aprsIs.connect(
            callsign = call,
            passcode = settings.passcodeForCallsign(),
            server = settings.aprsIsServer.value,
            filter = settings.defaultFilter()
        )
    }

    fun disconnectAprsIs() = aprsIs.disconnect()

    class Factory(
        private val ble: BleUartManager,
        private val tcpTnc: TcpKissTncClient,
        private val aprsIs: AprsIsClient,
        private val iGate: IGateService,
        private val settings: SettingsRepository,
        private val beacon: BeaconService,
        private val location: PhoneLocationTracker
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConnectionViewModel(
                ble, tcpTnc, aprsIs, iGate, settings, beacon, location
            ) as T
        }
    }
}
