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

import com.aprs.radiochat.data.aprs.AprsMessageCodec
import java.util.concurrent.ConcurrentHashMap

/**
 * Short log of the packets this app put on APRS-IS itself.
 *
 * The iGate cannot recognise its own packets by callsign: if the radio
 * (Radtel over BLE) is set to the same callsign and SSID as the app,
 * filtering by callsign would drop its traffic too, and DireWolf also hears
 * over the air what the radio transmits and delivers it with that same source.
 * What does distinguish them is having originated that specific packet, and
 * that is what gets noted here.
 */
class OwnTransmissionLog(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val sent = ConcurrentHashMap<String, Long>()

    /** Notes an own packet already sent to APRS-IS. */
    fun note(source: String, info: String) {
        val now = System.currentTimeMillis()
        prune(now)
        sent[key(source, info)] = now
    }

    /** true when this app sent this packet recently (its RF echo must not be gated). */
    fun wasSentByUs(source: String, info: String): Boolean {
        val at = sent[key(source, info)] ?: return false
        return System.currentTimeMillis() - at < windowMs
    }

    private fun key(source: String, info: String): String =
        "${AprsMessageCodec.canonicalCall(source)}|${info.trim()}"

    private fun prune(now: Long) {
        if (sent.size < MAX_ENTRIES) return
        val it = sent.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > windowMs) it.remove()
        }
    }

    companion object {
        /** The digipeated echo comes back in seconds; 5 min is plenty and bounds memory. */
        private const val DEFAULT_WINDOW_MS = 5 * 60_000L
        private const val MAX_ENTRIES = 200
    }
}
