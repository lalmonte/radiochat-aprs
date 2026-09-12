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

import android.content.Context
import android.content.SharedPreferences
import com.aprs.radiochat.data.aprs.AprsMessageCodec
import com.aprs.radiochat.data.aprsis.AprsIsClient
import com.aprs.radiochat.data.aprsis.AprsIsFilter
import com.aprs.radiochat.data.aprsis.AprsIsPasscode
import com.aprs.radiochat.data.ble.RadioModel
import com.aprs.radiochat.data.model.OwnPosition
import com.aprs.radiochat.data.tnc.TcpKissTncClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferences: callsign, passcode, APRS-IS range, iGate, TCP TNC, own position.
 */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("radiochat_aprs", Context.MODE_PRIVATE)

    private val _myCallsign = MutableStateFlow(prefs.getString(KEY_CALL, "N0CALL") ?: "N0CALL")
    val myCallsign: StateFlow<String> = _myCallsign.asStateFlow()

    private val _aprsIsServer = MutableStateFlow(
        prefs.getString(KEY_SERVER, AprsIsClient.DEFAULT_SERVER) ?: AprsIsClient.DEFAULT_SERVER
    )
    val aprsIsServer: StateFlow<String> = _aprsIsServer.asStateFlow()

    private val _iGateEnabled = MutableStateFlow(prefs.getBoolean(KEY_IGATE, false))
    val iGateEnabled: StateFlow<Boolean> = _iGateEnabled.asStateFlow()

    private val _passcode = MutableStateFlow(loadPasscode())
    val passcode: StateFlow<Int> = _passcode.asStateFlow()

    private val _rangeKm = MutableStateFlow(
        prefs.getInt(KEY_RANGE_KM, DEFAULT_RANGE_KM).coerceIn(1, 20_000)
    )
    val rangeKm: StateFlow<Int> = _rangeKm.asStateFlow()

    /** Which radio the BLE TNC talks to. */
    private val _radioModel = MutableStateFlow(
        RadioModel.fromId(prefs.getString(KEY_RADIO_MODEL, RadioModel.DEFAULT.id))
    )
    val radioModel: StateFlow<RadioModel> = _radioModel.asStateFlow()

    private val _tcpTncHost = MutableStateFlow(
        prefs.getString(KEY_TCP_HOST, TcpKissTncClient.DEFAULT_HOST)
            ?: TcpKissTncClient.DEFAULT_HOST
    )
    val tcpTncHost: StateFlow<String> = _tcpTncHost.asStateFlow()

    private val _tcpTncPort = MutableStateFlow(
        prefs.getInt(KEY_TCP_PORT, TcpKissTncClient.DEFAULT_PORT).coerceIn(1, 65_535)
    )
    val tcpTncPort: StateFlow<Int> = _tcpTncPort.asStateFlow()

    private val _beaconEnabled = MutableStateFlow(prefs.getBoolean(KEY_BEACON_ON, false))
    val beaconEnabled: StateFlow<Boolean> = _beaconEnabled.asStateFlow()

    private val _beaconIntervalSec = MutableStateFlow(
        prefs.getInt(KEY_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL_SEC).coerceIn(60, 3_600)
    )
    val beaconIntervalSec: StateFlow<Int> = _beaconIntervalSec.asStateFlow()

    private val _beaconComment = MutableStateFlow(
        prefs.getString(KEY_BEACON_COMMENT, DEFAULT_BEACON_COMMENT) ?: DEFAULT_BEACON_COMMENT
    )
    val beaconComment: StateFlow<String> = _beaconComment.asStateFlow()

    private val _beaconSymbol = MutableStateFlow(
        prefs.getString(KEY_BEACON_SYMBOL, DEFAULT_BEACON_SYMBOL) ?: DEFAULT_BEACON_SYMBOL
    )
    val beaconSymbol: StateFlow<String> = _beaconSymbol.asStateFlow()

    private val _ownPosition = MutableStateFlow(sanitizeOwnPosition(loadOwnPosition()))
    val ownPosition: StateFlow<OwnPosition?> = _ownPosition.asStateFlow()

    private val _notifyMessages = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_MSG, true))
    val notifyMessages: StateFlow<Boolean> = _notifyMessages.asStateFlow()

    private val _notifyAck = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_ACK, true))
    val notifyAck: StateFlow<Boolean> = _notifyAck.asStateFlow()

    private val _notifyConnection = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_CONN, true))
    val notifyConnection: StateFlow<Boolean> = _notifyConnection.asStateFlow()

    private val _notifyBulletins = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY_BLN, false))
    val notifyBulletins: StateFlow<Boolean> = _notifyBulletins.asStateFlow()

    fun setMyCallsign(call: String) {
        val normalized = call.uppercase().trim().ifBlank { "N0CALL" }
        val previous = _myCallsign.value
        prefs.edit().putString(KEY_CALL, normalized).apply()
        _myCallsign.value = normalized
        if (_passcode.value == AprsIsPasscode.compute(previous)) {
            setPasscode(AprsIsPasscode.compute(normalized), fromAuto = true)
        }
        _ownPosition.value?.let { pos ->
            val next = sanitizeOwnPosition(pos)
            if (next != pos) {
                _ownPosition.value = next
                if (next != null) persistOwnPosition(next) else clearPersistedOwnPosition()
            }
        }
    }

    fun setPasscode(code: Int, fromAuto: Boolean = false) {
        val value = code.coerceIn(0, 32_767)
        prefs.edit()
            .putInt(KEY_PASSCODE, value)
            .putBoolean(KEY_PASS_CUSTOM, !fromAuto)
            .apply()
        _passcode.value = value
    }

    fun useComputedPasscode() {
        setPasscode(AprsIsPasscode.compute(_myCallsign.value), fromAuto = true)
    }

    fun computedPasscode(): Int = AprsIsPasscode.compute(_myCallsign.value)

    fun setAprsIsServer(server: String) {
        val s = server.trim().ifBlank { AprsIsClient.DEFAULT_SERVER }
        prefs.edit().putString(KEY_SERVER, s).apply()
        _aprsIsServer.value = s
    }

    fun setIGateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IGATE, enabled).apply()
        _iGateEnabled.value = enabled
    }

    fun setRangeKm(km: Int) {
        val value = km.coerceIn(1, 20_000)
        prefs.edit().putInt(KEY_RANGE_KM, value).apply()
        _rangeKm.value = value
    }

    fun setRadioModel(model: RadioModel) {
        if (_radioModel.value == model) return
        prefs.edit().putString(KEY_RADIO_MODEL, model.id).apply()
        _radioModel.value = model
    }

    fun setTcpTncHost(host: String) {
        val h = host.trim().ifBlank { TcpKissTncClient.DEFAULT_HOST }
        prefs.edit().putString(KEY_TCP_HOST, h).apply()
        _tcpTncHost.value = h
    }

    fun setTcpTncPort(port: Int) {
        val p = port.coerceIn(1, 65_535)
        prefs.edit().putInt(KEY_TCP_PORT, p).apply()
        _tcpTncPort.value = p
    }

    fun setBeaconEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BEACON_ON, enabled).apply()
        _beaconEnabled.value = enabled
    }

    fun setBeaconIntervalSec(sec: Int) {
        val v = sec.coerceIn(60, 3_600)
        prefs.edit().putInt(KEY_BEACON_INTERVAL, v).apply()
        _beaconIntervalSec.value = v
    }

    fun setBeaconComment(comment: String) {
        val c = comment.take(43)
        prefs.edit().putString(KEY_BEACON_COMMENT, c).apply()
        _beaconComment.value = c
    }

    fun setBeaconSymbol(symbol: String) {
        val s = symbol.trim().take(2).ifBlank { DEFAULT_BEACON_SYMBOL }
        val normalized = if (s.length == 1) "/$s" else s
        prefs.edit().putString(KEY_BEACON_SYMBOL, normalized).apply()
        _beaconSymbol.value = normalized
    }

    fun setNotifyMessages(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_MSG, enabled).apply()
        _notifyMessages.value = enabled
    }

    fun setNotifyAck(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_ACK, enabled).apply()
        _notifyAck.value = enabled
    }

    fun setNotifyConnection(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_CONN, enabled).apply()
        _notifyConnection.value = enabled
    }

    fun setNotifyBulletins(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_BLN, enabled).apply()
        _notifyBulletins.value = enabled
    }

    fun setOwnPosition(position: OwnPosition, persist: Boolean = true) {
        val mine = _myCallsign.value
        if (position.fromRadioBeacon &&
            !AprsMessageCodec.sameCallsignWithSsid(position.callsign, mine)
        ) {
            return
        }
        val aligned = if (AprsMessageCodec.sameCallsignWithSsid(position.callsign, mine)) {
            position.copy(callsign = AprsMessageCodec.canonicalCall(mine))
        } else if (position.fromPhoneGps) {
            position.copy(callsign = AprsMessageCodec.canonicalCall(mine))
        } else {
            position
        }
        val prev = _ownPosition.value
        val samePlace = prev != null &&
            kotlin.math.abs(prev.latitude - aligned.latitude) < 0.00002 &&
            kotlin.math.abs(prev.longitude - aligned.longitude) < 0.00002 &&
            prev.callsign.equals(aligned.callsign, ignoreCase = true) &&
            prev.symbol == aligned.symbol &&
            prev.comment == aligned.comment &&
            prev.fromPhoneGps == aligned.fromPhoneGps &&
            prev.fromRadioBeacon == aligned.fromRadioBeacon
        if (samePlace) {
            if (persist) persistOwnPosition(prev!!)
            return
        }
        _ownPosition.value = aligned
        if (persist) persistOwnPosition(aligned)
    }

    fun passcodeForCallsign(): Int = _passcode.value

    /**
     * APRS-IS filter: radius, own messages, echo and regional prefix.
     * See [AprsIsFilter].
     */
    fun defaultFilter(): String {
        val pos = _ownPosition.value
        return AprsIsFilter.build(
            callsign = _myCallsign.value,
            rangeKm = _rangeKm.value,
            latitude = pos?.latitude,
            longitude = pos?.longitude
        )
    }

    /**
     * HI3LAG-3 (another station / DireWolf) is not our position when we are HI3LAG-7.
     * Keeps the coordinates only when they come from the phone GPS.
     */
    private fun sanitizeOwnPosition(pos: OwnPosition?): OwnPosition? {
        if (pos == null) return null
        val mine = _myCallsign.value
        if (AprsMessageCodec.sameCallsignWithSsid(pos.callsign, mine)) {
            return pos.copy(callsign = AprsMessageCodec.canonicalCall(mine))
        }
        if (pos.fromPhoneGps) {
            return pos.copy(
                callsign = AprsMessageCodec.canonicalCall(mine),
                fromRadioBeacon = false
            )
        }
        return null
    }

    private fun clearPersistedOwnPosition() {
        prefs.edit()
            .remove(KEY_OWN_CALL)
            .remove(KEY_OWN_LAT)
            .remove(KEY_OWN_LON)
            .remove(KEY_OWN_SYMBOL)
            .remove(KEY_OWN_COMMENT)
            .remove(KEY_OWN_AT)
            .remove(KEY_OWN_FROM_RADIO)
            .remove(KEY_OWN_FROM_GPS)
            .apply()
    }

    private fun persistOwnPosition(position: OwnPosition) {
        prefs.edit()
            .putString(KEY_OWN_CALL, position.callsign)
            .putLong(KEY_OWN_LAT, java.lang.Double.doubleToRawLongBits(position.latitude))
            .putLong(KEY_OWN_LON, java.lang.Double.doubleToRawLongBits(position.longitude))
            .putString(KEY_OWN_SYMBOL, position.symbol)
            .putString(KEY_OWN_COMMENT, position.comment)
            .putLong(KEY_OWN_AT, position.updatedAt)
            .putBoolean(KEY_OWN_FROM_RADIO, position.fromRadioBeacon)
            .putBoolean(KEY_OWN_FROM_GPS, position.fromPhoneGps)
            .apply()
    }

    private fun loadOwnPosition(): OwnPosition? {
        if (!prefs.contains(KEY_OWN_LAT) || !prefs.contains(KEY_OWN_LON)) return null
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_OWN_LAT, 0L))
        val lon = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_OWN_LON, 0L))
        if (lat == 0.0 && lon == 0.0) return null
        return OwnPosition(
            callsign = prefs.getString(KEY_OWN_CALL, _myCallsign.value) ?: _myCallsign.value,
            latitude = lat,
            longitude = lon,
            symbol = prefs.getString(KEY_OWN_SYMBOL, DEFAULT_BEACON_SYMBOL) ?: DEFAULT_BEACON_SYMBOL,
            comment = prefs.getString(KEY_OWN_COMMENT, "") ?: "",
            updatedAt = prefs.getLong(KEY_OWN_AT, 0L),
            fromRadioBeacon = prefs.getBoolean(KEY_OWN_FROM_RADIO, false),
            fromPhoneGps = prefs.getBoolean(KEY_OWN_FROM_GPS, false)
        )
    }

    private fun loadPasscode(): Int {
        if (prefs.contains(KEY_PASSCODE)) {
            return prefs.getInt(KEY_PASSCODE, 0).coerceIn(0, 32_767)
        }
        return AprsIsPasscode.compute(_myCallsign.value)
    }

    companion object {
        private const val KEY_CALL = "my_callsign"
        private const val KEY_SERVER = "aprs_is_server"
        private const val KEY_IGATE = "igate_enabled"
        private const val KEY_PASSCODE = "aprs_is_passcode"
        private const val KEY_PASS_CUSTOM = "aprs_is_passcode_custom"
        private const val KEY_RANGE_KM = "aprs_is_range_km"
        private const val KEY_RADIO_MODEL = "ble_radio_model"
        private const val KEY_TCP_HOST = "tcp_tnc_host"
        private const val KEY_TCP_PORT = "tcp_tnc_port"
        private const val KEY_BEACON_ON = "beacon_enabled"
        private const val KEY_BEACON_INTERVAL = "beacon_interval_sec"
        private const val KEY_BEACON_COMMENT = "beacon_comment"
        private const val KEY_BEACON_SYMBOL = "beacon_symbol"
        private const val KEY_OWN_CALL = "own_pos_call"
        private const val KEY_OWN_LAT = "own_pos_lat"
        private const val KEY_OWN_LON = "own_pos_lon"
        private const val KEY_OWN_SYMBOL = "own_pos_symbol"
        private const val KEY_OWN_COMMENT = "own_pos_comment"
        private const val KEY_OWN_AT = "own_pos_at"
        private const val KEY_OWN_FROM_RADIO = "own_pos_from_radio"
        private const val KEY_OWN_FROM_GPS = "own_pos_from_gps"
        private const val KEY_NOTIFY_MSG = "notify_messages"
        private const val KEY_NOTIFY_ACK = "notify_ack"
        private const val KEY_NOTIFY_CONN = "notify_connection"
        private const val KEY_NOTIFY_BLN = "notify_bulletins"
        const val DEFAULT_RANGE_KM = 100
        const val DEFAULT_BEACON_INTERVAL_SEC = 300
        const val DEFAULT_BEACON_COMMENT = "RadioChat APRS"
        const val DEFAULT_BEACON_SYMBOL = "/$"
    }
}
