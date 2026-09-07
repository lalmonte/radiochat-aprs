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

/**
 * Building and taking apart AX.25 UI frames (APRS).
 *
 * AX.25 address: 6 callsign chars (<<1) + SSID byte.
 * - Destination: C/R bit = 1 (command) → mask 0xE0
 * - Source/digis: mask 0x60; LSB = 1 marks the end of the address list
 */
object Ax25Frame {

    private const val CONTROL_UI: Int = 0x03
    private const val PID_NO_L3: Int = 0xF0

    /**
     * Builds an AX.25 UI frame: Dest + Source + [digis…] + Control + PID + Info.
     * Typical APRS path: WIDE1-1,WIDE2-1
     */
    fun buildUiFrame(
        destination: String,
        source: String,
        info: ByteArray,
        digipeaters: List<String> = listOf("WIDE1-1", "WIDE2-1")
    ): ByteArray {
        val (destCall, destSsid) = splitCallSsid(destination)
        val (srcCall, srcSsid) = splitCallSsid(source)
        val digis = digipeaters.map { splitCallSsid(it) }

        val parts = ArrayList<Byte>(7 * (2 + digis.size) + 2 + info.size)

        // Destination: never the last one when there is a source
        parts += encodeAddress(destCall, destSsid, last = false, command = true).toList()

        // Source: last only when there are no digipeaters
        parts += encodeAddress(
            srcCall,
            srcSsid,
            last = digis.isEmpty(),
            command = false
        ).toList()

        digis.forEachIndexed { index, (call, ssid) ->
            parts += encodeAddress(
                call,
                ssid,
                last = index == digis.lastIndex,
                command = false
            ).toList()
        }

        parts += CONTROL_UI.toByte()
        parts += PID_NO_L3.toByte()
        parts += info.toList()
        return parts.toByteArray()
    }

    /**
     * Parses Dest, Source, digipeaters and Info Field of an AX.25 UI frame.
     */
    fun parse(frame: ByteArray): ParsedAx25? {
        if (frame.size < 16) return null
        var offset = 0
        val addresses = mutableListOf<String>()
        while (offset + 7 <= frame.size) {
            val block = frame.copyOfRange(offset, offset + 7)
            offset += 7
            addresses += decodeAddress(block)
            val isLast = (block[6].toInt() and 0x01) != 0
            if (isLast) break
            if (addresses.size > 10) return null
        }
        if (addresses.size < 2) return null
        if (offset + 2 > frame.size) return null
        val info = frame.copyOfRange(offset + 2, frame.size)
        return ParsedAx25(
            destination = addresses[0],
            source = addresses[1],
            digipeaters = addresses.drop(2),
            info = info
        )
    }

    data class ParsedAx25(
        val destination: String,
        val source: String,
        val digipeaters: List<String>,
        val info: ByteArray
    ) {
        val infoText: String
            get() = info.toString(Charsets.ISO_8859_1).trimEnd('\u0000', '\r', '\n')
    }

    fun splitCallSsid(callsign: String): Pair<String, Int> {
        val raw = callsign.uppercase().trim()
        val dash = raw.lastIndexOf('-')
        if (dash > 0 && dash < raw.lastIndex) {
            val ssidPart = raw.substring(dash + 1)
            val ssid = ssidPart.toIntOrNull()
            if (ssid != null && ssid in 0..15) {
                return raw.substring(0, dash) to ssid
            }
        }
        return raw to 0
    }

    private fun encodeAddress(
        callsign: String,
        ssid: Int,
        last: Boolean,
        command: Boolean
    ): ByteArray {
        val call = callsign.uppercase().padEnd(6, ' ').take(6)
        val out = ByteArray(7)
        for (i in 0 until 6) {
            out[i] = ((call[i].code shl 1) and 0xFE).toByte()
        }
        // Reserved bits set to 1; C/R=1 on the destination (command); E=1 on the last address
        val base = if (command) 0xE0 else 0x60
        var ssidByte = base or ((ssid and 0x0F) shl 1)
        if (last) ssidByte = ssidByte or 0x01
        out[6] = ssidByte.toByte()
        return out
    }

    private fun decodeAddress(block: ByteArray): String {
        val chars = CharArray(6) { i ->
            ((block[i].toInt() and 0xFF) shr 1).toChar()
        }
        val call = String(chars).trim()
        val ssid = (block[6].toInt() shr 1) and 0x0F
        return if (ssid > 0) "$call-$ssid" else call
    }
}
