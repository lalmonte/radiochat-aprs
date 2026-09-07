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
package com.aprs.radiochat.data.kiss

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KissCodecTest {

    @Test
    fun encodeDecode_roundTrip_withEscapes() {
        val payload = byteArrayOf(
            0x01, KissCodec.FEND, 0x02, KissCodec.FESC, 0x03
        )
        val frame = KissCodec.encode(payload)
        assertEquals(KissCodec.FEND, frame.first())
        assertEquals(KissCodec.FEND, frame.last())

        val decoder = KissCodec.Decoder()
        val out = decoder.feed(frame)
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0])
    }

    @Test
    fun decoder_handlesFragmentedBleChunks() {
        val payload = "HELLO".toByteArray()
        val frame = KissCodec.encode(payload)
        val mid = frame.size / 2
        val decoder = KissCodec.Decoder()
        assertTrue(decoder.feed(frame.copyOfRange(0, mid)).isEmpty())
        val result = decoder.feed(frame.copyOfRange(mid, frame.size))
        assertEquals(1, result.size)
        assertArrayEquals(payload, result[0])
    }

    @Test
    fun decoder_skipsNonDataKissCommands() {
        val txdelay = KissCodec.encode(byteArrayOf(30), KissCodec.CMD_TXDELAY)
        val data = KissCodec.encode("HELLO".toByteArray())
        val decoder = KissCodec.Decoder()
        val out = decoder.feed(txdelay + data)
        val payloads = out.filter { it.isNotEmpty() }
        assertEquals(1, payloads.size)
        assertArrayEquals("HELLO".toByteArray(), payloads[0])
    }

    @Test
    fun decoder_acceptsDataOnKissPort1() {
        val payload = "RF".toByteArray()
        val frame = KissCodec.encode(payload, command = 0x10)
        val out = KissCodec.Decoder().feed(frame).filter { it.isNotEmpty() }
        assertEquals(1, out.size)
        assertArrayEquals(payload, out[0])
    }

    @Test
    fun decoder_handlesDireWolfStyleConcatenatedFrames() {
        val a = KissCodec.encode("AAAA".toByteArray())
        val b = KissCodec.encode("BBBB".toByteArray())
        val decoder = KissCodec.Decoder()
        val out = decoder.feed(byteArrayOf(KissCodec.FEND) + a + b)
            .filter { it.isNotEmpty() }
        assertEquals(2, out.size)
        assertArrayEquals("AAAA".toByteArray(), out[0])
        assertArrayEquals("BBBB".toByteArray(), out[1])
    }
}
