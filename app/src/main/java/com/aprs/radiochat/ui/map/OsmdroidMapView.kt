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

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aprs.radiochat.data.aprs.AprsMessageCodec
import com.aprs.radiochat.data.aprs.AprsSymbolIcons
import com.aprs.radiochat.data.location.TrackPoint
import com.aprs.radiochat.data.model.MapStation
import com.aprs.radiochat.data.model.OwnPosition
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import android.graphics.Color as AndroidColor

/**
 * OSM map with APRS icons and the track of sent beacons (packet coordinates).
 */
@Composable
fun OsmdroidMapView(
    stations: List<MapStation>,
    ownPosition: OwnPosition?,
    track: List<TrackPoint> = emptyList(),
    modifier: Modifier = Modifier,
    defaultCenter: GeoPoint = GeoPoint(18.4861, -69.9312),
    defaultZoom: Double = 5.0
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val overlayState = remember { OverlaySyncState() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setMultiTouchControls(true)
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setDestroyMode(false)
                controller.setZoom(defaultZoom)
                controller.setCenter(defaultCenter)
                overlayState.mapView = this
            }
        },
        update = { view ->
            overlayState.mapView = view
            val stationsChanged = stations !== overlayState.stations
            val trackChanged = track !== overlayState.track
            val ownChanged = ownPosition !== overlayState.own
            if (!stationsChanged && !trackChanged && !ownChanged) return@AndroidView
            view.post {
                if (overlayState.mapView !== view) return@post
                val ctx = view.context
                if (stationsChanged) {
                    syncStationMarkers(view, ctx, overlayState, stations, ownPosition?.callsign)
                    overlayState.stations = stations
                }
                if (trackChanged) {
                    syncRouteOverlay(view, overlayState, track)
                    overlayState.track = track
                }
                if (ownChanged || stationsChanged) {
                    updateOwnMarker(view, ctx, ownPosition)
                    overlayState.own = ownPosition
                }
                maybeFollowCamera(view, overlayState, ownPosition, stations, track)
                view.invalidate()
            }
        }
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> overlayState.mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> overlayState.mapView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            overlayState.mapView?.onPause()
            overlayState.mapView?.onDetach()
            overlayState.mapView = null
        }
    }
}

private const val OWN_MARKER_ID = "own-station"

private class OverlaySyncState {
    var mapView: MapView? = null
    var stations: List<MapStation> = emptyList()
    var track: List<TrackPoint> = emptyList()
    var own: OwnPosition? = null
    var hasCenteredOnOwn = false
    var hasFittedRoute = false
    var followedStamp = 0L
    val stationMarkers = HashMap<String, Marker>()
    var routeOverlay: BeaconRouteOverlay? = null
}

private fun syncStationMarkers(
    view: MapView,
    ctx: android.content.Context,
    state: OverlaySyncState,
    stations: List<MapStation>,
    myCall: String?
) {
    val wanted = HashSet<String>(stations.size)
    stations.forEach { station ->
        val isMe = myCall != null &&
            AprsMessageCodec.sameCallsignWithSsid(station.callsign, myCall)
        if (isMe) return@forEach
        wanted.add(station.callsign)
        val existing = state.stationMarkers[station.callsign]
        if (existing != null) {
            existing.position = GeoPoint(station.latitude, station.longitude)
            existing.title = station.callsign
            existing.snippet = buildSnippet(station.symbol, station.comment)
        } else {
            val marker = stationMarker(view, ctx, station)
            state.stationMarkers[station.callsign] = marker
            view.overlays.add(marker)
        }
    }
    val stale = state.stationMarkers.keys.filter { it !in wanted }
    stale.forEach { call ->
        state.stationMarkers.remove(call)?.let { view.overlays.remove(it) }
    }
}

private fun syncRouteOverlay(
    view: MapView,
    state: OverlaySyncState,
    track: List<TrackPoint>
) {
    state.routeOverlay?.let { view.overlays.remove(it) }
    state.routeOverlay = null
    if (track.isEmpty()) {
        state.hasFittedRoute = false
        state.followedStamp = 0L
        return
    }
    val overlay = BeaconRouteOverlay(track.map { GeoPoint(it.latitude, it.longitude) })
    state.routeOverlay = overlay
    view.overlays.add(0, overlay)
}

