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
package com.aprs.radiochat.data.repository

import com.aprs.radiochat.data.aprs.AprsMessageCodec
import com.aprs.radiochat.data.aprs.AprsPacketParser
import com.aprs.radiochat.data.aprs.AprsPositionParser
import com.aprs.radiochat.data.aprs.Ax25Frame
import com.aprs.radiochat.data.aprs.MiceParser
import com.aprs.radiochat.data.aprsis.AprsIsClient
import com.aprs.radiochat.data.aprsis.Tnc2Codec
import com.aprs.radiochat.data.kiss.KissFrameHub
import com.aprs.radiochat.data.kiss.KissTransport
import com.aprs.radiochat.data.model.AprsLogEntry
import com.aprs.radiochat.data.model.AprsPacket
import com.aprs.radiochat.data.model.LogKind
import com.aprs.radiochat.data.model.LogTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Live monitor of APRS packets (KISS BLE/TCP + APRS-IS).
 * Logs everything that reaches the socket/TNC. The range is only requested from
 * the IS server; nothing is dropped here, neither messages nor positions.
 */
class AprsLogRepository(
    private val kissHub: KissFrameHub,
    private val aprsIs: AprsIsClient,
    private val parser: AprsPacketParser,
    private val settings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    /** RF (BLE + DireWolf/KISS TCP): IS cannot evict these packets. */
    private val rfBuffer = ArrayDeque<AprsLogEntry>(MAX_RF + 1)
    private val isBuffer = ArrayDeque<AprsLogEntry>(MAX_IS + 1)
    private val symbolsByCall = ConcurrentHashMap<String, String>()
    @Volatile private var dirty = false

    private val _entries = MutableStateFlow<List<AprsLogEntry>>(emptyList())
    val entries: StateFlow<List<AprsLogEntry>> = _entries.asStateFlow()

    init {
        kissHub.onFrame = { frame ->
            appendFromKiss(frame.payload, frame.transport)
        }
        aprsIs.onIncomingLine = { line ->
            appendFromInternet(line)
        }
        // One snapshot per second: don't push 500 copies to the UI thread per packet.
        scope.launch {
            while (isActive) {
                delay(SNAPSHOT_MS)
                publishIfDirty()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            rfBuffer.clear()
            isBuffer.clear()
            dirty = false
        }
        _entries.value = emptyList()
    }

    private fun appendFromKiss(payload: ByteArray, transport: KissTransport) {
        val logTransport = when (transport) {
            KissTransport.BLE -> LogTransport.RF_BLE
            KissTransport.TCP -> LogTransport.TNC_TCP
        }
        val ax = Ax25Frame.parse(payload)
        val packet = parser.parseKissPayload(payload)
        val path = ax?.digipeaters?.joinToString(",").orEmpty()
        val raw = when {
            ax != null -> {
                val digis = if (path.isNotEmpty()) ",$path" else ""
                "${ax.source}>${ax.destination}$digis:${ax.infoText}"
            }
            else -> payload.joinToString(" ") { "%02X".format(it) }
        }

        val entry = when (packet) {
            is AprsPacket.TextMessage -> entry(
                source = logTransport,
                kind = LogKind.MESSAGE,
                from = packet.sourceCall,
                to = packet.addressee,
                path = path,
                summary = packet.messageText,
                raw = raw
            )
            is AprsPacket.Position -> entry(
                source = logTransport,
                kind = LogKind.POSITION,
                from = packet.sourceCall,
                to = packet.destCall,
                path = path,
                summary = formatPosition(
                    packet.latitude,
                    packet.longitude,
                    packet.comment
                ),
                raw = raw,
                symbol = packet.symbol
            )
            is AprsPacket.Other -> entry(
                source = logTransport,
                kind = LogKind.OTHER,
                from = packet.sourceCall,
                to = packet.destCall,
                path = path,
                summary = packet.rawInfo.take(120),
                raw = raw
            )
            null -> entry(
                source = logTransport,
                kind = LogKind.RAW,
                from = ax?.source.orEmpty(),
                to = ax?.destination.orEmpty(),
                path = path,
                summary = "AX.25 ${payload.size}B",
                raw = raw
            )
        }
        push(entry)
    }

    private fun appendFromInternet(line: String) {
        val frame = Tnc2Codec.parse(line)
        if (frame == null) {
            push(
                entry(
                    source = LogTransport.INTERNET,
                    kind = LogKind.RAW,
                    from = "",
                    to = "",
                    path = "",
                    summary = line.take(120),
                    raw = line
                )
            )
            return
        }

        val inner = Tnc2Codec.effective(frame.source, frame.destination, frame.info)
        val path = (inner.digipeaters.ifEmpty { frame.digipeaters }).joinToString(",")
        val from = inner.source
        val dest = inner.destination
        val info = inner.info
        val msg = AprsMessageCodec.parse(info)
        val uncompressed = AprsPositionParser.parse(info)
        val mice = if (uncompressed == null) {
            MiceParser.parse(dest, info)
        } else {
            null
        }

        val entry = when {
            msg != null -> entry(
                source = LogTransport.INTERNET,
                kind = LogKind.MESSAGE,
                from = from,
                to = msg.addressee,
                path = path,
                summary = msg.text,
                raw = line
            )
            uncompressed != null -> entry(
                source = LogTransport.INTERNET,
                kind = LogKind.POSITION,
                from = from,
                to = dest,
                path = path,
                summary = formatPosition(
                    uncompressed.latitude,
                    uncompressed.longitude,
                    uncompressed.comment
                ),
                raw = line,
                symbol = uncompressed.symbol
            )
            mice != null -> entry(
                source = LogTransport.INTERNET,
                kind = LogKind.POSITION,
                from = from,
                to = dest,
                path = path,
                summary = formatPosition(mice.latitude, mice.longitude, mice.comment),
                raw = line,
                symbol = mice.symbol
            )
            else -> entry(
                source = LogTransport.INTERNET,
                kind = LogKind.OTHER,
                from = from,
                to = dest,
                path = path,
                summary = info.take(120),
                raw = line
            )
        }
        push(entry)
    }

    private fun entry(
        source: LogTransport,
        kind: LogKind,
        from: String,
        to: String,
        path: String,
        summary: String,
        raw: String,
        symbol: String = ""
    ): AprsLogEntry {
        if (symbol.length >= 2) rememberSymbol(from, symbol)
        return AprsLogEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            source = source,
            kind = kind,
            from = from,
            to = to,
            path = path,
            summary = summary,
            raw = raw,
            symbol = symbolFor(from, symbol)
        )
    }

    private fun rememberSymbol(call: String, symbol: String) {
        val key = AprsMessageCodec.canonicalCall(call)
        if (key.isBlank() || symbol.length < 2) return
        symbolsByCall[key] = symbol.take(2)
    }

    private fun symbolFor(call: String, seen: String): String {
        if (seen.length >= 2) return seen.take(2)
        val key = AprsMessageCodec.canonicalCall(call)
        if (key.isBlank()) return ""
        symbolsByCall[key]?.let { return it }
        val own = settings.ownPosition.value
        if (own != null &&
            AprsMessageCodec.sameCallsignWithSsid(own.callsign, call) &&
            own.symbol.length >= 2
        ) {
            return own.symbol.take(2)
        }
        return ""
    }

    private fun formatPosition(lat: Double, lon: Double, comment: String): String =
        String.format(java.util.Locale.US, "%.4f %.4f %s", lat, lon, comment).trim()

    private fun push(entry: AprsLogEntry) {
        synchronized(lock) {
            if (entry.source == LogTransport.INTERNET) {
                isBuffer.addFirst(entry)
                while (isBuffer.size > MAX_IS) isBuffer.removeLast()
            } else {
                rfBuffer.addFirst(entry)
                while (rfBuffer.size > MAX_RF) rfBuffer.removeLast()
            }
            dirty = true
        }
    }

    private fun publishIfDirty() {
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            val merged = ArrayList<AprsLogEntry>(rfBuffer.size + isBuffer.size)
            merged.addAll(rfBuffer)
            merged.addAll(isBuffer)
            merged.sortByDescending { it.timestamp }
            merged
        }
        _entries.value = snapshot
    }

    companion object {
        /** DireWolf / BLE: not evicted by the APRS-IS flood. */
        private const val MAX_RF = 400
        private const val MAX_IS = 300
        private const val SNAPSHOT_MS = 2_000L
    }
}
