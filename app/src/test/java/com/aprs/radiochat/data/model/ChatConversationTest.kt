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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationTest {

    @Test
    fun unreadCount_onlyIncomingUnread() {
        val convs = conversationsFrom(
            listOf(
                msg("1", "OTA", outgoing = false, read = false, t = 1),
                msg("2", "OTA", outgoing = false, read = false, t = 2),
                msg("3", "OTA", outgoing = true, read = true, t = 3)
            )
        )
        assertEquals(1, convs.size)
        assertEquals(2, convs[0].unreadCount)
        assertTrue(convs[0].unread)
        assertEquals(3, convs[0].messageCount)
    }

    @Test
    fun unreadCount_zeroWhenOpened() {
        val convs = conversationsFrom(
            listOf(
                msg("1", "OTA", outgoing = false, read = true, t = 1),
                msg("2", "OTA", outgoing = false, read = true, t = 2)
            )
        )
        assertEquals(0, convs[0].unreadCount)
        assertFalse(convs[0].unread)
    }

    @Test
    fun unreadCount_perPeer() {
        val convs = conversationsFrom(
            listOf(
                msg("1", "OTA", outgoing = false, read = false, t = 10),
                msg("2", "EA1ABC", outgoing = false, read = true, t = 20)
            )
        )
        assertEquals("EA1ABC", convs[0].peerCallsign)
        assertEquals(0, convs[0].unreadCount)
        assertEquals("OTA", convs[1].peerCallsign)
        assertEquals(1, convs[1].unreadCount)
    }

    private fun msg(
        id: String,
        peer: String,
        outgoing: Boolean,
        read: Boolean,
        t: Long
    ) = ChatMessage(
        id = id,
        from = if (outgoing) "ME" else peer,
        to = if (outgoing) peer else "ME",
        text = "hola $id",
        timestamp = t,
        isOutgoing = outgoing,
        source = if (outgoing) MessageSource.OUTGOING else MessageSource.INTERNET,
        peer = peer,
        isRead = read
    )
}
