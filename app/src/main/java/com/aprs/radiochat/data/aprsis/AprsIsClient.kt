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
package com.aprs.radiochat.data.aprsis

import android.util.Log
import com.aprs.radiochat.data.model.AprsIsConnectionState
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * APRS-IS TCP client (port 14580) with keepalive, post-login filter and reconnect.
 */
class AprsIsClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState =
        MutableStateFlow<AprsIsConnectionState>(AprsIsConnectionState.Disconnected)
    val connectionState: StateFlow<AprsIsConnectionState> = _connectionState.asStateFlow()

    /**
     * tryEmit + SUSPEND (the default) dropped bursts: the log lost lines.
     * DROP_OLDEST keeps the newest ones when a collector falls behind.
     */
    private val _incomingLines = MutableSharedFlow<String>(
        extraBufferCapacity = 2048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingLines: SharedFlow<String> = _incomingLines.asSharedFlow()

    /**
     * Synchronous delivery from the read thread (the log does not depend on the SharedFlow).
     */
    @Volatile
    var onIncomingLine: ((String) -> Unit)? = null

    private val stopRequested = AtomicBoolean(false)
    private val sessionGen = AtomicInteger(0)
    private var sessionJob: Job? = null

    private val writeMutex = Mutex()
    private val writerRef = AtomicReference<BufferedWriter?>(null)
    private val socketRef = AtomicReference<Socket?>(null)

    /**
     * Outbound queue. `sendLine` used to return `true` and fire the write separately:
     * a failure was only logged, and the beacon or the chat considered sent something
     * that never left. Now a failure tears the session down and the state stops being
     * Connected.
     */
    private val txQueue = Channel<String>(capacity = TX_QUEUE_CAPACITY)

    private val pendingFilter = AtomicReference<String?>(null)
    private val lastSentFilter = AtomicReference<String?>(null)
    private val lastFilterPushMs = AtomicLong(0L)

    private var connectCallsign: String = ""
    private var connectPasscode: Int = -1
    private var connectServer: String = DEFAULT_SERVER
    private var connectPort: Int = DEFAULT_PORT
    private var connectFilter: String = ""
    private var autoReconnect: Boolean = true

    fun connect(
        callsign: String,
        passcode: Int,
        server: String = DEFAULT_SERVER,
        port: Int = DEFAULT_PORT,
        filter: String,
        reconnect: Boolean = true
    ) {
        autoReconnect = reconnect
        connectCallsign = callsign.uppercase().trim()
        connectPasscode = passcode
        connectServer = server.trim().ifBlank { DEFAULT_SERVER }
        connectPort = port
        connectFilter = sanitizeFilter(filter)
        pendingFilter.set(connectFilter)
        lastSentFilter.set(null)

        stopRequested.set(false)
        restartSession(resetError = true)
    }

    fun disconnect() {
        autoReconnect = false
        stopRequested.set(true)
        sessionGen.incrementAndGet()
        sessionJob?.cancel()
        sessionJob = null
        closeSocketQuietly()
        while (txQueue.tryReceive().isSuccess) {
            // drain the outbound queue
        }
        _connectionState.value = AprsIsConnectionState.Disconnected
    }

    /**
     * Queues a TNC2 line (without CRLF).
     * @return false when there is no connected session or the outbound queue is full.
     */
    fun sendLine(line: String): Boolean {
        if (_connectionState.value !is AprsIsConnectionState.Connected) return false
        val clean = line.trimEnd('\r', '\n')
        if (clean.isEmpty()) return false
        if (!txQueue.trySend(clean).isSuccess) {
            Log.w(TAG, "IS TX dropped: outbound queue full")
            return false
        }
        return true
    }

    /**
     * Updates the filter. [immediate]=true applies it right away (km changed);
     * otherwise it is debounced so movement does not spam the server.
     */
    fun updateFilter(filter: String, immediate: Boolean = false): Boolean {
        val f = sanitizeFilter(filter)
        if (f.isEmpty()) return false
        pendingFilter.set(f)
        connectFilter = f
        if (f == lastSentFilter.get()) return true
        if (_connectionState.value !is AprsIsConnectionState.Connected) return false
        val now = System.currentTimeMillis()
        if (!immediate && now - lastFilterPushMs.get() < FILTER_DEBOUNCE_MS) {
            return true
        }
        lastFilterPushMs.set(now)
        lastSentFilter.set(f)
        return sendLine("# filter $f")
    }

    private fun restartSession(resetError: Boolean) {
        sessionJob?.cancel()
        closeSocketQuietly()
        val gen = sessionGen.incrementAndGet()
        if (resetError) {
            _connectionState.value = AprsIsConnectionState.Connecting
        }
        sessionJob = scope.launch {
            var attempt = 0
            while (!stopRequested.get() && sessionGen.get() == gen) {
                try {
                    if (attempt > 0) {
                        val backoff = (2_000L * (1L shl (attempt - 1).coerceAtMost(4)))
                            .coerceAtMost(30_000L)
                        Log.i(TAG, "Reconnecting APRS-IS in ${backoff}ms (attempt $attempt)…")
                        _connectionState.value = AprsIsConnectionState.Connecting
                        delay(backoff)
                        if (stopRequested.get() || sessionGen.get() != gen) return@launch
                    }
                    runSession(gen)
                    // Clean exit from the read loop
                    if (stopRequested.get() || sessionGen.get() != gen) return@launch
                    if (!autoReconnect) {
                        _connectionState.value = AprsIsConnectionState.Disconnected
                        return@launch
                    }
                    attempt++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (stopRequested.get() || sessionGen.get() != gen) return@launch
                    val msg = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "APRS-IS: $msg", e)
                    if (!autoReconnect) {
                        _connectionState.value = AprsIsConnectionState.Error(friendlyError(msg))
                        return@launch
                    }
                    _connectionState.value =
                        AprsIsConnectionState.Error("${friendlyError(msg)} · retrying…")
                    attempt++
                }
            }
        }
    }

    private suspend fun runSession(gen: Int) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.keepAlive = true
        // Detect a dead link; the app-level keepalive avoids ordinary timeouts
        socket.soTimeout = READ_TIMEOUT_MS
        socket.connect(InetSocketAddress(connectServer, connectPort), CONNECT_TIMEOUT_MS)
        if (sessionGen.get() != gen || stopRequested.get()) {
            runCatching { socket.close() }
            return
        }
        socketRef.set(socket)

        val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
        )
        val writer = BufferedWriter(
            OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        )
        writerRef.set(writer)

        val banner = try {
            reader.readLine()
        } catch (e: SocketTimeoutException) {
            throw SocketException("No banner from the server (timeout)")
        }
        Log.i(TAG, "Banner: $banner")

        // Log in WITHOUT the filter on the same line (more compatible). Filter afterwards.
        val loginCall = connectCallsign
        val login = "user $loginCall pass $connectPasscode vers RadioChatAPRS 1.0"
        writeLocked(writer, login)
        Log.i(TAG, "Login: user $loginCall pass ****")

        var verified = false
        val deadline = System.currentTimeMillis() + LOGIN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (sessionGen.get() != gen || stopRequested.get()) return
            val line = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                break
            } ?: break
            Log.i(TAG, "IS← $line")
            when {
                line.contains("unverified", ignoreCase = true) -> {
                    verified = false
                    break
                }
                line.contains("verified", ignoreCase = true) -> {
                    verified = true
                    break
                }
                !line.startsWith('#') && line.contains('>') -> {
                    dispatchIncoming(line)
                }
            }
        }

        if (!verified) {
            Log.w(TAG, "Login unverified (passcode?). Continuing RX-only if the server allows it.")
        }

        _connectionState.value = AprsIsConnectionState.Connected(connectServer, verified)
        Log.i(TAG, "Connected to $connectServer (verified=$verified)")

        // Filter after login
        val filter = pendingFilter.get() ?: connectFilter
        if (filter.isNotBlank()) {
            writeLocked(writer, "# filter $filter")
            lastFilterPushMs.set(System.currentTimeMillis())
            lastSentFilter.set(filter)
            Log.i(TAG, "Filter: $filter")
        }

        val writerJob = scope.launch {
            try {
                while (sessionGen.get() == gen && !stopRequested.get()) {
                    val pending = txQueue.receive()
                    if (sessionGen.get() != gen) {
                        // Session already replaced: hand the line back to the new one.
                        txQueue.trySend(pending)
                        return@launch
                    }
                    writeLocked(writer, pending)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Close the captured socket, never socketRef: if the session was already
                // replaced, closing by reference would kill the new session.
                Log.w(TAG, "IS TX failed, tearing the session down: ${e.message}")
                runCatching { socket.close() }
            }
        }

        val keepaliveJob = scope.launch {
            while (isActive && sessionGen.get() == gen && !stopRequested.get()) {
                delay(KEEPALIVE_MS)
                if (sessionGen.get() != gen || stopRequested.get()) break
                // Flush the pending filter (debounced), only when it changed
                val pending = pendingFilter.get()
                if (pending != null &&
                    pending != lastSentFilter.get() &&
                    System.currentTimeMillis() - lastFilterPushMs.get() >= FILTER_DEBOUNCE_MS
                ) {
                    if (writeRaw("# filter $pending")) {
                        lastFilterPushMs.set(System.currentTimeMillis())
                        lastSentFilter.set(pending)
                        Log.i(TAG, "Filter (flush): $pending")
                    }
                }
                writeRaw("# RadioChatAPRS keepalive")
            }
        }

        try {
            while (sessionGen.get() == gen && !stopRequested.get()) {
                val line = try {
                    reader.readLine()
                } catch (_: SocketTimeoutException) {
                    // No data: the keepalive should be holding the link up
                    continue
                }
                if (line == null) {
                    throw SocketException("Server closed the connection")
                }
                if (line.isBlank()) continue
                if (line.startsWith('#')) {
                    Log.d(TAG, "IS# $line")
                    // Some servers warn about an invalid filter / rate limit
                    if (line.contains("invalid", ignoreCase = true) ||
                        line.contains("filter", ignoreCase = true)
                    ) {
                        Log.w(TAG, "Server notice: $line")
                    }
                    continue
                }
                dispatchIncoming(line)
            }
        } finally {
            keepaliveJob.cancel()
            writerJob.cancel()
            closeSocketQuietly()
        }
    }

    private fun dispatchIncoming(line: String) {
        try {
            onIncomingLine?.invoke(line)
        } catch (e: Exception) {
            Log.w(TAG, "onIncomingLine: ${e.message}")
        }
        _incomingLines.tryEmit(line)
    }

    private suspend fun writeRaw(line: String): Boolean {
        val w = writerRef.get() ?: return false
        return try {
            writeLocked(w, line)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Write failed: ${e.message}")
            false
        }
    }

    private suspend fun writeLocked(writer: BufferedWriter, line: String) {
        writeMutex.withLock {
            writer.write(line)
            writer.write("\r\n")
            writer.flush()
            if (!line.startsWith("# RadioChatAPRS keepalive")) {
                Log.i(TAG, "IS→ $line")
            }
        }
    }

    private fun closeSocketQuietly() {
        writerRef.getAndSet(null)
        val s = socketRef.getAndSet(null)
        runCatching { s?.close() }
    }

    private fun sanitizeFilter(raw: String): String {
        // Avoid locale decimal commas and double spaces
        return raw.trim()
            .replace(',', '.')
            .replace(Regex("\\s+"), " ")
    }

    private fun friendlyError(msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("software caused connection abort") ||
                m.contains("connection abort") ->
                "Connection aborted (network/server). Retrying…"
            m.contains("connection reset") ->
                "Server closed the connection"
            m.contains("broken pipe") ->
                "APRS-IS link broken"
            m.contains("timeout") || m.contains("timed out") ->
                "APRS-IS timeout"
            m.contains("unable to resolve") || m.contains("unknown host") ->
                "Server not resolved"
            else -> msg.take(120)
        }
    }

    companion object {
        private const val TAG = "AprsIsClient"
        private const val TX_QUEUE_CAPACITY = 128
        const val DEFAULT_SERVER = "rotate.aprs2.net"
        const val DEFAULT_PORT = 14580
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val LOGIN_WAIT_MS = 12_000L
        private const val KEEPALIVE_MS = 25_000L
        private const val FILTER_DEBOUNCE_MS = 20_000L
    }
}
