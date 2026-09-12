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

import com.aprs.radiochat.data.kiss.KissCodec
import java.util.UUID

/**
 * Radtel RT-950 Pro — plain KISS over a Nordic-style UART service.
 * Enable **KISS(BLE)** in the radio's APRS menu, with TX and RX on.
 *
 * - **FFE1** → notify (radio → app)
 * - **FF31** → write (app → radio, KISS frames)
 *
 * Documented by the community in [mecta02/aprs](https://github.com/mecta02/aprs).
 *
 * The FF31 unlock sequence belongs to CPS programming mode (serial) and must NOT be
 * sent in BLE KISS mode.
 *
 * These values and the KISS framing are exactly what shipped in 1.0.0. Nothing here
 * should change when another radio is added.
 */
object RadtelKissProfile : BleRadioProfile {

    override val model = RadioModel.RADTEL_RT950_PRO

    override val serviceUuid: UUID =
        UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")

    override val notifyCharUuid: UUID =
        UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")

    /**
     * FF31 is the real KISS TX channel. FFE2 and FFE1 are fallbacks seen on some
     * firmwares; FFE1 doubles as the notify characteristic there.
     */
    override val writeCharUuids = listOf(
        BleRadioProfile.WriteCandidate(
            UUID.fromString("0000FF31-0000-1000-8000-00805F9B34FB"), "FF31"
        ),
        BleRadioProfile.WriteCandidate(
            UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB"), "FFE2"
        ),
        BleRadioProfile.WriteCandidate(
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"), "FFE1"
        )
    )

    override val deviceNameHints = listOf(
        "RT-950", "RT950", "Radtel", "950 Pro", "950PRO", "BT-RT950", "RT_950"
    )

    override val fallbackDeviceName = "RT-950 Pro"

    override val usesIndications = false

    /** KISS is a byte stream: splitting it across GATT writes is safe and expected. */
    override val chunkWritesToMtu = true

    /**
     * Receive only. The RT-950's firmware does not reliably key PTT from phone-side KISS:
     * writes are accepted on FF31 and nothing goes out on the air. Use DireWolf or
     * APRS-IS to transmit.
     */
    override val supportsTx = false

    override fun newCodec(): RadioLinkCodec = KissLinkCodec()
}

/**
 * Plain TAPR KISS: one data frame per AX.25 payload, decoded incrementally because a
 * frame can straddle several GATT notifications.
 */
class KissLinkCodec : RadioLinkCodec {

    private val decoder = KissCodec.Decoder()

    override fun reset() = decoder.reset()

    override fun decode(chunk: ByteArray): List<ByteArray> =
        decoder.feed(chunk).filter { it.isNotEmpty() }

    // A single KISS data frame (cmd 0x00). TXDELAY is not reliably implemented in the
    // RT-950's internal TNC, so it is not prepended. The manager splits the stream to
    // the MTU, exactly as it always has.
    override fun encode(ax25: ByteArray, maxWriteBytes: Int): List<ByteArray> =
        listOf(KissCodec.encode(ax25))
}
