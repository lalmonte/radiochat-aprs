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
package com.aprs.radiochat.data.kiss

import com.aprs.radiochat.data.ble.BleUartManager
import com.aprs.radiochat.data.tnc.TcpKissTncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Joins KISS frames from BLE (radio) and TCP (DireWolf) into a single stream.
 */
class KissFrameHub(
    ble: BleUartManager,
    tcp: TcpKissTncClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _frames = MutableSharedFlow<KissFrame>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: SharedFlow<KissFrame> = _frames.asSharedFlow()

    /** Synchronous delivery (RF/DireWolf log) in addition to the SharedFlow. */
    @Volatile
    var onFrame: ((KissFrame) -> Unit)? = null

    init {
        // TCP: direct callback from the read thread (does not depend on collectors).
        tcp.onAx25Payload = { payload ->
            if (payload.isNotEmpty()) {
                dispatch(KissFrame(KissTransport.TCP, payload))
            }
        }
        scope.launch {
            ble.kissPayloads.collect { payload ->
                if (payload.isNotEmpty()) {
                    dispatch(KissFrame(KissTransport.BLE, payload))
                }
            }
        }
    }

    private fun dispatch(frame: KissFrame) {
        try {
            onFrame?.invoke(frame)
        } catch (_: Exception) {
        }
        _frames.tryEmit(frame)
    }
}
