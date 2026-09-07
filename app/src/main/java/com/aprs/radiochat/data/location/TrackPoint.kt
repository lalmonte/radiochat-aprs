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
package com.aprs.radiochat.data.location

import com.aprs.radiochat.data.aprs.GeoUtils

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracyM: Float
) {
    companion object {
        fun distanceKm(points: List<TrackPoint>): Double {
            if (points.size < 2) return 0.0
            var sum = 0.0
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                sum += GeoUtils.distanceKm(a.latitude, a.longitude, b.latitude, b.longitude)
            }
            return sum
        }
    }
}
