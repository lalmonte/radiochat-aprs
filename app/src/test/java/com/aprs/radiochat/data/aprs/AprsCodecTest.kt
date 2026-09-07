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
package com.aprs.radiochat.data.aprs

import com.aprs.radiochat.data.kiss.KissCodec
import com.aprs.radiochat.data.model.AprsPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AprsCodecTest {

    @Test
    fun message_buildAck() {
        val ack = AprsMessageCodec.buildAck("HI3LAG-7", "01")
        assertEquals(":HI3LAG-7 :ack01", ack)
        assertTrue(AprsMessageCodec.isAckOrRej("ack01"))
    }

    @Test
    fun message_buildPadsAddresseeTo9() {
        val info = AprsMessageCodec.build("EA1ABC", "Hola")
        assertEquals(":EA1ABC   :Hola", info)
    }

    @Test
    fun message_parseExtractsFields() {
        val parsed = AprsMessageCodec.parse(":EA1ABC   :Hola mundo{01")
        assertNotNull(parsed)
        assertEquals("EA1ABC", parsed!!.addressee)
        assertEquals("Hola mundo", parsed.text)
        assertEquals("01", parsed.messageId)
    }

    @Test
    fun message_parseUnpaddedAddressee_direwolfStyle() {
        val parsed = AprsMessageCodec.parse(":HI3LAG-7:Hola TCP{03")
        assertNotNull(parsed)
        assertEquals("HI3LAG-7", parsed!!.addressee)
        assertEquals("Hola TCP", parsed.text)
        assertEquals("03", parsed.messageId)
        assertTrue(AprsMessageCodec.isAddressedTo(parsed.addressee, "HI3LAG-7"))
        assertTrue(!AprsMessageCodec.isAddressedTo(parsed.addressee, "HI3LAG"))
        assertTrue(!AprsMessageCodec.isAddressedTo(parsed.addressee, "HI3LAG-9"))
    }

    @Test
    fun isAddressedTo_requiresMatchingSsid() {
        assertTrue(AprsMessageCodec.isAddressedTo("HI3LAG-7", "hi3lag-7"))
        assertTrue(AprsMessageCodec.isAddressedTo("HI3LAG", "HI3LAG-0"))
        assertTrue(!AprsMessageCodec.isAddressedTo("HI3LAG-9", "HI3LAG-7"))
        assertTrue(!AprsMessageCodec.isAddressedTo("HI3LAG", "HI3LAG-7"))
        assertTrue(!AprsMessageCodec.isAddressedTo("HI3LAG-7", "HI3LAG"))
    }

    @Test
    fun isAckOrRej_onlyAprsAckTokens() {
        assertTrue(AprsMessageCodec.isAckOrRej("ack01"))
        assertTrue(AprsMessageCodec.isAckOrRej("ack"))
        assertTrue(!AprsMessageCodec.isAckOrRej("acknowledged"))
        assertTrue(!AprsMessageCodec.isAckOrRej("Hola ack"))
    }

    @Test
    fun parseAckOrRej_extractsIdAndReject() {
        val ack = AprsMessageCodec.parseAckOrRej("ack01")
        assertNotNull(ack)
        assertEquals("01", ack!!.messageId)
        assertTrue(!ack.rejected)
        val rej = AprsMessageCodec.parseAckOrRej("REJ12")
        assertNotNull(rej)
        assertEquals("12", rej!!.messageId)
        assertTrue(rej.rejected)
        assertEquals("05", AprsMessageCodec.ackMessageId("ack", "05"))
        assertEquals("01", AprsMessageCodec.ackMessageId("ack01", null))
        assertTrue(AprsMessageCodec.extractAck(":HI3LAG   :ack01") != null)
        assertEquals("01", AprsMessageCodec.extractAck(":HI3LAG   :ack01")!!.messageId)
        assertEquals("01", AprsMessageCodec.extractAck("ack01{")!!.messageId)
        assertEquals("01", AprsMessageCodec.extractAck("ack01}")!!.messageId)
        assertEquals("01", AprsMessageCodec.extractAck(":HI3LAG-7:ack01}")!!.messageId)
        assertTrue(AprsMessageCodec.isAckAddressedTo("HI3LAG-7", "HI3LAG-7"))
        assertTrue(AprsMessageCodec.isAckAddressedTo("HI3LAG", "HI3LAG-7"))
        assertTrue(!AprsMessageCodec.isAckAddressedTo("HI3LAG-3", "HI3LAG-7"))
        assertTrue(AprsMessageCodec.sameMessageId("01", "1"))
        assertTrue(AprsMessageCodec.sameMessageId("01", "01"))
        assertTrue(!AprsMessageCodec.sameMessageId("01", "02"))
    }

    @Test
    fun parseKissPayload_bareAckOnAprsDest() {
        val parser = AprsPacketParser(myCallsign = { "HI3LAG-7" })
        val frame = Ax25Frame.buildUiFrame(
            destination = "APRS",
            source = "OTA",
            info = "ack01".toByteArray(Charsets.ISO_8859_1),
            digipeaters = listOf("WIDE1-1")
        )
        val packet = parser.parseKissPayload(frame)
        assertTrue(packet is AprsPacket.TextMessage)
        val msg = packet as AprsPacket.TextMessage
        assertEquals("OTA", msg.sourceCall)
        assertTrue(AprsMessageCodec.extractAck(msg.messageText) != null)
        assertEquals("01", AprsMessageCodec.extractAck(msg.messageText)!!.messageId)
    }

    @Test
    fun parseKissPayload_ackAddressedToBaseCallsign() {
        val parser = AprsPacketParser(myCallsign = { "HI3LAG-7" })
        val info = AprsMessageCodec.buildAck("HI3LAG", "01")
        val frame = Ax25Frame.buildUiFrame(
            destination = "APRS",
            source = "OTA",
            info = info.toByteArray(Charsets.ISO_8859_1),
            digipeaters = emptyList()
        )
        val packet = parser.parseKissPayload(frame)
        assertTrue(packet is AprsPacket.TextMessage)
        val msg = packet as AprsPacket.TextMessage
        assertEquals("HI3LAG", msg.addressee)
        assertTrue(!AprsMessageCodec.isAddressedTo(msg.addressee, "HI3LAG-7"))
        assertTrue(AprsMessageCodec.extractAck(msg.messageText) != null)
    }

    @Test
    fun parseKissPayload_directedAx25DestIsMessage() {
        val parser = AprsPacketParser(myCallsign = { "HI3LAG-7" })
        val frame = Ax25Frame.buildUiFrame(
            destination = "HI3LAG-7",
            source = "EA1ABC-9",
            info = "Hello from RF".toByteArray(Charsets.ISO_8859_1),
            digipeaters = emptyList()
        )
        val packet = parser.parseKissPayload(frame)
        assertTrue(packet is AprsPacket.TextMessage)
        val msg = packet as AprsPacket.TextMessage
        assertEquals("EA1ABC-9", msg.sourceCall)
        assertEquals("HI3LAG-7", msg.addressee)
        assertEquals("Hello from RF", msg.messageText)
        assertTrue(AprsMessageCodec.isAddressedTo(msg.addressee, "HI3LAG-7"))
        assertTrue(AprsMessageCodec.isAddressedTo(msg.destCall, "HI3LAG-7"))
    }

    @Test
    fun position_uncompressed() {
        val info = "!4903.50N/07201.75W-Test"
        val pos = AprsPositionParser.parse(info)
        assertNotNull(pos)
        assertEquals(49.058333, pos!!.latitude, 0.001)
        assertEquals(-72.029166, pos.longitude, 0.001)
        assertEquals("/-", pos.symbol)
        assertEquals("Test", pos.comment)
    }

    @Test
    fun position_formatRoundTrip() {
        val info = AprsPositionParser.formatUncompressed(
            latitude = 18.4861,
            longitude = -69.9312,
            symbol = "/$",
            comment = "TestPhone",
            messagingCapable = true
        )
        assertTrue(info.startsWith("="))
        val pos = AprsPositionParser.parse(info)
        assertNotNull(pos)
        assertEquals(18.4861, pos!!.latitude, 0.001)
        assertEquals(-69.9312, pos.longitude, 0.001)
        assertEquals("/$", pos.symbol)
        assertEquals("TestPhone", pos.comment)
    }

    @Test
    fun sameCallsignWithSsid_distinguishesSsids() {
        assertTrue(AprsMessageCodec.sameCallsignWithSsid("HI3LAG-7", "hi3lag-7"))
        assertTrue(AprsMessageCodec.sameCallsignWithSsid("HI3LAG", "HI3LAG-0"))
        assertTrue(!AprsMessageCodec.sameCallsignWithSsid("HI3LAG-7", "HI3LAG-9"))
        assertTrue(!AprsMessageCodec.sameCallsignWithSsid("HI3LAG-7", "HI3LAG-3"))
        assertTrue(!AprsMessageCodec.sameCallsignWithSsid("HI3LAG-7", "HI3LAG"))
    }

    @Test
    fun kissRoundTrip_parsesIncomingMessage() {
        val parser = AprsPacketParser(myCallsign = { "HI3LAG-9" })
        val ax25 = parser.buildOutgoingMessage(
            toCallsign = "HI3LAG-7",
            text = "Hola TCP",
            messageId = "01",
            digipeaters = listOf("WIDE1-1", "WIDE2-1")
        )
        val kiss = KissCodec.encode(ax25)
        val payloads = KissCodec.Decoder().feed(kiss).filter { it.isNotEmpty() }
        assertEquals(1, payloads.size)
        val packet = parser.parseKissPayload(payloads[0])
        assertTrue(packet is AprsPacket.TextMessage)
        val msg = packet as AprsPacket.TextMessage
        assertEquals("HI3LAG-9", msg.sourceCall)
        assertEquals("HI3LAG-7", msg.addressee)
        assertEquals("Hola TCP", msg.messageText)
        assertEquals("01", msg.messageId)
        assertTrue(!AprsMessageCodec.sameCallsignWithSsid(msg.sourceCall, "HI3LAG-7"))
        assertTrue(AprsMessageCodec.isAddressedTo(msg.addressee, "HI3LAG-7"))
    }

    @Test
    fun parseKissPayload_unwrapsOtaThirdParty() {
        val parser = AprsPacketParser(myCallsign = { "HI3LAG-7" })
        val innerInfo = "}OTA>APRS,TCPIP*::HI3LAG-7 :Hola OTA{05"
        val frame = Ax25Frame.buildUiFrame(
            destination = "APRS",
            source = "WIDE1-1",
            info = innerInfo.toByteArray(Charsets.ISO_8859_1),
            digipeaters = listOf("WIDE2-1")
        )
        val packet = parser.parseKissPayload(frame)
        assertTrue(packet is AprsPacket.TextMessage)
        val msg = packet as AprsPacket.TextMessage
        assertEquals("OTA", msg.sourceCall)
        assertEquals("HI3LAG-7", msg.addressee)
        assertEquals("Hola OTA", msg.messageText)
        assertEquals("05", msg.messageId)
        assertTrue(AprsMessageCodec.isAddressedTo(msg.addressee, "HI3LAG-7"))
    }

    @Test
    fun parseKissPayload_stripsTrailingFcs() {
        val parser = AprsPacketParser(myCallsign = { "N0CALL" })
        val ax25 = parser.buildOutgoingMessage(
            toCallsign = "HI3LAG-7",
            text = "Con FCS",
            messageId = "02"
        )
        val withFcs = ax25 + byteArrayOf(0x12, 0x34)
        val packet = parser.parseKissPayload(withFcs)
        assertTrue(packet is AprsPacket.TextMessage)
        val msg = packet as AprsPacket.TextMessage
        assertEquals("Con FCS", msg.messageText)
        assertEquals("02", msg.messageId)
    }
}
