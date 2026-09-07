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
package com.aprs.radiochat.data.kiss

/**
 * KISS TNC codec (RFC-like / TAPR KISS).
 *
 * Control bytes:
 * - FEND  = 0xC0  frame start/end
 * - FESC  = 0xDB  escape
 * - TFEND = 0xDC  escaped literal FEND (after FESC)
 * - TFESC = 0xDD  escaped literal FESC (after FESC)
 *
 * Typical frame: FEND | CMD | DATA... | FEND
 * CMD = 0x00 → data to TNC port 0 (UI/AX.25).
 */
object KissCodec {

    const val FEND: Byte = 0xC0.toByte()
    const val FESC: Byte = 0xDB.toByte()
    const val TFEND: Byte = 0xDC.toByte()
    const val TFESC: Byte = 0xDD.toByte()

    /** KISS command: data (port 0). */
    const val CMD_DATA: Byte = 0x00

    /** KISS command: TX delay (next byte = units × 10 ms). */
    const val CMD_TXDELAY: Byte = 0x01

    /**
     * KISS stream for TX: TXDELAY + data frame.
     * Some internal TNCs (Radtel) need the preamble before the UI frame.
     */
    fun encodeTxStream(ax25Payload: ByteArray, txDelayUnits: Int = 30): ByteArray {
        val delay = encode(byteArrayOf(txDelayUnits.toByte()), CMD_TXDELAY)
        val data = encode(ax25Payload)
        return delay + data
    }

    /**
     * Wraps AX.25/APRS bytes in a KISS frame ready to send over UART/BLE.
     */
    fun encode(payload: ByteArray, command: Byte = CMD_DATA): ByteArray {
        val out = ArrayList<Byte>(payload.size + 8)
        out.add(FEND)
        out.add(command)
        for (b in payload) {
            when (b) {
                FEND -> {
                    out.add(FESC)
                    out.add(TFEND)
                }
                FESC -> {
                    out.add(FESC)
                    out.add(TFESC)
                }
                else -> out.add(b)
            }
        }
        out.add(FEND)
        return out.toByteArray()
    }

    /**
     * Incremental decoder: feed it BLE bytes and it emits complete payloads (no CMD byte).
     * Tolerates frames fragmented across GATT notifications.
     */
    class Decoder {
        private val buffer = ArrayList<Byte>(256)
        private var inFrame = false
        private var escaped = false

        /**
         * @return list of complete AX.25/APRS payloads (command byte dropped).
         */
        fun feed(chunk: ByteArray): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            for (b in chunk) {
                when {
                    !inFrame -> {
                        if (b == FEND) {
                            inFrame = true
                            buffer.clear()
                            escaped = false
                        }
                    }
                    b == FEND -> {
                        // Closing FEND: if there is data, emit the frame
                        if (buffer.isNotEmpty()) {
                            frames += extractPayload(buffer.toByteArray())
                        }
                        buffer.clear()
                        escaped = false
                        // Stay in frame: a FEND can also open the next one
                        inFrame = true
                    }
                    escaped -> {
                        when (b) {
                            TFEND -> buffer.add(FEND)
                            TFESC -> buffer.add(FESC)
                            else -> buffer.add(b) // invalid byte: keep it
                        }
                        escaped = false
                    }
                    b == FESC -> escaped = true
                    else -> buffer.add(b)
                }
            }
            return frames
        }

        fun reset() {
            buffer.clear()
            inFrame = false
            escaped = false
        }

        /**
         * Strips the KISS command byte (port<<4 | cmd) and returns the AX.25 payload.
         * Only data frames are forwarded (cmd 0); TXDELAY/FULLDUP/etc. are ignored.
         */
        private fun extractPayload(frame: ByteArray): ByteArray {
            if (frame.size < 2) return ByteArray(0)
            val cmd = frame[0].toInt() and 0x0F
            if (cmd != 0) return ByteArray(0)
            return frame.copyOfRange(1, frame.size)
        }
    }
}
