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

/**
 * Radios the app can talk to over Bluetooth LE.
 *
 * Each entry maps to one [BleRadioProfile]. They do not share a wire protocol: the
 * Radtel speaks plain KISS over a Nordic-style UART, the Benshi radios speak their own
 * framed protocol. Keeping them behind [profile] is what stops one from breaking the
 * other.
 *
 * [id] is persisted in settings, so never change an existing value.
 */
enum class RadioModel(
    val id: String,
    val displayName: String,
    val shortName: String
) {
    RADTEL_RT950_PRO("radtel_rt950_pro", "Radtel RT-950 Pro", "RT-950 Pro"),
    BTECH_UV_PRO("btech_uv_pro", "BTECH UV-PRO", "UV-PRO");

    val profile: BleRadioProfile
        get() = when (this) {
            RADTEL_RT950_PRO -> RadtelKissProfile
            BTECH_UV_PRO -> BenshiProfile
        }

    companion object {
        val DEFAULT = RADTEL_RT950_PRO

        fun fromId(id: String?): RadioModel =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
