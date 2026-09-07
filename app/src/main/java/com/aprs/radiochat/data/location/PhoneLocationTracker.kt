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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PhoneLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val altitudeM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val updatedAt: Long
)

/**
 * Phone GPS / network location via [LocationManager] (no Play Services).
 * Keeps running with the foreground service (screen off / another app in front).
 */
class PhoneLocationTracker(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val gpsThread = HandlerThread("aprs-gps").apply { start() }

    private val _fix = MutableStateFlow<PhoneLocationFix?>(null)
    val fix: StateFlow<PhoneLocationFix?> = _fix.asStateFlow()

    @Volatile
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            accept(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (started) return
        if (!hasPermission()) {
            Log.w(TAG, "No location permission")
            return
        }
        started = true
        seedLastKnown()
        val looper = gpsThread.looper
        if (looper == null) {
            Log.e(TAG, "GPS thread has no looper")
            started = false
            return
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DIST_M,
                    listener,
                    looper
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS * 2,
                    MIN_DIST_M * 2,
                    listener,
                    looper
                )
            }
            Log.i(TAG, "GPS tracking started")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start GPS: ${e.message}", e)
            started = false
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager.removeUpdates(listener) }
        Log.i(TAG, "GPS tracking stopped")
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnown() {
        if (!hasPermission()) return
        val candidates = listOfNotNull(
            runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
                .getOrNull(),
            runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
                .getOrNull()
        )
        candidates.maxByOrNull { it.time }?.let { accept(it) }
    }

    private fun accept(location: Location) {
        val current = _fix.value
        if (current != null &&
            location.time < current.updatedAt &&
            location.accuracy >= current.accuracyM
        ) {
            return
        }
        _fix.value = PhoneLocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = location.accuracy,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            updatedAt = location.time.coerceAtLeast(System.currentTimeMillis() - 60_000L)
        )
    }

    companion object {
        private const val TAG = "PhoneGps"
        private const val MIN_TIME_MS = 5_000L
        private const val MIN_DIST_M = 15f
    }
}
