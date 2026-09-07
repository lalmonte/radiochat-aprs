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

import com.aprs.radiochat.data.aprs.AprsPositionParser
import com.aprs.radiochat.data.aprs.GeoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Map track: one point per **sent** beacon (coordinates from the APRS packet).
 * Deduplicates the RF echo of the same TX.
 */
class BeaconTrackStore(private val file: File) {

    private val _points = MutableStateFlow(load())
    val points: StateFlow<List<TrackPoint>> = _points.asStateFlow()

    @Synchronized
    fun append(latitude: Double, longitude: Double, timestamp: Long = System.currentTimeMillis()) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        if (latitude == 0.0 && longitude == 0.0) return

        val last = _points.value.lastOrNull()
        if (last != null) {
            val meters = GeoUtils.distanceKm(
                last.latitude, last.longitude, latitude, longitude
            ) * 1000.0
            if (meters < DEDUP_METERS) return
        }

        val point = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracyM = 0f
        )
        val next = (_points.value + point).let { list ->
            if (list.size > MAX_POINTS) list.takeLast(MAX_POINTS) else list
        }
        _points.value = next
        persist(next)
    }

    /** Uses the coordinates exactly as they appear in the sent APRS text. */
    fun appendFromAprsInfo(info: String, fallbackLat: Double, fallbackLon: Double) {
        val sent = AprsPositionParser.parse(info)
        append(sent?.latitude ?: fallbackLat, sent?.longitude ?: fallbackLon)
    }

    @Synchronized
    fun clear() {
        _points.value = emptyList()
        persist(emptyList())
    }

    private fun load(): List<TrackPoint> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .mapNotNull { line ->
                    val p = line.trim().split(',')
                    if (p.size < 3) return@mapNotNull null
                    TrackPoint(
                        latitude = p[0].toDouble(),
                        longitude = p[1].toDouble(),
                        timestamp = p[2].toLong(),
                        accuracyM = 0f
                    )
                }
        }.getOrElse { emptyList() }
    }

    private fun persist(points: List<TrackPoint>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                points.joinToString("\n") { "${it.latitude},${it.longitude},${it.timestamp}" }
            )
        }
    }

    companion object {
        private const val MAX_POINTS = 2_000
        private const val DEDUP_METERS = 25.0
    }
}