private fun maybeFollowCamera(
    view: MapView,
    state: OverlaySyncState,
    ownPosition: OwnPosition?,
    stations: List<MapStation>,
    track: List<TrackPoint>
) {
    val last = track.lastOrNull()
    val stamp = last?.timestamp ?: 0L
    if (last != null && stamp != state.followedStamp) {
        val lastPoint = GeoPoint(last.latitude, last.longitude)
        when {
            track.size >= 2 && !state.hasFittedRoute -> {
                view.zoomToBoundingBox(
                    BoundingBox.fromGeoPoints(
                        track.map { GeoPoint(it.latitude, it.longitude) }
                    ),
                    false,
                    120
                )
                state.hasFittedRoute = true
                state.hasCenteredOnOwn = true
            }
            track.size == 1 && !state.hasCenteredOnOwn -> {
                view.controller.setZoom(14.0)
                view.controller.setCenter(lastPoint)
                state.hasCenteredOnOwn = true
            }
            state.hasCenteredOnOwn -> {
                view.controller.setCenter(lastPoint)
            }
        }
        state.followedStamp = stamp
    } else if (!state.hasCenteredOnOwn) {
        ownPosition?.let { own ->
            view.controller.setZoom(12.0)
            view.controller.setCenter(GeoPoint(own.latitude, own.longitude))
            state.hasCenteredOnOwn = true
        } ?: stations.firstOrNull()?.let { first ->
            view.controller.setZoom(8.0)
            view.controller.setCenter(GeoPoint(first.latitude, first.longitude))
            state.hasCenteredOnOwn = true
        }
    }
}

private fun stationMarker(view: MapView, ctx: android.content.Context, station: MapStation): Marker =
    Marker(view).apply {
        id = station.callsign
        position = GeoPoint(station.latitude, station.longitude)
        title = station.callsign
        snippet = buildSnippet(station.symbol, station.comment)
        icon = AprsSymbolIcons.drawable(ctx, station.symbol)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindowAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
    }

private fun updateOwnMarker(view: MapView, ctx: android.content.Context, ownPosition: OwnPosition?) {
    val existing = view.overlays.filterIsInstance<Marker>().find { it.id == OWN_MARKER_ID }
    if (ownPosition == null) {
        existing?.let { view.overlays.remove(it) }
        return
    }
    val geo = GeoPoint(ownPosition.latitude, ownPosition.longitude)
    val snippet = buildSnippet(
        ownPosition.symbol,
        listOfNotNull(
            ownPosition.comment.ifBlank { null },
            when {
                ownPosition.fromPhoneGps -> "GPS"
                ownPosition.fromRadioBeacon -> "beacon radio"
                else -> null
            }
        ).joinToString(" · ")
    )
    if (existing != null) {
        existing.position = geo
        existing.title = "You · ${ownPosition.callsign}"
        existing.snippet = snippet
        return
    }
    view.overlays.add(
        Marker(view).apply {
            id = OWN_MARKER_ID
            position = geo
            title = "You · ${ownPosition.callsign}"
            this.snippet = snippet
            icon = AprsSymbolIcons.drawable(ctx, ownPosition.symbol.ifBlank { "/$" })
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindowAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
        }
    )
}

private fun buildSnippet(symbol: String, comment: String): String =
    listOf(symbol, comment).filter { it.isNotBlank() }.joinToString(" · ")

/** Polyline + decimated points (not one circle per beacon: that freezes the draw). */
private class BeaconRouteOverlay(
    private val geoPoints: List<GeoPoint>
) : Overlay() {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#1B5E40")
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#2E7D4F")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun draw(c: Canvas, p: Projection) {
        if (geoPoints.isEmpty()) return
        val pt = android.graphics.Point()
        if (geoPoints.size >= 2) {
            val path = Path()
            geoPoints.forEachIndexed { i, g ->
                p.toPixels(g, pt)
                if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat())
                else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
            }
            c.drawPath(path, linePaint)
        }
        val last = geoPoints.lastIndex
        val step = if (geoPoints.size > 12) geoPoints.size / 12 else 1
        var i = 0
        while (i < last) {
            p.toPixels(geoPoints[i], pt)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7f, fillPaint)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7f, strokePaint)
            i += step
        }
        p.toPixels(geoPoints[last], pt)
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 11f, fillPaint)
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 11f, strokePaint)
    }
}
