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

import java.util.Locale

/**
 * javAPRSSrvr filter (elements are OR-ed).
 *
 * Do not use `t/m` on its own: the server sends **every** message in the world.
 */
object AprsIsFilter {

    fun build(callsign: String, rangeKm: Int, latitude: Double?, longitude: Double?): String {
        val full = callsign.uppercase().trim()
        val base = full.substringBefore('-')
        val km = rangeKm.coerceIn(1, 20_000)
        val parts = mutableListOf<String>()

        if (latitude != null && longitude != null) {
            val lat = String.format(Locale.US, "%.2f", latitude)
            val lon = String.format(Locale.US, "%.2f", longitude)
            parts += "r/$lat/$lon/$km"
        }
        // Around our last TX position (nearby messages and beacons).
        parts += "m/$km"

        val gCalls = linkedSetOf(base)
        if (full.isNotBlank() && full != base) gCalls += full
        parts += "g/${gCalls.joinToString("/")}"
        parts += "b/$base"

        regionalPrefix(base)?.let { parts += "p/$it" }
        return parts.joinToString(" ")
    }

    /**
     * Zone prefix (HI3LAG → HI3) to hear regional traffic
     * even when the server has no position for that station.
     */
    internal fun regionalPrefix(baseCall: String): String? {
        val base = baseCall.uppercase().filter { it.isLetterOrDigit() }
        return when {
            base.length >= 4 -> base.take(3)
            base.length >= 2 -> base.take(2)
            else -> null
        }
    }
}
