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
 * APRS messaging format (addressed text).
 *
 * Strict send:
 *   ':' + addressee (exactly 9 chars, space padded) + ':' + text
 * Example: ":EA1ABC   :Hello"
 *
 * Optionally at the end: '{' + msgNo  (ack request)
 * ACK: ":ADDRESSEE:ackMSGNO"
 */
object AprsMessageCodec {

    /** APRS ACK/REJ: `ack01` / `rej12`, not phrases that merely start with "ack". */
    private val ackRejRegex = Regex("""^(ack|rej)([A-Za-z0-9]{0,5})$""", RegexOption.IGNORE_CASE)

    data class AckRej(
        val rejected: Boolean,
        val messageId: String
    )

    /**
     * Builds the Info Field of an APRS message.
     * @param addressee destination callsign (SSID not required; padded to 9 chars)
     * @param text message body (max. ~67 chars recommended on RF)
     * @param messageId optional, to request an ACK (`{01`)
     */
    fun build(addressee: String, text: String, messageId: String? = null): String {
        val padded = padAddressee(addressee)
        val body = text.take(67)
        val ack = messageId?.let { "{$it" }.orEmpty()
        return ":$padded:$body$ack"
    }

    /** ACK confirmation Info Field: `:CALL     :ack01` */
    fun buildAck(addressee: String, messageId: String): String {
        val id = messageId.trim().take(5)
        return ":${padAddressee(addressee)}:ack$id"
    }

    fun isAckOrRej(text: String): Boolean =
        extractAck(text) != null

    fun parseAckOrRej(text: String): AckRej? {
        val t = text.trim()
        val m = ackRejRegex.matchEntire(t) ?: return null
        return AckRej(
            rejected = m.groupValues[1].equals("rej", ignoreCase = true),
            messageId = m.groupValues[2]
        )
    }

    /**
     * ACK/REJ in the usual RF/KISS shapes:
     * `ack01`, `:ack01`, `:CALL     :ack01`, `ack01{`, `ack` + `{01`.
     */
    fun extractAck(text: String): AckRej? {
        val cleaned = text.trimEnd('\u0000', '\r', '\n').trim()
        if (cleaned.isEmpty()) return null
        parseAckOrRej(stripAckTrailer(cleaned))?.let { return it }
        parseAckOrRej(stripAckTrailer(cleaned.removePrefix(":").trim()))?.let { return it }
        val parsed = parse(cleaned) ?: return null
        val fromBody = parseAckOrRej(stripAckTrailer(parsed.text)) ?: return null
        val id = fromBody.messageId.ifBlank { parsed.messageId?.trim().orEmpty() }
        return fromBody.copy(messageId = id)
    }

    private fun stripAckTrailer(text: String): String =
        text.trim().trimEnd('{', '}', '*', ' ')

    /**
     * ACK addressed to us: same SSID, or to the callsign without SSID (`:HI3LAG :ack01`).
     * HI3LAG-3 ≠ HI3LAG-7.
     */
    fun isAckAddressedTo(addressee: String, myCall: String): Boolean {
        val dest = addressee.removeSuffix("*").trim()
        if (dest.isEmpty() || myCall.isBlank()) return false
        if (sameCallsignWithSsid(dest, myCall)) return true
        val destCanon = canonicalCall(dest)
        return !destCanon.contains('-') &&
            normalizeCall(destCanon) == normalizeCall(myCall)
    }

    fun isGenericAprsDestination(dest: String): Boolean {
        val base = dest.uppercase().trim().substringBefore('-')
        if (base.startsWith("AP") && base.length >= 3) return true
        if (base.startsWith("WIDE") || base.startsWith("RELAY") || base.startsWith("TRACE")) {
            return true
        }
        return base in GENERIC_DESTS
    }

    fun isLikelyDigipeater(call: String): Boolean {
        val base = normalizeCall(call)
        if (base.isEmpty()) return true
        return isGenericAprsDestination(call) ||
            base.startsWith("WIDE") ||
            base.startsWith("RELAY") ||
            base.startsWith("TRACE")
    }

