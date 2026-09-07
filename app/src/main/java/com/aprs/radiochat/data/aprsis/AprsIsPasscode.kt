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

/**
 * Standard APRS-IS passcode (hash of the callsign without SSID).
 * Documented on aprs-is.net / aprs.fi — it is not a secret, only proof of callsign.
 */
object AprsIsPasscode {

    fun compute(callsign: String): Int {
        val call = callsign.uppercase().substringBefore('-').trim()
        var hash = 0x73e2
        var i = 0
        while (i < call.length) {
            hash = hash xor (call[i].code shl 8)
            if (i + 1 < call.length) {
                hash = hash xor call[i + 1].code
            }
            i += 2
        }
        return hash and 0x7fff
    }
}
