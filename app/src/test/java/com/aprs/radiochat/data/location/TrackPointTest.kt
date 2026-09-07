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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrackPointTest {

    @Test
    fun distanceKm_sumsSegments() {
        val a = TrackPoint(18.4861, -69.9312, 0L, 5f)
        val b = TrackPoint(18.4961, -69.9312, 1L, 5f)
        val km = TrackPoint.distanceKm(listOf(a, b))
        assertTrue(km in 1.0..1.3)
        assertEquals(0.0, TrackPoint.distanceKm(listOf(a)), 0.0)
    }
}

class BeaconTrackStoreTest {

    @Test
    fun append_keepsBeaconCoordinatesInOrder() {
        val store = BeaconTrackStore(tempFile())
        store.append(18.4861, -69.9312, 1_000L)
        store.append(18.4961, -69.9312, 120_000L)
        val pts = store.points.value
        assertEquals(2, pts.size)
        assertEquals(18.4861, pts[0].latitude, 1e-6)
        assertEquals(18.4961, pts[1].latitude, 1e-6)
        assertTrue(TrackPoint.distanceKm(pts) in 1.0..1.3)
    }

    @Test
    fun append_skipsStationaryRepeat() {
        val store = BeaconTrackStore(tempFile())
        store.append(18.48610, -69.93120, 1_000L)
        store.append(18.48611, -69.93121, 300_000L)
        assertEquals(1, store.points.value.size)
    }

    @Test
    fun appendFromAprsInfo_usesEncodedPacketCoords() {
        val gpsLat = 18.4861234
        val gpsLon = -69.9312345
        val info = AprsPositionParser.formatUncompressed(gpsLat, gpsLon)
        val encoded = AprsPositionParser.parse(info)!!
        val store = BeaconTrackStore(tempFile())
        store.appendFromAprsInfo(info, gpsLat, gpsLon)
        val point = store.points.value.single()
        assertEquals(encoded.latitude, point.latitude, 1e-9)
        assertEquals(encoded.longitude, point.longitude, 1e-9)
    }

    @Test
    fun persist_reloadsRoute() {
        val file = tempFile()
        BeaconTrackStore(file).append(18.4861, -69.9312, 1_000L)
        val reloaded = BeaconTrackStore(file).points.value
        assertEquals(1, reloaded.size)
        assertEquals(18.4861, reloaded[0].latitude, 1e-6)
        assertEquals(-69.9312, reloaded[0].longitude, 1e-6)
    }

    @Test
    fun clear_emptiesPersistedRoute() {
        val file = tempFile()
        val store = BeaconTrackStore(file)
        store.append(18.4861, -69.9312, 1_000L)
        store.clear()
        assertTrue(store.points.value.isEmpty())
        assertTrue(BeaconTrackStore(file).points.value.isEmpty())
    }

    private fun tempFile(): File =
        File.createTempFile("beacon_route", ".csv").apply { deleteOnExit() }
}
