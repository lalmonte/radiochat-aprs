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
package com.aprs.radiochat.data.ble

import com.aprs.radiochat.data.kiss.KissCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The Radtel path shipped in 1.0.0 and works on real hardware. These tests pin it down
 * so adding another radio cannot quietly change it.
 */
class RadtelProfileTest {

    @Test
    fun `gatt attributes are unchanged`() {
        assertEquals(
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),
            RadtelKissProfile.serviceUuid
        )
        assertEquals(
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
            RadtelKissProfile.notifyCharUuid
        )
        // FF31 must stay the preferred TX channel: FFE1 does not key RF TX in KISS mode.
        assertEquals("FF31", RadtelKissProfile.writeCharUuids.first().label)
        assertEquals(
            UUID.fromString("0000FF31-0000-1000-8000-00805F9B34FB"),
            RadtelKissProfile.writeCharUuids.first().uuid
        )
        assertEquals(
            listOf("FF31", "FFE2", "FFE1"),
            RadtelKissProfile.writeCharUuids.map { it.label }
        )
    }

    @Test
    fun `uses notifications and mtu chunking`() {
        assertFalse(RadtelKissProfile.usesIndications)
        assertTrue(RadtelKissProfile.chunkWritesToMtu)
    }

    @Test
    fun `is receive only over BLE`() {
        // The RT-950 accepts the write and never keys up; claiming TX would make the app
        // report messages as sent that never went on the air.
        assertFalse(RadtelKissProfile.supportsTx)
    }

    @Test
    fun `encode emits exactly one plain KISS frame`() {
        val ax25 = byteArrayOf(0x01, 0x02, 0x03, 0xC0.toByte(), 0xDB.toByte())
        val units = RadtelKissProfile.newCodec().encode(ax25, maxWriteBytes = 20)

        assertEquals(1, units.size)
        assertArrayEquals(KissCodec.encode(ax25), units.first())
    }

    @Test
    fun `decode reassembles a frame split across notifications`() {
        val ax25 = ByteArray(120) { (it and 0xFF).toByte() }
        val stream = KissCodec.encode(ax25)
        val codec = RadtelKissProfile.newCodec()

        val out = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < stream.size) {
            val end = minOf(offset + 20, stream.size)
            out += codec.decode(stream.copyOfRange(offset, end))
            offset = end
        }

        assertEquals(1, out.size)
        assertArrayEquals(ax25, out.first())
    }

    @Test
    fun `each connection gets a fresh codec`() {
        assertNotSame(RadtelKissProfile.newCodec(), RadtelKissProfile.newCodec())
    }
}

/**
 * Transport-level behaviour of the Benshi (BTECH UV-PRO) codec.
 *
 * These cover framing and fragment reassembly, which hold regardless of the exact
 * command identifiers in [BenshiProtocol]; the round trip is written against the codec
 * itself so it stays valid if those constants are corrected.
 */
class BenshiProfileTest {

    /**
     * Rewrites an outgoing HT_SEND_DATA unit into the DATA_RXD event the radio actually
     * pushes: same fragment, but wrapped as an event notification.
     */
    private fun asInboundEvent(outgoingUnit: ByteArray): ByteArray {
        val fragment = outgoingUnit.copyOfRange(4, outgoingUnit.size)
        val msg = ByteArray(4 + 1 + fragment.size)
        msg[0] = 0
        msg[1] = BenshiProtocol.GROUP_BASIC.toByte()
        msg[2] = ((BenshiProtocol.CMD_EVENT_NOTIFICATION ushr 8) and 0xFF).toByte()
        msg[3] = (BenshiProtocol.CMD_EVENT_NOTIFICATION and 0xFF).toByte()
        msg[4] = BenshiProtocol.EVENT_DATA_RXD.toByte()
        fragment.copyInto(msg, 5)
        return msg
    }

    @Test
    fun `gatt attributes match the benlink reference`() {
        assertEquals(
            UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5"),
            BenshiProfile.serviceUuid
        )
        assertEquals(
            UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5"),
            BenshiProfile.notifyCharUuid
        )
        assertEquals(
            UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5"),
            BenshiProfile.writeCharUuids.single().uuid
        )
    }

    @Test
    fun `protocol constants match benlink`() {
        assertEquals(2, BenshiProtocol.GROUP_BASIC)
        assertEquals(6, BenshiProtocol.CMD_REGISTER_NOTIFICATION)
        assertEquals(9, BenshiProtocol.CMD_EVENT_NOTIFICATION)
        assertEquals(31, BenshiProtocol.CMD_HT_SEND_DATA)
        assertEquals(1, BenshiProtocol.EVENT_HT_STATUS_CHANGED)
        assertEquals(2, BenshiProtocol.EVENT_DATA_RXD)
    }

    @Test
    fun `uses indications and never splits its own messages`() {
        assertTrue(BenshiProfile.usesIndications)
        assertFalse(BenshiProfile.chunkWritesToMtu)
    }

    @Test
    fun `transmits over BLE`() {
        assertTrue(BenshiProfile.supportsTx)
    }

