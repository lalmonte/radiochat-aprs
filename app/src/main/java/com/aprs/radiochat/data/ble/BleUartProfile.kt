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
package com.aprs.radiochat.data.ble

import java.util.UUID

/**
 * BLE KISS profile of the Radtel RT-950 Pro (BLE KISS mode in the APRS menu).
 *
 * BLE KISS mode (documented in [mecta02/aprs](https://github.com/mecta02/aprs)):
 * - **FFE1** → notify (radio → app)
 * - **FF31** → write (app → radio, KISS frames)
 *
 * The FF31 unlock is only for CPS programming mode (serial), do NOT use it in BLE KISS.
 */
object BleUartProfile {
    val SERVICE_UUID: UUID =
        UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")

    /** Notify: radio → app */
    val NOTIFY_CHAR_UUID: UUID =
        UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")

    /** KISS write: app → radio (BLE KISS mode) */
    val WRITE_CHAR_UUID: UUID =
        UUID.fromString("0000FF31-0000-1000-8000-00805F9B34FB")

    /** Fallback write on some firmwares */
    val WRITE_CHAR_ALT_UUID: UUID =
        UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")

    val CCCD_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    val DEVICE_NAME_HINTS = listOf(
        "RT-950", "RT950", "Radtel", "950 Pro", "950PRO", "BT-RT950", "RT_950"
    )
}
