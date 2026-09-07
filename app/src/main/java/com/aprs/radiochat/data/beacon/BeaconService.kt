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
package com.aprs.radiochat.data.beacon

import android.util.Log
import android.content.Context
import com.aprs.radiochat.data.aprs.AprsPositionParser
import com.aprs.radiochat.data.aprs.Ax25Frame
import com.aprs.radiochat.data.aprs.GeoUtils
import com.aprs.radiochat.data.aprsis.AprsIsClient
import com.aprs.radiochat.data.aprsis.OwnTransmissionLog
import com.aprs.radiochat.data.aprsis.Tnc2Codec
import com.aprs.radiochat.data.location.BeaconTrackStore
import com.aprs.radiochat.data.location.PhoneLocationFix
import com.aprs.radiochat.data.location.PhoneLocationTracker
import com.aprs.radiochat.data.model.AprsIsConnectionState
import com.aprs.radiochat.data.model.OwnPosition
import com.aprs.radiochat.data.model.TcpTncConnectionState
import com.aprs.radiochat.data.repository.SettingsRepository
import com.aprs.radiochat.data.service.AprsTrackingService
import com.aprs.radiochat.data.tnc.TcpKissTncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Periodic APRS beacon from the phone GPS → KISS TCP and/or APRS-IS.
 *
 * The loop works from an absolute due instant rather than a relative `delay`:
 * if Doze suspends the process, on waking it transmits as soon as it can
 * instead of losing its turn, and changing the interval keeps the current
 * schedule rather than injecting an extra transmission on a shared channel.
 */