    @Test
    fun `registers for events on connect, or the radio stays silent`() {
        val handshake = BenshiProfile.newCodec().onLinkReady(100)
        assertEquals(1, handshake.size)

        val msg = handshake.single()
        assertEquals(BenshiProtocol.GROUP_BASIC, ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF))
        assertEquals(
            BenshiProtocol.CMD_REGISTER_NOTIFICATION,
            ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        )
        // Registering HT_STATUS_CHANGED is what also switches DATA_RXD on.
        assertEquals(BenshiProtocol.EVENT_HT_STATUS_CHANGED, msg[4].toInt() and 0xFF)
    }

    @Test
    fun `radtel sends no handshake`() {
        assertTrue(RadtelKissProfile.newCodec().onLinkReady(100).isEmpty())
    }

    @Test
    fun `short frame encodes to a single final fragment`() {
        val ax25 = ByteArray(10) { it.toByte() }
        val units = BenshiProfile.newCodec().encode(ax25, maxWriteBytes = 100)

        assertEquals(1, units.size)
        assertEquals(
            BenshiProtocol.CMD_HT_SEND_DATA,
            ((units[0][2].toInt() and 0xFF) shl 8) or (units[0][3].toInt() and 0xFF)
        )
        val flags = units.first()[4].toInt() and 0xFF
        assertTrue("final bit must be set", (flags and 0x80) != 0)
        assertEquals("fragment id", 0, flags and 0x3F)
    }

    @Test
    fun `long frame is fragmented and every unit fits the mtu`() {
        val ax25 = ByteArray(300) { (it and 0xFF).toByte() }
        val mtu = 60
        val units = BenshiProfile.newCodec().encode(ax25, maxWriteBytes = mtu)

        assertTrue("should fragment", units.size > 1)
        units.forEach { assertTrue("unit of ${it.size}B exceeds MTU", it.size <= mtu) }

        val finals = units.count { (it[4].toInt() and 0x80) != 0 }
        assertEquals("exactly one final fragment", 1, finals)
    }

    @Test
    fun `encode then decode round trips the frame`() {
        val ax25 = ByteArray(300) { (it and 0xFF).toByte() }
        val units = BenshiProfile.newCodec().encode(ax25, maxWriteBytes = 60)

        val codec = BenshiProfile.newCodec()
        val out = units.map { asInboundEvent(it) }.flatMap { codec.decode(it) }

        assertEquals(1, out.size)
        assertArrayEquals(ax25, out.first())
    }

    @Test
    fun `channel id is a trailer, not a header`() {
        val payload = byteArrayOf(0x11, 0x22, 0x33)
        // final | with_channel_id | fragment 0, then data, then the channel id byte
        val msg = byteArrayOf(
            0x00, BenshiProtocol.GROUP_BASIC.toByte(),
            0x00, BenshiProtocol.CMD_EVENT_NOTIFICATION.toByte(),
            BenshiProtocol.EVENT_DATA_RXD.toByte(),
            (0x80 or 0x40).toByte(),
            0x11, 0x22, 0x33,
            0x07
        )
        val out = BenshiProfile.newCodec().decode(msg)
        assertEquals(1, out.size)
        assertArrayEquals(payload, out.first())
    }

    @Test
    fun `non-data events are ignored`() {
        val codec = BenshiProfile.newCodec()
        val statusEvent = byteArrayOf(
            0x00, BenshiProtocol.GROUP_BASIC.toByte(),
            0x00, BenshiProtocol.CMD_EVENT_NOTIFICATION.toByte(),
            BenshiProtocol.EVENT_HT_STATUS_CHANGED.toByte(),
            0x11, 0x22
        )
        assertTrue(codec.decode(statusEvent).isEmpty())
    }

    @Test
    fun `unrelated commands are ignored`() {
        val codec = BenshiProfile.newCodec()
        val other = byteArrayOf(0x00, 0x02, 0x00, 0x14, 0x11, 0x22)
        assertTrue(codec.decode(other).isEmpty())
    }

    @Test
    fun `a dropped fragment does not emit a corrupt frame`() {
        val ax25 = ByteArray(300) { (it and 0xFF).toByte() }
        val units = BenshiProfile.newCodec().encode(ax25, maxWriteBytes = 60)
            .map { asInboundEvent(it) }

        val codec = BenshiProfile.newCodec()
        val out = units.filterIndexed { i, _ -> i != 1 }.flatMap { codec.decode(it) }

        assertTrue("a frame with a hole must never be emitted", out.isEmpty())
    }

    @Test
    fun `empty payload encodes to nothing`() {
        assertTrue(BenshiProfile.newCodec().encode(ByteArray(0), 100).isEmpty())
    }
}

class RadioModelTest {

    @Test
    fun `default is the radtel, so existing installs keep working`() {
        assertEquals(RadioModel.RADTEL_RT950_PRO, RadioModel.DEFAULT)
        assertEquals(RadioModel.RADTEL_RT950_PRO, RadioModel.fromId(null))
        assertEquals(RadioModel.RADTEL_RT950_PRO, RadioModel.fromId("nonsense"))
    }

    @Test
    fun `persisted ids are stable`() {
        // Changing these strings would silently reset every user's radio choice.
        assertEquals("radtel_rt950_pro", RadioModel.RADTEL_RT950_PRO.id)
        assertEquals("btech_uv_pro", RadioModel.BTECH_UV_PRO.id)
        assertEquals(RadioModel.BTECH_UV_PRO, RadioModel.fromId("btech_uv_pro"))
    }

    @Test
    fun `every model resolves to its own profile`() {
        RadioModel.entries.forEach { assertEquals(it, it.profile.model) }
    }
}
