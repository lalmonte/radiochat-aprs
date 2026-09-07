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
 * APRS text message (addressed format `:CALLSIGN :text`).
 */
data class ChatMessage(
    val id: String,
    val from: String,
    val to: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val source: MessageSource,
    /** Conversation peer (the other callsign). */
    val peer: String,
    val isRead: Boolean = false,
    /** APRS id `{01` used to match ACK/REJ. */
    val messageId: String? = null,
    val ackStatus: MessageAckStatus = MessageAckStatus.NONE
)
