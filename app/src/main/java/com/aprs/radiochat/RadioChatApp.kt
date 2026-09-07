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
package com.aprs.radiochat

import android.app.Application
import com.aprs.radiochat.data.aprs.AprsPacketParser
import com.aprs.radiochat.data.aprsis.AprsIsClient
import com.aprs.radiochat.data.aprsis.IGateService
import com.aprs.radiochat.data.aprsis.OwnTransmissionLog
import com.aprs.radiochat.data.beacon.BeaconService
import com.aprs.radiochat.data.ble.BleUartManager
import com.aprs.radiochat.data.kiss.KissFrameHub
import com.aprs.radiochat.data.location.BeaconTrackStore
import com.aprs.radiochat.data.location.PhoneLocationTracker
import com.aprs.radiochat.data.notification.AprsNotificationManager
import com.aprs.radiochat.data.notification.ConnectionNotificationObserver
import java.io.File
import com.aprs.radiochat.data.repository.AprsLogRepository
import com.aprs.radiochat.data.repository.ChatRepository
import com.aprs.radiochat.data.repository.ChatStore
import com.aprs.radiochat.data.repository.SettingsRepository
import com.aprs.radiochat.data.repository.StationRepository
import com.aprs.radiochat.data.tnc.TcpKissTncClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.config.Configuration

/**
 * Simple dependency container (no Hilt).
 */
class RadioChatApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var bleUartManager: BleUartManager
        private set
    lateinit var tcpKissTncClient: TcpKissTncClient
        private set
    lateinit var kissFrameHub: KissFrameHub
        private set
    lateinit var aprsIsClient: AprsIsClient
        private set
    lateinit var iGateService: IGateService
        private set
    lateinit var ownTransmissionLog: OwnTransmissionLog
        private set
    lateinit var phoneLocationTracker: PhoneLocationTracker
        private set
    lateinit var beaconTrackStore: BeaconTrackStore
        private set
    lateinit var beaconService: BeaconService
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var stationRepository: StationRepository
        private set
    lateinit var aprsLogRepository: AprsLogRepository
        private set
    lateinit var aprsNotificationManager: AprsNotificationManager
        private set

    private val _pendingChatPeer = MutableStateFlow<String?>(null)
    val pendingChatPeer: StateFlow<String?> = _pendingChatPeer.asStateFlow()

    fun requestOpenChat(peer: String) {
        _pendingChatPeer.value = peer.uppercase().trim()
    }

    fun consumePendingChatPeer(): String? {
        val peer = _pendingChatPeer.value
        _pendingChatPeer.value = null
        return peer
    }

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().load(
            applicationContext,
            applicationContext.getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        settingsRepository = SettingsRepository(this)
        aprsNotificationManager = AprsNotificationManager(
            this,
            settingsRepository
        )
        bleUartManager = BleUartManager(this)
        tcpKissTncClient = TcpKissTncClient()
        kissFrameHub = KissFrameHub(bleUartManager, tcpKissTncClient)
        aprsIsClient = AprsIsClient()
        phoneLocationTracker = PhoneLocationTracker(this)
        beaconTrackStore = BeaconTrackStore(File(filesDir, "beacon_route.csv"))

        val parser = AprsPacketParser(myCallsign = { settingsRepository.myCallsign.value })

        ownTransmissionLog = OwnTransmissionLog()

        iGateService = IGateService(
            kissHub = kissFrameHub,
            aprsIs = aprsIsClient,
            myCallsign = { settingsRepository.myCallsign.value },
            ownTx = ownTransmissionLog,
            isEnabled = { settingsRepository.iGateEnabled.value }
        )

        beaconService = BeaconService(
            appContext = this,
            location = phoneLocationTracker,
            beaconTrack = beaconTrackStore,
            settings = settingsRepository,
            aprsIs = aprsIsClient,
            ownTx = ownTransmissionLog,
            tcpTnc = tcpKissTncClient,
            myCallsign = { settingsRepository.myCallsign.value }
        )

        chatRepository = ChatRepository(
            kissHub = kissFrameHub,
            tcpTnc = tcpKissTncClient,
            aprsIs = aprsIsClient,
            ownTx = ownTransmissionLog,
            parser = parser,
            store = ChatStore(this),
            notifications = aprsNotificationManager,
            myCallsign = { settingsRepository.myCallsign.value }
        )
        stationRepository = StationRepository(
            kissHub = kissFrameHub,
            aprsIs = aprsIsClient,
            parser = parser,
            settings = settingsRepository,
            beaconTrack = beaconTrackStore,
            myCallsign = { settingsRepository.myCallsign.value }
        )
        aprsLogRepository = AprsLogRepository(
            kissHub = kissFrameHub,
            aprsIs = aprsIsClient,
            parser = parser,
            settings = settingsRepository
        )

        ConnectionNotificationObserver(
            ble = bleUartManager,
            tcp = tcpKissTncClient,
            aprsIs = aprsIsClient,
            settings = settingsRepository,
            notifications = aprsNotificationManager
        )
    }
}
