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
 * Mic-E decoder (position in the AX.25 destination + info).
 * The usual format of RT-950 Pro beacons.
 */
object MiceParser {

    data class Result(
        val latitude: Double,
        val longitude: Double,
        val symbol: String,
        val comment: String,
        val speedKnots: Double? = null,
        val courseDeg: Int? = null
    )

    fun parse(destination: String, info: String): Result? {
        if (info.length < 9) return null
        val type = info[0]
        if (type != '`' && type != '\'' && type != '>' && type != ']' && type != '"') {
            return null
        }

        val dest = destination.uppercase().substringBefore('-').padEnd(6, ' ').take(6)
        val digits = IntArray(6)
        val flags = BooleanArray(6)
        for (i in 0 until 6) {
            val decoded = decodeDestChar(dest[i]) ?: return null
            digits[i] = decoded.first
            flags[i] = decoded.second
        }

        val latDeg = digits[0] * 10 + digits[1]
        val latMin = digits[2] * 10 + digits[3] + digits[4] / 10.0 + digits[5] / 100.0
        var lat = latDeg + latMin / 60.0
        if (!flags[3]) lat = -lat // flag en 4º char = Norte

        var lonDeg = info[1].code - 28
        if (flags[4]) lonDeg += 100 // offset +100
        if (lonDeg in 180..189) lonDeg -= 80
        if (lonDeg in 190..199) lonDeg -= 190

        var lonMin = info[2].code - 28
        if (lonMin >= 60) lonMin -= 60
        val lonHun = info[3].code - 28
        var lon = lonDeg + (lonMin + lonHun / 100.0) / 60.0
        if (flags[5]) lon = -lon // Oeste

        val sp = info[4].code - 28
        val dc = info[5].code - 28
        val se = info[6].code - 28
        val speedKt = (sp * 10 + dc / 10).toDouble()
        var course = (dc % 10) * 100 + se
        if (course in 400..499) course -= 400

        val symbolCode = info[7]
        val symbolTable = info[8]
        val comment = if (info.length > 9) info.substring(9).trim() else ""

        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        return Result(
            latitude = lat,
            longitude = lon,
            symbol = "$symbolTable$symbolCode",
            comment = comment,
            speedKnots = speedKt,
            courseDeg = course.coerceIn(0, 360)
        )
    }

    /** digit 0-9 + “P-Z set” flag (N / +100 / W depending on the index). */
    private fun decodeDestChar(c: Char): Pair<Int, Boolean>? = when (c) {
        in '0'..'9' -> (c - '0') to false
        in 'A'..'J' -> (c - 'A') to false
        'K', 'L' -> 0 to false
        in 'P'..'Y' -> (c - 'P') to true
        'Z' -> 0 to true
        ' ' -> 0 to false
        else -> null
    }
}
