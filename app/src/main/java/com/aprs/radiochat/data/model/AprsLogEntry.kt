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
 * Entry in the APRS traffic monitor (RF/BLE or Internet).
 */
data class AprsLogEntry(
    val id: String,
    val timestamp: Long,
    val source: LogTransport,
    val kind: LogKind,
    val from: String,
    val to: String,
    val path: String,
    val summary: String,
    val raw: String,
    /** APRS table/symbol pair (`/_`, `\W`). Empty until a beacon has been heard. */
    val symbol: String = ""
)

enum class LogTransport {
    RF_BLE,
    TNC_TCP,
    INTERNET
}

enum class LogKind {
    MESSAGE,
    POSITION,
    OTHER,
    RAW
}
