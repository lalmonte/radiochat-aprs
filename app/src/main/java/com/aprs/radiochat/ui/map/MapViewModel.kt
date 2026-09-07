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
package com.aprs.radiochat.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aprs.radiochat.data.location.BeaconTrackStore
import com.aprs.radiochat.data.location.TrackPoint
import com.aprs.radiochat.data.model.MapStation
import com.aprs.radiochat.data.model.OwnPosition
import com.aprs.radiochat.data.repository.StationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

class MapViewModel(
    stationRepository: StationRepository,
    private val beaconTrack: BeaconTrackStore
) : ViewModel() {

    @OptIn(FlowPreview::class)
    val stations: StateFlow<List<MapStation>> = stationRepository.stations
        .debounce(1_500)
        .map { stations ->
            stations.values
                .sortedBy { s -> s.callsign }
                .take(MAX_MAP_STATIONS)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope + Dispatchers.Default,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val track: StateFlow<List<TrackPoint>> = beaconTrack.points
        .map { points ->
            if (points.size <= MAX_MAP_TRACK_POINTS) points
            else downsample(points, MAX_MAP_TRACK_POINTS)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope + Dispatchers.Default,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val ownPosition: StateFlow<OwnPosition?> = stationRepository.ownPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val trackDistanceKm: StateFlow<Double> = beaconTrack.points
        .map { TrackPoint.distanceKm(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope + Dispatchers.Default,
            SharingStarted.WhileSubscribed(5_000),
            0.0
        )

    fun clearTrack() = beaconTrack.clear()

    companion object {
        private const val MAX_MAP_STATIONS = 80
        private const val MAX_MAP_TRACK_POINTS = 80

        private fun downsample(points: List<TrackPoint>, max: Int): List<TrackPoint> {
            if (points.size <= max) return points
            val step = points.size.toDouble() / (max - 1)
            val out = ArrayList<TrackPoint>(max)
            for (n in 0 until max - 1) {
                out.add(points[(n * step).toInt().coerceIn(0, points.lastIndex)])
            }
            out.add(points.last())
            return out
        }
    }

    class Factory(
        private val stationRepository: StationRepository,
        private val beaconTrack: BeaconTrackStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MapViewModel(stationRepository, beaconTrack) as T
        }
    }
}
