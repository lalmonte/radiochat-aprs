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

import java.util.UUID

/**
 * Everything that differs between one BLE radio and another: which GATT attributes to
 * look for, how to recognise the device while scanning, and how AX.25 payloads are
 * framed on the wire.
 *
 * `BleUartManager` owns the GATT state machine and knows nothing about any particular
 * radio, so adding a model cannot change the behaviour of an existing one.
 */
interface BleRadioProfile {

    val model: RadioModel

    /** Advertised service used to recognise the radio during a scan. */
    val serviceUuid: UUID

    /** Characteristic the radio writes to (radio → app). */
    val notifyCharUuid: UUID

    /**
     * Candidate write characteristics (app → radio), most preferred first. The first one
     * the connected radio actually exposes as writable is used.
     */
    val writeCharUuids: List<WriteCandidate>

    /** Substrings matched against the advertised device name. */
    val deviceNameHints: List<String>

    /** Shown while scanning when the radio advertises no name. */
    val fallbackDeviceName: String

    /**
     * True when the radio's RX characteristic is an *indication* rather than a
     * notification. The CCCD value differs, and enabling the wrong one yields a
     * connection that looks healthy but never delivers a single packet.
     */
    val usesIndications: Boolean

    /**
     * True for byte-stream protocols such as KISS, where a write unit may be split
     * across GATT writes to fit the MTU. Message-oriented protocols must set this
     * false: splitting one of their messages in half corrupts it, so their codec sizes
     * its own fragments instead.
     */
    val chunkWritesToMtu: Boolean

    /**
     * True when the radio will actually key up and transmit a frame handed to it over
     * BLE. Receive-only radios must set this false: queuing a frame they silently drop
     * makes the app report a message as sent when it never went on the air.
     */
    val supportsTx: Boolean

    /** A fresh codec. Called on every connect, so decoders never carry stale state. */
    fun newCodec(): RadioLinkCodec

    data class WriteCandidate(val uuid: UUID, val label: String)

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * Translates between AX.25 payloads and whatever the radio expects on the wire.
 *
 * Implementations are used from a single coroutine at a time and are not thread safe;
 * the manager creates one per connection.
 */
interface RadioLinkCodec {

    /** Drops any partially received frame. Called on connect and disconnect. */
    fun reset()

    /**
     * Messages to send once notifications are subscribed and the link is usable.
     *
     * Some radios stay silent until told which events to report, so this is where a
     * protocol performs its handshake. Returning an empty list — the default — means the
     * radio starts talking on its own, as the Radtel does.
     */
    fun onLinkReady(maxWriteBytes: Int): List<ByteArray> = emptyList()

    /**
     * Feeds bytes arriving on the notify characteristic.
     * @return zero or more complete AX.25 payloads, KISS/transport framing removed.
     */
    fun decode(chunk: ByteArray): List<ByteArray>

    /**
     * Frames one AX.25 payload for transmission.
     *
     * @param maxWriteBytes what fits in a single GATT write on the negotiated MTU.
     *        Stream protocols may ignore it; message-oriented ones use it to size their
     *        fragments so no message ever needs splitting.
     * @return the write units to send in order.
     */
    fun encode(ax25: ByteArray, maxWriteBytes: Int): List<ByteArray>
}
