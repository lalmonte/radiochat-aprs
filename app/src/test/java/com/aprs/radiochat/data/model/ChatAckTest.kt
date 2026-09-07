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
package com.aprs.radiochat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAckTest {

    @Test
    fun apply_marksLatestOutgoingAsDelivered() {
        val sent = outgoing("OTA", "01", MessageAckStatus.SENT, t = 1)
        val older = outgoing("OTA", "01", MessageAckStatus.SENT, t = 0)
        val next = ChatAck.apply(listOf(older, sent), "OTA", "01", rejected = false)
        assertEquals(MessageAckStatus.SENT, next[0].ackStatus)
        assertEquals(MessageAckStatus.DELIVERED, next[1].ackStatus)
    }

    @Test
    fun apply_rejectedSetsRejected() {
        val sent = outgoing("OTA", "07", MessageAckStatus.SENT)
        val next = ChatAck.apply(listOf(sent), "OTA", "07", rejected = true)
        assertEquals(MessageAckStatus.REJECTED, next.single().ackStatus)
    }

    @Test
    fun apply_doesNotMatchDifferentSsid() {
        val sent = outgoing("HI3LAG-7", "03", MessageAckStatus.SENT)
        val list = listOf(sent)
        val next = ChatAck.apply(list, "HI3LAG-3", "03", rejected = false)
        assertTrue(next === list)
        assertEquals(MessageAckStatus.SENT, next.single().ackStatus)
    }

    @Test
    fun apply_matchesExactSsid() {
        val sent = outgoing("HI3LAG-3", "03", MessageAckStatus.SENT)
        val next = ChatAck.apply(listOf(sent), "HI3LAG-3", "03", rejected = false)
        assertEquals(MessageAckStatus.DELIVERED, next.single().ackStatus)
    }

    @Test
    fun apply_ignoresAlreadyDelivered() {
        val done = outgoing("OTA", "01", MessageAckStatus.DELIVERED)
        val waiting = outgoing("OTA", "02", MessageAckStatus.SENT)
        val list = listOf(done, waiting)
        val next = ChatAck.apply(list, "OTA", "01", rejected = false)
        assertTrue(next === list)
        assertEquals(MessageAckStatus.SENT, next[1].ackStatus)
    }

    @Test
    fun apply_matchesMsgIdWithLeadingZero() {
        val sent = outgoing("OTA", "01", MessageAckStatus.SENT)
        val next = ChatAck.apply(listOf(sent), "OTA", "1", rejected = false)
        assertEquals(MessageAckStatus.DELIVERED, next.single().ackStatus)
    }

    @Test
    fun apply_matchesByMessageIdWhenPeerIsDigi() {
        val sent = outgoing("OTA", "01", MessageAckStatus.SENT)
        val next = ChatAck.apply(listOf(sent), "WIDE1-1", "01", rejected = false)
        assertEquals(MessageAckStatus.DELIVERED, next.single().ackStatus)
    }

    @Test
    fun apply_stripsHeardAsteriskFromSource() {
        val sent = outgoing("OTA", "05", MessageAckStatus.SENT)
        val next = ChatAck.apply(listOf(sent), "OTA*", "05", rejected = false)
        assertEquals(MessageAckStatus.DELIVERED, next.single().ackStatus)
    }

    @Test
    fun apply_blankIdMatchesLastPendingToPeer() {
        val sent = outgoing("OTA", "04", MessageAckStatus.SENT)
        val next = ChatAck.apply(listOf(sent), "OTA", "", rejected = false)
        assertEquals(MessageAckStatus.DELIVERED, next.single().ackStatus)
    }

    private fun outgoing(
        peer: String,
        messageId: String,
        status: MessageAckStatus,
        t: Long = 1L
    ) = ChatMessage(
        id = "$peer-$messageId-$t",
        from = "ME",
        to = peer,
        text = "hola",
        timestamp = t,
        isOutgoing = true,
        source = MessageSource.OUTGOING,
        peer = peer,
        isRead = true,
        messageId = messageId,
        ackStatus = status
    )
}
