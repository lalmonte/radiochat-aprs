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

import com.aprs.radiochat.data.aprsis.Tnc2Codec
import com.aprs.radiochat.data.model.AprsPacket

/**
 * Orchestrates AX.25 + APRS codecs to classify each KISS payload.
 */
class AprsPacketParser(
    private val myCallsign: () -> String
) {
    /** Digi path learned from RX frames (used on TX). */
    var learnedDigipeaters: List<String> = emptyList()
        private set

    fun parseKissPayload(payload: ByteArray): AprsPacket? {
        classify(payload)?.let { packet ->
            if (packet !is AprsPacket.Other) return packet
        }
        // Some TNCs append the FCS (2 CRC bytes); retry without them.
        if (payload.size > 18) {
            classify(payload.copyOf(payload.size - 2))?.let { packet ->
                if (packet !is AprsPacket.Other) return packet
            }
        }
        return classify(payload)
    }

    private fun classify(payload: ByteArray): AprsPacket? {
        val ax25 = Ax25Frame.parse(payload) ?: return null
        if (ax25.digipeaters.isNotEmpty()) {
            learnedDigipeaters = ax25.digipeaters
        }
        val info = ax25.infoText
        if (info.isEmpty()) return null

        val inner = Tnc2Codec.effective(ax25.source, ax25.destination, info)
        val source = inner.source
        val dest = inner.destination
        val payload = inner.info

        AprsMessageCodec.parse(payload)?.let { msg ->
            return AprsPacket.TextMessage(
                sourceCall = source,
                destCall = dest,
                rawInfo = payload,
                addressee = msg.addressee,
                messageText = msg.text,
                messageId = msg.messageId
            )
        }

        // Bare ACK/REJ over KISS (`ack01` to the AX.25 dest, without `:CALL:`).
        AprsMessageCodec.extractAck(payload)?.let { ack ->
            return AprsPacket.TextMessage(
                sourceCall = source,
                destCall = dest,
                rawInfo = payload,
                addressee = dest,
                messageText = payload.trim().trimStart(':').trim(),
                messageId = ack.messageId.ifBlank { null }
            )
        }

        AprsPositionParser.parse(payload)?.let { pos ->
            return AprsPacket.Position(
                sourceCall = source,
                destCall = dest,
                rawInfo = payload,
                latitude = pos.latitude,
                longitude = pos.longitude,
                symbol = pos.symbol,
                comment = pos.comment
            )
        }

        // Mic-E: latitude in the destination, the rest in info (typical RT-950)
        MiceParser.parse(dest, payload)?.let { pos ->
            return AprsPacket.Position(
                sourceCall = source,
                destCall = dest,
                rawInfo = payload,
                latitude = pos.latitude,
                longitude = pos.longitude,
                symbol = pos.symbol,
                comment = pos.comment
            )
        }

        // UI addressed to the callsign (AX.25 dest = me), without the APRS message ':'.
        if (isDirectedToStation(dest, myCallsign())) {
            return AprsPacket.TextMessage(
                sourceCall = source,
                destCall = dest,
                rawInfo = payload,
                addressee = dest,
                messageText = payload.trim(),
                messageId = null
            )
        }

        return AprsPacket.Other(
            sourceCall = ax25.source,
            destCall = ax25.destination,
            rawInfo = info
        )
    }

    private fun isDirectedToStation(ax25Dest: String, myCall: String): Boolean {
        if (myCall.isBlank() || myCall.equals("N0CALL", ignoreCase = true)) return false
        if (isGenericAprsDestination(ax25Dest)) return false
        return AprsMessageCodec.isAddressedTo(ax25Dest, myCall)
    }

    private fun isGenericAprsDestination(dest: String): Boolean {
        val u = dest.uppercase().trim()
        val base = u.substringBefore('-')
        if (base.startsWith("AP") && base.length >= 3) return true
        if (base.startsWith("WIDE") || base.startsWith("RELAY") || base.startsWith("TRACE")) {
            return true
        }
        return base in GENERIC_DESTS
    }

    /**
     * Builds the AX.25 payload for an outgoing message.
     * An empty [digipeaters] = typical Radtel BLE (the radio adds the digis).
     * Para DireWolf: WIDE1-1,WIDE2-1.
     */
    fun buildOutgoingMessage(
        toCallsign: String,
        text: String,
        messageId: String? = null,
        digipeaters: List<String> = emptyList(),
        includeFcs: Boolean = false
    ): ByteArray {
        val myCall = myCallsign().ifBlank { "N0CALL" }
        val info = AprsMessageCodec.build(toCallsign, text, messageId)
            .toByteArray(Charsets.ISO_8859_1)
        var frame = Ax25Frame.buildUiFrame(
            destination = "APRS",
            source = myCall,
            info = info,
            digipeaters = digipeaters
        )
        if (includeFcs) {
            frame = Ax25Fcs.appendFcs(frame)
        }
        return frame
    }

    fun isMessageForMe(packet: AprsPacket.TextMessage): Boolean {
        return AprsMessageCodec.isAddressedTo(packet.addressee, myCallsign())
    }

    companion object {
        private val GENERIC_DESTS = setOf(
            "BEACON", "ID", "CQ", "QST", "MAIL", "ALL", "SP", "TEST", "GPS",
            "APRS", "SKY", "AIR"
        )
    }
}
