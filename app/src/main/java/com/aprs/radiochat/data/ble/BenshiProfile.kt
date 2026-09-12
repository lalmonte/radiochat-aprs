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
package com.aprs.radiochat.data.ble

import android.util.Log
import java.util.UUID

/**
 * BTECH UV-PRO, and the other Benshi-based handhelds that share its firmware
 * (Vero VR-N76, RadioOddity GA-5WB).
 *
 * These radios do **not** speak KISS over BLE. They expose a vendor GATT service and a
 * framed message protocol; AX.25 frames travel inside "TNC data" messages that the
 * radio splits into fragments. The protocol was reverse engineered by the
 * [benlink](https://github.com/khusmann/benlink) project, which is the reference for
 * everything below.
 *
 * Two differences from the Radtel matter at the transport level:
 *  - the RX characteristic is an **indication**, not a notification;
 *  - messages are **atomic**, so a message must never be split across GATT writes.
 */
object BenshiProfile : BleRadioProfile {

    override val model = RadioModel.BTECH_UV_PRO

    override val serviceUuid: UUID =
        UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5")

    /** Indicate: radio → app */
    override val notifyCharUuid: UUID =
        UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")

    /** Write: app → radio */
    override val writeCharUuids = listOf(
        BleRadioProfile.WriteCandidate(
            UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5"), "BENSHI-TX"
        )
    )

    override val deviceNameHints = listOf(
        "UV-PRO", "UVPRO", "UV PRO", "BTECH", "Benshi", "VR-N76", "GA-5WB"
    )

    override val fallbackDeviceName = "UV-PRO"

    /** The RX characteristic indicates; enabling notifications instead delivers nothing. */
    override val usesIndications = true

    /** Message oriented: the codec sizes its own fragments, the manager must not split. */
    override val chunkWritesToMtu = false

    /** The UV-PRO transmits frames handed to it over BLE, unlike the RT-950. */
    override val supportsTx = true

    override fun newCodec(): RadioLinkCodec = BenshiLinkCodec()
}

/**
 * Wire constants for the Benshi protocol, taken from the
 * [benlink](https://github.com/khusmann/benlink) reference implementation
 * (`protocol/command/message.py`, `notification.py`, `common.py`).
 */
internal object BenshiProtocol {

    /** `CommandGroup.BASIC` — carries radio control and TNC data alike. */
    const val GROUP_BASIC = 2

    /** `BasicCommand.REGISTER_NOTIFICATION` — subscribe to an event class. */
    const val CMD_REGISTER_NOTIFICATION = 6

    /** `BasicCommand.EVENT_NOTIFICATION` — how the radio pushes every event. */
    const val CMD_EVENT_NOTIFICATION = 9

    /** `BasicCommand.HT_SEND_DATA` — hand a TNC data fragment to the radio. */
    const val CMD_HT_SEND_DATA = 31

    /** `EventType.HT_STATUS_CHANGED`. */
    const val EVENT_HT_STATUS_CHANGED = 1

    /** `EventType.DATA_RXD` — a received APRS / BSS message. */
    const val EVENT_DATA_RXD = 2

    /** Top bit of the command word marks a reply/event rather than a request. */
    const val REPLY_FLAG = 0x8000

    /** Guards against a runaway reassembly buffer if a final fragment never arrives. */
    const val MAX_REASSEMBLY_BYTES = 8 * 1024
}

/**
 * Encodes and decodes Benshi TNC-data messages.
 *
 * Message layout (big endian):
 * ```
 *   u16  command group
 *   u16  reply flag (bit 15) | command id (bits 14..0)
 *   ...  body
 * ```
 *
 * Received data is not its own command: the radio pushes everything as an
 * `EVENT_NOTIFICATION` whose body starts with an event type, and an AX.25 frame arrives
 * as `DATA_RXD`:
 * ```
 *   u8   event type          -- 2 = DATA_RXD
 *   ...  TNC data fragment
 * ```
 *
 * TNC data fragment — note the channel id is a **trailer**, not a header:
 * ```
 *   u8   is_final (bit 7) | with_channel_id (bit 6) | fragment_id (bits 5..0)
 *   ...  fragment of the AX.25 frame
 *   u8   channel id        -- only when with_channel_id is set
 * ```
 *
 * One BLE indication carries one complete message, so no cross-notification buffering
 * is needed here; reassembly happens at the fragment level instead.
 */
class BenshiLinkCodec : RadioLinkCodec {

    private val assembling = ArrayList<Byte>()
    private var expectNextFragment = 0

    override fun reset() {
        assembling.clear()
        expectNextFragment = 0
    }

