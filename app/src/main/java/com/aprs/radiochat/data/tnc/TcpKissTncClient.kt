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
package com.aprs.radiochat.data.tnc

import android.util.Log
import com.aprs.radiochat.data.kiss.KissCodec
import com.aprs.radiochat.data.kiss.KissFrame
import com.aprs.radiochat.data.kiss.KissTransport
import com.aprs.radiochat.data.model.TcpTncConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * KISS TCP client (DireWolf `KISSPORT`, kissutil, etc.).
 * Speaks pure binary KISS: FEND … FEND.
 *
 * Session design:
 * - One blocking reader and one writer draining [txQueue]; both die together.
 * - Any write failure tears the session down (it is not swallowed into a log) so
 *   that the state leaves [TcpTncConnectionState.Connected] and the chat stops
 *   believing it sent something that never left the phone.
 * - When the link goes quiet for [IDLE_PROBE_MS] a null KISS frame is sent to
 *   detect half-open sockets (router NAT, Doze, DireWolf restarted).
 */
class TcpKissTncClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState =
        MutableStateFlow<TcpTncConnectionState>(TcpTncConnectionState.Disconnected)
    val connectionState: StateFlow<TcpTncConnectionState> = _connectionState.asStateFlow()

    private val _kissFrames = MutableSharedFlow<KissFrame>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val kissFrames: SharedFlow<KissFrame> = _kissFrames.asSharedFlow()

    /**
     * Synchronous callback from the TCP read thread.
     * Avoids losing frames while the SharedFlow still has no collectors.
     */
    @Volatile
    var onAx25Payload: ((ByteArray) -> Unit)? = null

    /** Last byte received / sent (epoch ms). Diagnostic for a quiet link. */
    @Volatile
    var lastRxAtMs: Long = 0L
        private set

    @Volatile
    var lastTxAtMs: Long = 0L
        private set

    private val stopRequested = AtomicBoolean(false)
    private val sessionGen = AtomicInteger(0)
    private var sessionJob: Job? = null
    private val socketRef = AtomicReference<Socket?>(null)

    /** Outbound queue: bounds the backlog and lets a rejected send be reported. */
    private val txQueue = Channel<Outbound>(capacity = TX_QUEUE_CAPACITY)

    private var connectHost: String = DEFAULT_HOST
    private var connectPort: Int = DEFAULT_PORT
    private var autoReconnect: Boolean = true

    private class Outbound(val bytes: ByteArray, val queuedAt: Long, val isProbe: Boolean)

    fun connect(host: String, port: Int, reconnect: Boolean = true) {
        autoReconnect = reconnect
        connectHost = host.trim().ifBlank { DEFAULT_HOST }
        connectPort = port.coerceIn(1, 65_535)
        stopRequested.set(false)
        restartSession()
    }

    fun disconnect() {
        autoReconnect = false
        stopRequested.set(true)
        sessionGen.incrementAndGet()
        sessionJob?.cancel()
        sessionJob = null
        closeSocketQuietly()
        drainQueue()
        _connectionState.value = TcpTncConnectionState.Disconnected
    }

    /**
     * Queues a KISS-encapsulated AX.25 payload for the TCP socket.
     * @return false when there is no connected session or the outbound queue is full.
     *         A `true` means the frame is queued on a live session; if that session
     *         dies before writing it, the session is torn down and the state stops
     *         being `Connected`.
     */
    fun sendAx25(payload: ByteArray): Boolean {
        if (_connectionState.value !is TcpTncConnectionState.Connected) return false
        if (payload.isEmpty()) return false
        val item = Outbound(KissCodec.encode(payload), System.currentTimeMillis(), isProbe = false)
        val result = txQueue.trySend(item)
        if (!result.isSuccess) {
            Log.w(TAG, "TX KISS dropped: outbound queue full")
            return false
        }
        return true
    }

    private fun restartSession() {
        sessionJob?.cancel()
        closeSocketQuietly()
        val gen = sessionGen.incrementAndGet()
        _connectionState.value = TcpTncConnectionState.Connecting
        sessionJob = scope.launch {
            var attempt = 0
            while (!stopRequested.get() && sessionGen.get() == gen) {
                try {
                    if (attempt > 0) {
                        val backoff = (2_000L * (1L shl (attempt - 1).coerceAtMost(4)))
                            .coerceAtMost(30_000L)
                        Log.i(TAG, "Reconnecting TCP TNC in ${backoff}ms…")
                        _connectionState.value = TcpTncConnectionState.Connecting
                        delay(backoff)
                        if (stopRequested.get() || sessionGen.get() != gen) return@launch
                    }
                    val connectedAt = System.currentTimeMillis()
                    runSession(gen)
                    if (stopRequested.get() || sessionGen.get() != gen) return@launch
                    if (!autoReconnect) {
                        _connectionState.value = TcpTncConnectionState.Disconnected
                        return@launch
                    }
                    // A session that held up counts as healthy: don't drag the backoff
                    // from old drops along until it is pinned at 30 s.
                    attempt = if (isStableSession(connectedAt)) 1 else attempt + 1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (stopRequested.get() || sessionGen.get() != gen) return@launch
                    val msg = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "TNC TCP: $msg", e)
                    if (!autoReconnect) {
                        _connectionState.value = TcpTncConnectionState.Error(friendlyError(msg))
                        return@launch
                    }
                    _connectionState.value =
                        TcpTncConnectionState.Error("${friendlyError(msg)} · retrying…")
                    attempt++
                }
            }
        }
    }

    private fun isStableSession(connectedAt: Long) =
        System.currentTimeMillis() - connectedAt >= STABLE_SESSION_MS

    private suspend fun runSession(gen: Int) {
        val host = connectHost
        val port = connectPort
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = READ_POLL_MS
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        if (sessionGen.get() != gen || stopRequested.get()) {
            runCatching { socket.close() }
            return
        }
        socketRef.set(socket)
        val now = System.currentTimeMillis()
        lastRxAtMs = now
        lastTxAtMs = now
        _connectionState.value = TcpTncConnectionState.Connected(host, port)
        Log.i(TAG, "TCP TNC connected to $host:$port")

        val out: OutputStream = socket.getOutputStream()
        // Empty FEND: aligns the TNC decoder (null frame, discarded).
        txQueue.trySend(Outbound(NULL_FRAME, System.currentTimeMillis(), isProbe = true))

        val writer = scope.launch {
            try {
                while (sessionGen.get() == gen && !stopRequested.get()) {
                    val item = txQueue.receive()
                    if (sessionGen.get() != gen) {
                        // Session already replaced: hand the frame back to the new one.
                        txQueue.trySend(item)
                        return@launch
                    }
                    val age = System.currentTimeMillis() - item.queuedAt
                    if (!item.isProbe && age > TX_MAX_AGE_MS) {
                        Log.w(TAG, "TX KISS dropped: ${age}ms in the queue")
                        continue
                    }
                    out.write(item.bytes)
                    out.flush()
                    lastTxAtMs = System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A broken write = dead link. Closing this socket wakes its reader and
                // triggers the reconnect, instead of swallowing the packet.
                // The captured socket is closed, never socketRef: if the session was
                // already replaced, closing by reference would kill the new session.
                Log.w(TAG, "TX KISS failed, tearing the session down: ${e.message}")
                runCatching { socket.close() }
            }
        }

        val decoder = KissCodec.Decoder()
        val input: InputStream = socket.getInputStream()
        val buf = ByteArray(4096)

        try {
            while (sessionGen.get() == gen && !stopRequested.get() && scope.isActive) {
                val n = try {
                    input.read(buf)
                } catch (_: SocketTimeoutException) {
                    // No data: probe the link if it has been quiet for too long.
                    probeIfIdle()
                    continue
                }
                if (n < 0) {
                    throw SocketException("TNC server closed the connection")
                }
                if (n == 0) continue
                lastRxAtMs = System.currentTimeMillis()
                val chunk = buf.copyOf(n)
                Log.d(TAG, "RX raw ${n}B: ${chunk.toHex()}")
                for (payload in decoder.feed(chunk)) {
                    if (payload.isEmpty()) continue
                    Log.d(TAG, "RX AX.25 ${payload.size}B: ${payload.toHex()}")
                    dispatchIncoming(payload)
                }
            }
        } finally {
            writer.cancel()
            closeSocketQuietly()
        }
    }

    /**
     * A KISS link can stay quiet for hours without that being a failure, but a
     * half-open socket (expired NAT, roaming Wi-Fi, DireWolf restarted) looks the
     * same. The null frame forces a write: if the link is gone, the session drops.
     */
    private fun probeIfIdle() {
        val now = System.currentTimeMillis()
        if (now - maxOf(lastRxAtMs, lastTxAtMs) < IDLE_PROBE_MS) return
        txQueue.trySend(Outbound(NULL_FRAME, now, isProbe = true))
    }

    private fun drainQueue() {
        while (txQueue.tryReceive().isSuccess) {
            // drain
        }
    }

    private fun dispatchIncoming(payload: ByteArray) {
        try {
            onAx25Payload?.invoke(payload)
        } catch (e: Exception) {
            Log.w(TAG, "onAx25Payload failed: ${e.message}")
        }
        _kissFrames.tryEmit(KissFrame(KissTransport.TCP, payload))
    }

    private fun closeSocketQuietly() {
        val s = socketRef.getAndSet(null)
        runCatching { s?.close() }
    }

    private fun friendlyError(msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("econnrefused") || m.contains("connection refused") ->
                "Connection refused (is DireWolf KISSPORT running?)"
            m.contains("network is unreachable") || m.contains("no route") ->
                "Network unreachable"
            m.contains("timeout") || m.contains("timed out") ->
                "TCP TNC timeout"
            m.contains("unable to resolve") || m.contains("unknown host") ->
                "Host not resolved"
            else -> msg.take(120)
        }
    }

    companion object {
        private const val TAG = "TcpKissTnc"
        const val DEFAULT_HOST = "192.168.1.10"
        const val DEFAULT_PORT = 8001
        private const val CONNECT_TIMEOUT_MS = 10_000

        /** Reader wake-up to check the watchdog; this is not a link timeout. */
        private const val READ_POLL_MS = 5_000

        /** Silence (RX and TX) after which the socket is probed. */
        private const val IDLE_PROBE_MS = 30_000L

        /** A session longer than this counts as healthy and resets the backoff. */
        private const val STABLE_SESSION_MS = 60_000L

        private const val TX_QUEUE_CAPACITY = 64

        /** An APRS packet that has sat in the queue too long is no longer worth sending. */
        private const val TX_MAX_AGE_MS = 60_000L

        private val NULL_FRAME = byteArrayOf(KissCodec.FEND, KissCodec.FEND)

        private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
    }
}