class BeaconService(
    private val appContext: Context,
    private val location: PhoneLocationTracker,
    private val beaconTrack: BeaconTrackStore,
    private val settings: SettingsRepository,
    private val aprsIs: AprsIsClient,
    private val ownTx: OwnTransmissionLog,
    private val tcpTnc: TcpKissTncClient,
    private val myCallsign: () -> String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * `restartLoop`/`stopLoop` are called from different collectors: without a lock
     * a reference was lost and an orphan loop kept transmitting in parallel.
     */
    private val loopLock = Any()
    private val loopGen = AtomicInteger(0)

    /** Debounce for the "Send now" button: one tap, one beacon. */
    private val sendNowGuard = AtomicBoolean(false)

    @Volatile
    private var loopJob: Job? = null

    private val _lastStatus = MutableStateFlow<String?>(null)
    val lastStatus: StateFlow<String?> = _lastStatus.asStateFlow()

    private val _lastTxAt = MutableStateFlow(0L)
    val lastTxAt: StateFlow<Long> = _lastTxAt.asStateFlow()

    /** Manual send in progress: the UI disables the button while it lasts. */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    @Volatile private var lastPublishAt = 0L
    @Volatile private var lastPersistAt = 0L
    @Volatile private var lastFilterLat = Double.NaN
    @Volatile private var lastFilterLon = Double.NaN

    init {
        scope.launch {
            settings.beaconEnabled.collectLatest { enabled ->
                if (enabled) {
                    location.start()
                    // The service wakelock is what keeps the timer alive with the screen
                    // off. If the process starts in the background, Android 12+ rejects
                    // the start; `start` logs it and carries on, and MainActivity.onStart
                    // retries once the UI appears.
                    AprsTrackingService.start(appContext)
                    restartLoop(kick = true)
                } else {
                    stopLoop()
                    AprsTrackingService.stop(appContext)
                    location.stop()
                    _lastStatus.value = "Beacon off"
                }
            }
        }
        // Keep ownPosition + the IS filter up to date with GPS
        scope.launch {
            combine(settings.beaconEnabled, location.fix) { enabled, fix ->
                enabled to fix
            }.collect { (enabled, fix) ->
                if (!enabled || fix == null) return@collect
                publishOwnFromGps(fix.latitude, fix.longitude)
            }
        }
        scope.launch {
            // drop(1): a StateFlow re-emits its current value on subscribe, and that
            // start-up would race the beaconEnabled collector, rescheduling the first
            // beacon a whole interval away. Only real changes matter here.
            settings.beaconIntervalSec.drop(1).collectLatest {
                // kick=false: reschedule from the last TX, without transmitting on edit.
                if (settings.beaconEnabled.value) restartLoop(kick = false)
            }
        }
    }

    /** Immediate send ("Send now" button). */
    fun sendNow() {
        scope.launch {
            if (!sendNowGuard.compareAndSet(false, true)) return@launch
            _sending.value = true
            try {
                location.start()
                val fix = awaitUsableFix()
                if (fix == null) {
                    _lastStatus.value = "No GPS fix"
                    return@launch
                }
                transmit(fix.latitude, fix.longitude)
            } finally {
                _sending.value = false
                sendNowGuard.set(false)
                // With the beacon off, GPS was only requested for this one send.
                if (!settings.beaconEnabled.value) location.stop()
            }
        }
    }

    /**
     * `location.start()` is synchronous but the first fix is not: without waiting,
     * the first tap on a cold GPS always failed and you had to tap twice.
     */
    private suspend fun awaitUsableFix(): PhoneLocationFix? {
        location.fix.value?.takeIf { !isStale(it) }?.let { return it }
        _lastStatus.value = "Waiting for GPS…"
        return withTimeoutOrNull(GPS_WAIT_MS) {
            location.fix.filterNotNull().first { !isStale(it) }
        }
    }

    private fun isStale(fix: PhoneLocationFix): Boolean =
        System.currentTimeMillis() - fix.updatedAt > maxFixAgeMs()

    /**
     * A stale fix means GPS was lost (indoors, a tunnel). Keeping on beaconing it
     * announces a position that is no longer yours, so past this margin the beacon
     * stays quiet instead of lying.
     */
    private fun maxFixAgeMs(): Long = maxOf(3 * intervalMs(), MIN_FIX_MAX_AGE_MS)

    private fun intervalMs(): Long =
        settings.beaconIntervalSec.value.coerceIn(60, 3_600) * 1_000L

    private fun restartLoop(kick: Boolean) {
        synchronized(loopLock) {
            val gen = loopGen.incrementAndGet()
            loopJob?.cancel()
            loopJob = scope.launch { runLoop(gen, kick) }
        }
    }

    private fun stopLoop() {
        synchronized(loopLock) {
            loopGen.incrementAndGet()
            loopJob?.cancel()
            loopJob = null
        }
    }

    private suspend fun runLoop(gen: Int, kick: Boolean) {
        val now = System.currentTimeMillis()
        val lastTx = _lastTxAt.value
        var nextDueAt = when {
            // Beacon just turned on: first attempt soon.
            kick -> now + FIRST_TX_DELAY_MS
            // Interval changed: keep the schedule of the last send.
            lastTx > 0L -> lastTx + intervalMs()
            else -> now + intervalMs()
        }

        while (loopGen.get() == gen && settings.beaconEnabled.value) {
            val wait = nextDueAt - System.currentTimeMillis()
            if (wait > 0) {
                delay(wait)
                if (loopGen.get() != gen || !settings.beaconEnabled.value) return
            }
            val ok = transmit()
            val after = System.currentTimeMillis()
            // With no GPS or no link we retry sooner, but never in a tight loop.
            nextDueAt = if (ok) after + intervalMs() else after + RETRY_DELAY_MS
        }
    }

    private suspend fun transmit(): Boolean {
        val fix = location.fix.value
        if (fix == null) {
            _lastStatus.value = "Waiting for GPS…"
            return false
        }
        if (isStale(fix)) {
            val ageSec = (System.currentTimeMillis() - fix.updatedAt) / 1_000L
            _lastStatus.value = "GPS stale (${ageSec}s); beacon on hold"
            return false
        }
        return transmit(fix.latitude, fix.longitude)
    }

    private suspend fun transmit(lat: Double, lon: Double): Boolean {
        val call = myCallsign().ifBlank { "N0CALL" }
        if (call == "N0CALL") {
            _lastStatus.value = "Set your callsign"
            return false
        }
        val symbol = settings.beaconSymbol.value.ifBlank { "/$" }
        val comment = settings.beaconComment.value
        val info = AprsPositionParser.formatUncompressed(
            latitude = lat,
            longitude = lon,
            symbol = symbol,
            comment = comment,
            messagingCapable = true
        )

        publishOwnFromGps(lat, lon, symbol, comment)

        var sentIs = false
        var sentTcp = false

        val isState = aprsIs.connectionState.value
        if (isState is AprsIsConnectionState.Connected && isState.verified) {
            val line = Tnc2Codec.format(
                source = call,
                destination = "APRS",
                digipeaters = listOf("TCPIP*"),
                info = info
            )
            sentIs = aprsIs.sendLine(line)
            // Published to IS by us: its RF echo must not come back through the iGate.
            if (sentIs) ownTx.note(call, info)
        }

        val tcpState = tcpTnc.connectionState.value
        if (tcpState is TcpTncConnectionState.Connected) {
            val ax25 = Ax25Frame.buildUiFrame(
                destination = "APRS",
                source = call,
                info = info.toByteArray(Charsets.ISO_8859_1),
                digipeaters = listOf("WIDE1-1", "WIDE2-1")
            )
            sentTcp = tcpTnc.sendAx25(ax25)
        }

        if (!sentIs && !sentTcp) {
            val reconnecting = tcpState is TcpTncConnectionState.Connecting ||
                isState is AprsIsConnectionState.Connecting
            _lastStatus.value = if (reconnecting) {
                "Reconnecting link; beacon not sent"
            } else {
                "No destination: connect APRS-IS or KISS TCP"
            }
            return false
        }

        _lastTxAt.value = System.currentTimeMillis()
        // Rewrites the whole CSV: off whatever thread called us (the UI included).
        withContext(Dispatchers.IO) {
            beaconTrack.appendFromAprsInfo(info, lat, lon)
        }
        val via = buildList {
            if (sentIs) add("IS")
            if (sentTcp) add("TCP")
        }.joinToString("+")
        _lastStatus.value = "TX $via · ${"%.4f".format(lat)}, ${"%.4f".format(lon)}"
        Log.i(TAG, "Beacon $via: $call>$info")
        return true
    }

    private fun publishOwnFromGps(
        lat: Double,
        lon: Double,
        symbol: String = settings.beaconSymbol.value.ifBlank { "/$" },
        comment: String = settings.beaconComment.value
    ) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val prev = settings.ownPosition.value
            val movedM = if (prev == null) {
                Double.MAX_VALUE
            } else {
                GeoUtils.distanceKm(prev.latitude, prev.longitude, lat, lon) * 1000.0
            }
            if (movedM < UI_MOVE_M && now - lastPublishAt < UI_MIN_INTERVAL_MS) return
            lastPublishAt = now

            val persist = now - lastPersistAt >= PERSIST_INTERVAL_MS || movedM >= PERSIST_MOVE_M
            if (persist) lastPersistAt = now

            val call = myCallsign().ifBlank { "N0CALL" }
            settings.setOwnPosition(
                OwnPosition(
                    callsign = call,
                    latitude = lat,
                    longitude = lon,
                    symbol = symbol,
                    comment = comment,
                    updatedAt = now,
                    fromRadioBeacon = false,
                    fromPhoneGps = true
                ),
                persist = persist
            )

            val filterMoved = lastFilterLat.isNaN() ||
                GeoUtils.distanceKm(lastFilterLat, lastFilterLon, lat, lon) * 1000.0 >= FILTER_MOVE_M
            if (filterMoved && aprsIs.connectionState.value is AprsIsConnectionState.Connected) {
                lastFilterLat = lat
                lastFilterLon = lon
                aprsIs.updateFilter(settings.defaultFilter())
            }
        }
    }

    companion object {
        private const val TAG = "BeaconService"
        private const val UI_MOVE_M = 25.0
        private const val UI_MIN_INTERVAL_MS = 8_000L
        private const val PERSIST_INTERVAL_MS = 60_000L
        private const val PERSIST_MOVE_M = 150.0
        private const val FILTER_MOVE_M = 1_200.0

        /** First beacon after turning on, to give the first fix some room. */
        private const val FIRST_TX_DELAY_MS = 3_000L

        /** Retry when there was no GPS or no link. */
        private const val RETRY_DELAY_MS = 30_000L

        /** How long a manual send waits for a usable fix. */
        private const val GPS_WAIT_MS = 25_000L

        /** Floor for the fix staleness margin (short intervals). */
        private const val MIN_FIX_MAX_AGE_MS = 10 * 60_000L
    }
}
