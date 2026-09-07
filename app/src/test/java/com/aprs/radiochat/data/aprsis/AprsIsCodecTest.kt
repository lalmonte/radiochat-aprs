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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AprsIsCodecTest {

    @Test
    fun passcode_known_call() {
        // Reference value from the standard APRS-IS algorithm
        assertEquals(13023, AprsIsPasscode.compute("N0CALL"))
    }

    @Test
    fun tnc2_roundtrip_parse() {
        val line = "HI3LAG-7>APRS,WIDE1-1,WIDE2-1::HI3LAG-3 :hola{01"
        val f = Tnc2Codec.parse(line)!!
        assertEquals("HI3LAG-7", f.source)
        assertEquals("APRS", f.destination)
        assertEquals(listOf("WIDE1-1", "WIDE2-1"), f.digipeaters)
        assertTrue(f.info.startsWith(":"))
    }

    @Test
    fun unwrap_ota_third_party_message() {
        val info = "}OTA>APRS,TCPIP*::HI3LAG-7 :Hello from OTA{01"
        val inner = Tnc2Codec.unwrapThirdParty(info)!!
        assertEquals("OTA", inner.source)
        assertEquals("APRS", inner.destination)
        assertEquals(":HI3LAG-7 :Hello from OTA{01", inner.info)
    }

    @Test
    fun should_not_gate_tcpip() {
        val frame = Tnc2Codec.Frame(
            source = "HI3LAG-7",
            destination = "APRS",
            digipeaters = listOf("TCPIP*"),
            info = ":TEST     :hi"
        )
        assertFalse(Tnc2Codec.shouldGateToIs(frame))
    }
}
