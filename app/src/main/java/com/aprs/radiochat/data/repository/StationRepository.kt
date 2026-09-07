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
package com.aprs.radiochat.data.repository

import android.util.Log
import com.aprs.radiochat.data.aprs.AprsMessageCodec
import com.aprs.radiochat.data.aprs.AprsPacketParser
import com.aprs.radiochat.data.aprs.AprsPositionParser
import com.aprs.radiochat.data.aprs.GeoUtils
import com.aprs.radiochat.data.aprs.MiceParser
import com.aprs.radiochat.data.aprsis.AprsIsClient
import com.aprs.radiochat.data.aprsis.Tnc2Codec
import com.aprs.radiochat.data.kiss.KissFrameHub
import com.aprs.radiochat.data.location.BeaconTrackStore
import com.aprs.radiochat.data.model.AprsIsConnectionState
import com.aprs.radiochat.data.model.AprsPacket
import com.aprs.radiochat.data.model.MapStation
import com.aprs.radiochat.data.model.OwnPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stations/beacons from RF (BLE/TCP KISS) and APRS-IS.
 * Own position is taken from the beacon of the same callsign over KISS RF.
 */
class StationRepository(
    private val kissHub: KissFrameHub,
    private val aprsIs: AprsIsClient,
    private val parser: AprsPacketParser,
    private val settings: SettingsRepository,
    private val beaconTrack: BeaconTrackStore,
    private val myCallsign: () -> String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val byCall = LinkedHashMap<String, MapStation>()
    @Volatile private var dirty = false

    private val _stations = MutableStateFlow<Map<String, MapStation>>(emptyMap())
    val stations: StateFlow<Map<String, MapStation>> = _stations.asStateFlow()

    val ownPosition: StateFlow<OwnPosition?> = settings.ownPosition

    init {
        scope.launch {
            kissHub.frames.collect { frame ->
                val packet = parser.parseKissPayload(frame.payload) ?: return@collect
                if (packet is AprsPacket.Position) {
                    upsert(packet)
                    maybeCaptureOwnFromRadio(packet)
                }
            }
        }
        scope.launch {
            aprsIs.incomingLines.collect { line ->
                val frame = Tnc2Codec.parse(line) ?: return@collect
                val uncompressed = AprsPositionParser.parse(frame.info)
                val mice = if (uncompressed == null) {
                    MiceParser.parse(frame.destination, frame.info)
                } else {
                    null
                }
                val lat: Double
                val lon: Double
                val sym: String
                val cmt: String
                when {
                    uncompressed != null -> {
                        lat = uncompressed.latitude
                        lon = uncompressed.longitude
                        sym = uncompressed.symbol
                        cmt = uncompressed.comment
                    }
                    mice != null -> {
                        lat = mice.latitude
                        lon = mice.longitude
                        sym = mice.symbol
                        cmt = mice.comment
                    }
                    else -> return@collect
                }
                // Local reinforcement: drop IS traffic outside the configured radius
                val own = settings.ownPosition.value
                if (own != null &&
                    !GeoUtils.withinKm(
                        own.latitude,
                        own.longitude,
                        lat,
                        lon,
                        settings.rangeKm.value
                    )
                ) {
                    return@collect
                }
                upsert(
                    AprsPacket.Position(
                        sourceCall = frame.source,
                        destCall = frame.destination,
                        rawInfo = frame.info,
                        latitude = lat,
                        longitude = lon,
                        symbol = sym,
                        comment = cmt
                    )
                )
            }
        }
        scope.launch {
            while (isActive) {
                delay(SNAPSHOT_MS)
                publishIfDirty()
            }
        }
    }

    /**
     * When the RF beacon carries our own callsign (radio connected),
     * update the app station's position.
     */
    private fun maybeCaptureOwnFromRadio(packet: AprsPacket.Position) {
        val mine = myCallsign()
        if (!AprsMessageCodec.sameCallsignWithSsid(packet.sourceCall, mine)) return

        val own = OwnPosition(
            callsign = AprsMessageCodec.canonicalCall(mine),
            latitude = packet.latitude,
            longitude = packet.longitude,
            symbol = packet.symbol.ifBlank { "/$" },
            comment = packet.comment,
            updatedAt = System.currentTimeMillis(),
            fromRadioBeacon = true,
            fromPhoneGps = false
        )
        val prev = settings.ownPosition.value
        val moved = prev == null ||
            kotlin.math.abs(prev.latitude - own.latitude) > 0.00005 ||
            kotlin.math.abs(prev.longitude - own.longitude) > 0.00005

        settings.setOwnPosition(own)
        beaconTrack.append(own.latitude, own.longitude, own.updatedAt)
        Log.i(
            TAG,
            "Own position from radio beacon: ${own.callsign} " +
                "%.5f %.5f sym=${own.symbol}".format(own.latitude, own.longitude)
        )

        // Update the IS filter (r/lat/lon/km) when the position changes
        if (moved && aprsIs.connectionState.value is AprsIsConnectionState.Connected) {
            aprsIs.updateFilter(settings.defaultFilter())
        }
    }

    private fun upsert(packet: AprsPacket.Position) {
        val call = AprsMessageCodec.canonicalCall(packet.sourceCall)
        if (call.isBlank()) return
        val station = MapStation(
            callsign = call,
            latitude = packet.latitude,
            longitude = packet.longitude,
            symbol = packet.symbol,
            comment = packet.comment,
            lastUpdate = System.currentTimeMillis()
        )
        synchronized(lock) {
            byCall[call] = station
            dirty = true
        }
    }

    private fun publishIfDirty() {
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            if (byCall.size > MAX_STATIONS) {
                val keep = byCall.values.sortedByDescending { it.lastUpdate }.take(MAX_STATIONS)
                byCall.clear()
                keep.forEach { byCall[it.callsign] = it }
            }
            LinkedHashMap(byCall)
        }
        _stations.value = snapshot
    }

    fun clear() {
        synchronized(lock) {
            byCall.clear()
            dirty = false
        }
        _stations.value = emptyMap()
    }

    companion object {
        private const val TAG = "StationRepository"
        private const val MAX_STATIONS = 120
        private const val SNAPSHOT_MS = 1_500L
    }
}
