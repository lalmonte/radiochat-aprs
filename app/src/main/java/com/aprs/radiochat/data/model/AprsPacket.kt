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
 * Result of parsing an AX.25/APRS frame (after stripping the KISS wrapper).
 */
sealed class AprsPacket {
    abstract val sourceCall: String
    abstract val destCall: String
    abstract val rawInfo: String

    data class TextMessage(
        override val sourceCall: String,
        override val destCall: String,
        override val rawInfo: String,
        val addressee: String,
        val messageText: String,
        val messageId: String? = null
    ) : AprsPacket()

    data class Position(
        override val sourceCall: String,
        override val destCall: String,
        override val rawInfo: String,
        val latitude: Double,
        val longitude: Double,
        val symbol: String,
        val comment: String
    ) : AprsPacket()

    data class Other(
        override val sourceCall: String,
        override val destCall: String,
        override val rawInfo: String
    ) : AprsPacket()
}
