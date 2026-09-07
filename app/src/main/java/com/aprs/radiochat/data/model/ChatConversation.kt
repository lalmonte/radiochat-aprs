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

/**
 * Summary of a conversation with one callsign (peer).
 */
data class ChatConversation(
    val peerCallsign: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val messageCount: Int,
    val unreadCount: Int = 0
) {
    val unread: Boolean get() = unreadCount > 0
}

fun conversationsFrom(messages: List<ChatMessage>): List<ChatConversation> =
    messages.groupBy { it.peer.uppercase() }
        .map { (peer, msgs) ->
            val last = msgs.maxBy { it.timestamp }
            ChatConversation(
                peerCallsign = peer,
                lastMessage = last.text,
                lastTimestamp = last.timestamp,
                messageCount = msgs.size,
                unreadCount = msgs.count { !it.isOutgoing && !it.isRead }
            )
        }
        .sortedByDescending { it.lastTimestamp }
