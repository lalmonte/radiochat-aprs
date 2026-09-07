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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnTransmissionLogTest {

    private val myBeacon = "=1928.46N/07039.83W>RadioChat APRS"

    @Test
    fun ownEcho_isRecognised() {
        val log = OwnTransmissionLog()
        log.note("HI3LAG-7", myBeacon)
        assertTrue(log.wasSentByUs("HI3LAG-7", myBeacon))
    }

    /**
     * The case that matters: the Radtel radio uses the same callsign and SSID as the app.
     * Its packets were not sent by the app, so the iGate must keep gating them.
     */
    @Test
    fun radioOnSameCallsignAndSsid_isNotTreatedAsOurs() {
        val log = OwnTransmissionLog()
        log.note("HI3LAG-7", myBeacon)
        val fromRadio = "=1928.44N/07039.84W>Radtel RT-950"
        assertFalse(log.wasSentByUs("HI3LAG-7", fromRadio))
    }

    @Test
    fun otherStation_isNotOurs() {
        val log = OwnTransmissionLog()
        log.note("HI3LAG-7", myBeacon)
        assertFalse(log.wasSentByUs("HI3RRR-7", myBeacon))
    }

    /** SSID 0 and no SSID are the same AX.25 callsign. */
    @Test
    fun ssidZero_matchesBareCallsign() {
        val log = OwnTransmissionLog()
        log.note("HI3LAG-0", myBeacon)
        assertTrue(log.wasSentByUs("HI3LAG", myBeacon))
    }

    @Test
    fun differentSsid_isAnotherStation() {
        val log = OwnTransmissionLog()
        log.note("HI3LAG-7", myBeacon)
        assertFalse(log.wasSentByUs("HI3LAG-3", myBeacon))
    }

    @Test
    fun entryExpiresAfterWindow() {
        val log = OwnTransmissionLog(windowMs = 0L)
        log.note("HI3LAG-7", myBeacon)
        assertFalse(log.wasSentByUs("HI3LAG-7", myBeacon))
    }
}
