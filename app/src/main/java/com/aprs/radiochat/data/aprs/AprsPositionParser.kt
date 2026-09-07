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
 * APRS position parser:
 * - Plain text: `!` / `=` / `/` / `@` + DDMM.mmN/S + symbol + DDDMM.mmE/W + symbol + comment
 * - Basic compressed (not Mic-E; only base91 `/` or `*` after an optional timestamp — compressed `!` format)
 *
 * Extracts: lat/lon as Double, symbol (table+code) and comment.
 */
object AprsPositionParser {

    // !4903.50N/07201.75W-Test comment
    private val uncompressedRegex = Regex(
        """^([!=\/@])(\d{4}\.\d{2})([NS])(.)(\d{5}\.\d{2})([EW])(.)(.*)"""
    )

    // Compressed format: ! / symbolTable + 4 lat + 4 lon + symbol + csT + comment
    // Typical example after '!': /YYYYXXXX$csTcomment  (base91)
    private val compressedRegex = Regex(
        """^([!=])([\\\/])(.{4})(.{4})(.)(.)(.)(.)(.*)"""
    )

    data class ParsedPosition(
        val latitude: Double,
        val longitude: Double,
        val symbol: String,
        val comment: String
    )

    fun parse(infoField: String): ParsedPosition? {
        val info = infoField.trimEnd('\r', '\n')
        if (info.isEmpty()) return null

        parseUncompressed(info)?.let { return it }
        parseCompressed(info)?.let { return it }

        // Some beacons carry a timestamp before the position (/HHMMSSz or @DDHHMMz)
        val withoutTs = stripLeadingTimestamp(info)
        if (withoutTs != info) {
            parseUncompressed(withoutTs)?.let { return it }
            parseCompressed(withoutTs)?.let { return it }
        }
        return null
    }

    private fun stripLeadingTimestamp(info: String): String {
        // /, @ + 7 timestamp chars + the rest of the position
        if (info.length > 8 && (info[0] == '/' || info[0] == '@')) {
            val ts = info.substring(1, 8)
            if (ts.all { it.isDigit() || it == 'z' || it == 'h' || it == '/' }) {
                // Re-inject the position type as '!' so the regex can be reused
                return "!" + info.substring(8)
            }
        }
        return info
    }

    private fun parseUncompressed(info: String): ParsedPosition? {
        val m = uncompressedRegex.matchEntire(info) ?: return null
        val lat = dmToDecimal(m.groupValues[2], m.groupValues[3])
        val lon = dmToDecimal(m.groupValues[5], m.groupValues[6])
        val table = m.groupValues[4]
        val code = m.groupValues[7]
        val comment = m.groupValues[8].trim()
        return ParsedPosition(lat, lon, table + code, comment)
    }

    private fun parseCompressed(info: String): ParsedPosition? {
        val m = compressedRegex.matchEntire(info) ?: return null
        val table = m.groupValues[2]
        val latEnc = m.groupValues[3]
        val lonEnc = m.groupValues[4]
        val code = m.groupValues[5]
        val comment = m.groupValues[9].trim()

        val lat = decodeBase91Lat(latEnc) ?: return null
        val lon = decodeBase91Lon(lonEnc) ?: return null
        return ParsedPosition(lat, lon, table + code, comment)
    }

    /** DDMM.mm + hemisphere → decimal degrees. */
    fun dmToDecimal(dm: String, hemisphere: String): Double {
        val dot = dm.indexOf('.')
        val degDigits = if (dm.length >= 8) 3 else 2 // lon=DDDMM.mm, lat=DDMM.mm
        val degrees = dm.substring(0, degDigits).toInt()
        val minutes = dm.substring(degDigits).toDouble()
        var dec = degrees + minutes / 60.0
        if (hemisphere.equals("S", true) || hemisphere.equals("W", true)) {
            dec = -dec
        }
        return dec
    }

    private fun decodeBase91Lat(y: String): Double? {
        if (y.length != 4 || y.any { it.code < 33 || it.code > 123 }) return null
        var v = 0
        for (c in y) v = v * 91 + (c.code - 33)
        return 90.0 - v / 380926.0
    }

    private fun decodeBase91Lon(x: String): Double? {
        if (x.length != 4 || x.any { it.code < 33 || it.code > 123 }) return null
        var v = 0
        for (c in x) v = v * 91 + (c.code - 33)
        return -180.0 + v / 190463.0
    }

    /** Encodes lat/lon as APRS plain text. `=` = messaging-capable station; `!` = not. */
    fun formatUncompressed(
        latitude: Double,
        longitude: Double,
        symbol: String = "/$",
        comment: String = "",
        messagingCapable: Boolean = true
    ): String {
        val table = symbol.getOrElse(0) { '/' }
        val code = symbol.getOrElse(1) { '$' }
        val lat = decimalToDm(latitude, isLat = true)
        val lon = decimalToDm(longitude, isLat = false)
        val type = if (messagingCapable) '=' else '!'
        return "$type$lat$table$lon$code${comment.take(43)}"
    }

    private fun decimalToDm(value: Double, isLat: Boolean): String {
        val hemi = when {
            isLat && value >= 0 -> 'N'
            isLat -> 'S'
            value >= 0 -> 'E'
            else -> 'W'
        }
        val abs = kotlin.math.abs(value)
        val deg = abs.toInt()
        val minutes = (abs - deg) * 60.0
        return if (isLat) {
            String.format(java.util.Locale.US, "%02d%05.2f%c", deg, minutes, hemi)
        } else {
            String.format(java.util.Locale.US, "%03d%05.2f%c", deg, minutes, hemi)
        }
    }
}
