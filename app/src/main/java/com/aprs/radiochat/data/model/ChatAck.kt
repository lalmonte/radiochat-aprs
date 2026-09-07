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

import com.aprs.radiochat.data.aprs.AprsMessageCodec

/**
 * Matches an APRS ACK/REJ with the outgoing message that asked for it (`{msgid`).
 */
object ChatAck {

    fun apply(
        messages: List<ChatMessage>,
        fromPeer: String,
        messageId: String,
        rejected: Boolean
    ): List<ChatMessage> {
        val peer = fromPeer.removeSuffix("*").trim()
        val idx = resolveIndex(messages, peer, messageId.trim())
        if (idx < 0) return messages
        val status = if (rejected) MessageAckStatus.REJECTED else MessageAckStatus.DELIVERED
        if (messages[idx].ackStatus == status) return messages
        return messages.mapIndexed { i, msg ->
            if (i == idx) msg.copy(ackStatus = status) else msg
        }
    }

    private fun resolveIndex(
        messages: List<ChatMessage>,
        peer: String,
        messageId: String
    ): Int {
        val byPeerAndId = indexOfOutgoingAwaitingAck(messages, peer, messageId)
        if (byPeerAndId >= 0) return byPeerAndId
        // Known id: don't fall back to the peer's last SENT (a duplicate ACK would
        // mark another message). If the source doesn't match (digi/SSID), search by id only.
        if (messageId.isNotBlank()) {
            if (AprsMessageCodec.isLikelyDigipeater(peer)) {
                return indexOfOutgoingAwaitingAckByIdOnly(messages, messageId)
            }
            return -1
        }
        return indexOfLastPendingToPeer(messages, peer)
    }

    fun indexOfOutgoingAwaitingAck(
        messages: List<ChatMessage>,
        fromPeer: String,
        messageId: String
    ): Int {
        val id = messageId.trim()
        fun isCandidate(msg: ChatMessage): Boolean {
            if (!msg.isOutgoing) return false
            if (msg.ackStatus == MessageAckStatus.DELIVERED ||
                msg.ackStatus == MessageAckStatus.REJECTED
            ) {
                return false
            }
            val stored = msg.messageId?.trim().orEmpty()
            if (id.isEmpty()) return stored.isEmpty()
            if (stored.isEmpty()) return false
            return AprsMessageCodec.sameMessageId(stored, id)
        }

        val exact = messages.indexOfLast { msg ->
            isCandidate(msg) &&
                AprsMessageCodec.sameCallsignWithSsid(msg.peer, fromPeer)
        }
        if (exact >= 0) return exact
        return -1
    }

    private fun indexOfOutgoingAwaitingAckByIdOnly(
        messages: List<ChatMessage>,
        messageId: String
    ): Int {
        return messages.indexOfLast { msg ->
            msg.isOutgoing &&
                msg.ackStatus == MessageAckStatus.SENT &&
                AprsMessageCodec.sameMessageId(msg.messageId.orEmpty(), messageId)
        }
    }

    private fun indexOfLastPendingToPeer(messages: List<ChatMessage>, fromPeer: String): Int {
        val exact = messages.indexOfLast { msg ->
            msg.isOutgoing &&
                msg.ackStatus == MessageAckStatus.SENT &&
                AprsMessageCodec.sameCallsignWithSsid(msg.peer, fromPeer)
        }
        if (exact >= 0) return exact
        return -1
    }
}
