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
package com.aprs.radiochat.data.aprsis

import android.util.Log
import com.aprs.radiochat.data.kiss.KissFrameHub
import com.aprs.radiochat.data.model.AprsIsConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * iGate RF→IS: KISS frames (BLE or TCP/DireWolf) are forwarded to APRS-IS.
 *
 * Requirements: a verified APRS-IS session + iGate enabled.
 * Anti-loop + dedupe + rate-limit so the server does not drop us.
 */
class IGateService(
    private val kissHub: KissFrameHub,
    private val aprsIs: AprsIsClient,
    private val myCallsign: () -> String,
    private val ownTx: OwnTransmissionLog,
    private val isEnabled: () -> Boolean
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recent = ConcurrentHashMap<String, Long>()
    @Volatile private var lastGateMs = 0L

    private val _gatedCount = MutableStateFlow(0)
    val gatedCount: StateFlow<Int> = _gatedCount.asStateFlow()

    private val _lastGated = MutableStateFlow<String?>(null)
    val lastGated: StateFlow<String?> = _lastGated.asStateFlow()

    init {
        scope.launch {
            kissHub.frames.collect { frame ->
                if (!isEnabled()) return@collect
                val state = aprsIs.connectionState.value
                if (state !is AprsIsConnectionState.Connected || !state.verified) return@collect

                val payload = frame.payload
                val tncFrame = Tnc2Codec.fromAx25(payload) ?: return@collect
                if (!Tnc2Codec.shouldGateToIs(tncFrame)) return@collect

                // RF echo of a packet the app already published to APRS-IS: gating it
                // would duplicate it. It is recognised by the packet, not the callsign:
                // the radio may use your own callsign and SSID, and its traffic is gated.
                if (ownTx.wasSentByUs(tncFrame.source, tncFrame.info)) {
                    return@collect
                }
                val mine = myCallsign()
                val myBase = mine.substringBefore('-').uppercase()
                if (tncFrame.source.substringBefore('-').equals(myBase, ignoreCase = true) &&
                    tncFrame.digipeaters.any { it.contains("TCPIP", ignoreCase = true) }
                ) {
                    return@collect
                }

                val line = Tnc2Codec.format(
                    source = tncFrame.source,
                    destination = tncFrame.destination,
                    digipeaters = tncFrame.digipeaters,
                    info = tncFrame.info
                )
                val key = "${tncFrame.source}|${tncFrame.info}"
                val now = System.currentTimeMillis()
                prune(now)
                val prev = recent.putIfAbsent(key, now)
                if (prev != null && now - prev < DEDUPE_MS) return@collect

                if (now - lastGateMs < MIN_GATE_INTERVAL_MS) return@collect
                lastGateMs = now

                if (aprsIs.sendLine(line)) {
                    _gatedCount.value = _gatedCount.value + 1
                    _lastGated.value = "${tncFrame.source}>${tncFrame.destination}"
                    Log.i(TAG, "iGate RF→IS (${frame.transport}): ${tncFrame.source}>${tncFrame.destination}")
                }
            }
        }
    }

    private fun prune(now: Long) {
        if (recent.size < 200) return
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > DEDUPE_MS) it.remove()
        }
    }

    companion object {
        private const val TAG = "IGateService"
        private const val DEDUPE_MS = 5 * 60_000L
        private const val MIN_GATE_INTERVAL_MS = 1_200L
    }
}