    /**
     * The radio stays silent until asked to report events. Registering for
     * `HT_STATUS_CHANGED` is what also switches on `DATA_RXD`, which is the quirk
     * benlink documents; without it the link comes up and no packet ever arrives.
     */
    override fun onLinkReady(maxWriteBytes: Int): List<ByteArray> = listOf(
        message(
            BenshiProtocol.CMD_REGISTER_NOTIFICATION,
            byteArrayOf(BenshiProtocol.EVENT_HT_STATUS_CHANGED.toByte())
        )
    )

    override fun decode(chunk: ByteArray): List<ByteArray> {
        if (chunk.size < HEADER_BYTES) return emptyList()

        val group = readU16(chunk, 0)
        val word = readU16(chunk, 2)
        val command = word and BenshiProtocol.REPLY_FLAG.inv() and 0xFFFF

        if (group != BenshiProtocol.GROUP_BASIC ||
            command != BenshiProtocol.CMD_EVENT_NOTIFICATION
        ) {
            Log.d(TAG, "Ignoring group=$group command=$command")
            return emptyList()
        }

        val body = chunk.copyOfRange(HEADER_BYTES, chunk.size)
        // Event type, then the event itself.
        if (body.size < 2) return emptyList()
        val eventType = body[0].toInt() and 0xFF
        if (eventType != BenshiProtocol.EVENT_DATA_RXD) {
            Log.d(TAG, "Ignoring event type $eventType")
            return emptyList()
        }

        val flags = body[1].toInt() and 0xFF
        val isFinal = (flags and 0x80) != 0
        val withChannelId = (flags and 0x40) != 0
        val fragmentId = flags and 0x3F

        // Offsets are in `body`: [0] event type, [1] fragment flags, then the data,
        // with the channel id as a single trailing byte when present.
        val dataStart = 2
        val dataEnd = if (withChannelId) body.size - 1 else body.size
        if (dataEnd < dataStart) return emptyList()

        // A fragment out of sequence means we missed one; the frame would be corrupt.
        if (fragmentId != expectNextFragment) {
            if (assembling.isNotEmpty()) {
                Log.w(TAG, "Fragment $fragmentId out of order (expected $expectNextFragment); dropping frame")
            }
            reset()
            if (fragmentId != 0) return emptyList()
        }

        body.copyOfRange(dataStart, dataEnd).forEach { assembling.add(it) }

        if (assembling.size > BenshiProtocol.MAX_REASSEMBLY_BYTES) {
            Log.w(TAG, "Reassembly buffer overflow; dropping frame")
            reset()
            return emptyList()
        }

        if (!isFinal) {
            expectNextFragment = fragmentId + 1
            return emptyList()
        }

        val frame = assembling.toByteArray()
        reset()
        return if (frame.isEmpty()) emptyList() else listOf(frame)
    }

    override fun encode(ax25: ByteArray, maxWriteBytes: Int): List<ByteArray> {
        if (ax25.isEmpty()) return emptyList()

        // Room left for fragment data once the header and the flags byte are in place.
        val perFragment = (maxWriteBytes - HEADER_BYTES - 1).coerceAtLeast(MIN_FRAGMENT_BYTES)

        val out = ArrayList<ByteArray>()
        var offset = 0
        var fragmentId = 0
        while (offset < ax25.size) {
            val end = minOf(offset + perFragment, ax25.size)
            val isFinal = end == ax25.size

            val body = ByteArray(1 + (end - offset))
            var flags = fragmentId and 0x3F
            if (isFinal) flags = flags or 0x80
            body[0] = flags.toByte()
            ax25.copyInto(body, 1, offset, end)

            out.add(message(BenshiProtocol.CMD_HT_SEND_DATA, body))
            offset = end
            fragmentId++
        }
        return out
    }

    private fun message(command: Int, body: ByteArray): ByteArray {
        val msg = ByteArray(HEADER_BYTES + body.size)
        writeU16(msg, 0, BenshiProtocol.GROUP_BASIC)
        writeU16(msg, 2, command)
        body.copyInto(msg, HEADER_BYTES)
        return msg
    }

    private fun readU16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, at: Int, value: Int) {
        b[at] = ((value ushr 8) and 0xFF).toByte()
        b[at + 1] = (value and 0xFF).toByte()
    }

    companion object {
        private const val TAG = "BenshiCodec"
        private const val HEADER_BYTES = 4

        /** Floor for a fragment, so a tiny MTU cannot produce zero-length fragments. */
        private const val MIN_FRAGMENT_BYTES = 8
    }
}