    /** Id to match on: body `ack01`, or `ack` + `{01` in the info field. */
    fun ackMessageId(text: String, parsedMessageId: String?): String? {
        val ack = extractAck(text) ?: return null
        return ack.messageId.ifBlank { parsedMessageId?.trim().orEmpty() }
    }

    /** `01` and `1` are the same APRS msgid. */
    fun sameMessageId(a: String, b: String): Boolean {
        fun norm(raw: String): String {
            val t = raw.trim().uppercase()
            if (t.isEmpty()) return ""
            return if (t.all { it.isDigit() }) t.trimStart('0').ifEmpty { "0" } else t
        }
        return norm(a) == norm(b)
    }

    /**
     * APRS message Info Field: `:ADDRESSEE:text{msgid`
     *
     * The spec asks for a 9-character space-padded addressee; DireWolf and many
     * TNCs send it unpadded (`:HI3LAG-7:Hello`). Both are accepted.
     */
    fun parse(infoField: String): ParsedMessage? {
        val cleaned = infoField.trimStart()
            .trimEnd('\u0000', '\r', '\n')
            .takeWhile { it.code in 32..126 }
        if (cleaned.isEmpty() || cleaned[0] != ':') return null
        val rest = cleaned.substring(1)
        val colon = rest.indexOf(':')
        if (colon < 1 || colon > 9) return null
        val addressee = rest.substring(0, colon).trim()
        if (addressee.isEmpty()) return null
        var body = rest.substring(colon + 1)
        var msgId: String? = null
        val brace = body.lastIndexOf('{')
        if (brace >= 0) {
            val id = body.substring(brace + 1).trim()
            if (id.isNotEmpty() && id.length <= 5 && id.all { it.isLetterOrDigit() }) {
                msgId = id
                body = body.substring(0, brace)
            }
        }
        return ParsedMessage(addressee = addressee, text = body, messageId = msgId)
    }

    /**
     * true when the message is addressed to [myCall] with the same SSID.
     * HI3LAG-7 ≠ HI3LAG-9; HI3LAG-0 == HI3LAG.
     */
    fun isAddressedTo(addressee: String, myCall: String): Boolean {
        val dest = addressee.removeSuffix("*").trim()
        val mine = myCall.trim()
        if (dest.isEmpty() || mine.isEmpty()) return false
        return sameCallsignWithSsid(dest, mine)
    }

    /** Pads/truncates the addressee to exactly 9 characters. */
    fun padAddressee(callsign: String): String {
        return callsign.uppercase().take(9).padEnd(9, ' ')
    }

    fun normalizeCall(call: String): String =
        call.uppercase().substringBefore('-').trim()

    /**
     * Canonical callsign including SSID (SSID 0 is omitted, as in AX.25).
     * HI3LAG-7 ≠ HI3LAG-9; HI3LAG-0 == HI3LAG.
     */
    fun canonicalCall(call: String): String {
        val raw = call.uppercase().trim().removeSuffix("*").trim()
        val dash = raw.lastIndexOf('-')
        if (dash > 0 && dash < raw.lastIndex) {
            val ssid = raw.substring(dash + 1).toIntOrNull()
            if (ssid != null && ssid == 0) return raw.substring(0, dash)
            if (ssid != null && ssid in 1..15) return raw
        }
        return raw
    }

    fun sameCallsignWithSsid(a: String, b: String): Boolean =
        canonicalCall(a).equals(canonicalCall(b), ignoreCase = true)

    data class ParsedMessage(
        val addressee: String,
        val text: String,
        val messageId: String?
    )

    private val GENERIC_DESTS = setOf(
        "BEACON", "ID", "CQ", "QST", "MAIL", "ALL", "SP", "TEST", "GPS",
        "APRS", "SKY", "AIR"
    )
}
