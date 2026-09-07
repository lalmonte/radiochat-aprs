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
package com.aprs.radiochat.data.aprsis

import com.aprs.radiochat.data.aprs.Ax25Frame

/**
 * TNC2 / APRS-IS format: `SOURCE>DEST[,DIGI…]:info`
 */
object Tnc2Codec {

    data class Frame(
        val source: String,
        val destination: String,
        val digipeaters: List<String>,
        val info: String
    ) {
        val pathForGate: String
            get() = buildString {
                append(destination)
                digipeaters.forEach { d ->
                    append(',')
                    append(d.removeSuffix("*"))
                }
            }
    }

    fun format(source: String, destination: String, digipeaters: List<String>, info: String): String {
        val digis = digipeaters.joinToString("") { ",$it" }
        return "$source>$destination$digis:$info"
    }

    fun fromAx25(payload: ByteArray): Frame? {
        val ax = Ax25Frame.parse(payload) ?: return null
        return Frame(
            source = ax.source,
            destination = ax.destination,
            digipeaters = ax.digipeaters,
            info = ax.infoText
        )
    }

    fun parse(line: String): Frame? {
        val trimmed = line.trimEnd('\r', '\n')
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return null
        val gt = trimmed.indexOf('>')
        val colon = trimmed.indexOf(':')
        if (gt <= 0 || colon <= gt) return null
        val source = trimmed.substring(0, gt).trim()
        val path = trimmed.substring(gt + 1, colon)
        val info = trimmed.substring(colon + 1)
        val parts = path.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return Frame(
            source = source,
            destination = parts.first(),
            digipeaters = parts.drop(1),
            info = info
        )
    }

    /**
     * APRS third-party packet: the Info Field starts with `}` and carries
     * another TNC2 frame (`}OTA>APRS,TCPIP*::CALL :text`).
     * Returns the innermost frame, or null when there is no `}`.
     */
    fun unwrapThirdParty(info: String): Frame? {
        var current = info.trimStart()
        if (!current.startsWith('}')) return null
        var innermost: Frame? = null
        var depth = 0
        while (current.startsWith('}') && depth < 4) {
            val frame = parse(current.substring(1)) ?: break
            innermost = frame
            current = frame.info.trimStart()
            depth++
        }
        return innermost
    }

    /** Effective source / dest / info after stripping `}` wrappers. */
    fun effective(source: String, destination: String, info: String): Frame {
        val inner = unwrapThirdParty(info) ?: return Frame(
            source = source,
            destination = destination,
            digipeaters = emptyList(),
            info = info
        )
        return inner
    }

    /** Avoids loops: do not gate packets that already came from the Internet. */
    fun shouldGateToIs(frame: Frame): Boolean {
        val path = (listOf(frame.destination) + frame.digipeaters)
            .joinToString(",")
            .uppercase()
        if (path.contains("TCPIP") || path.contains("TCPXX")) return false
        if (path.contains(",QA") || path.startsWith("QA") || Regex(",QA[A-Z]").containsMatchIn(path)) {
            return false
        }
        // Third-party / already gated
        if (frame.info.startsWith('}')) return false
        return frame.source.isNotBlank() && frame.info.isNotBlank()
    }
}
