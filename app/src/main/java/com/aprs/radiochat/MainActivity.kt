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
package com.aprs.radiochat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.aprs.radiochat.data.service.AprsTrackingService
import com.aprs.radiochat.ui.navigation.RadioChatAppNav
import com.aprs.radiochat.ui.theme.RadioChatTheme
import com.aprs.radiochat.ui.util.RequestBlePermissions

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_CHAT_PEER = "open_chat_peer"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // With edge-to-edge, Compose handles the IME via imePadding (adjustResize in the manifest)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            RadioChatTheme {
                // BLE + location + notification permissions (Android 12+)
                RequestBlePermissions()
                RadioChatAppNav()
            }
        }
        handleChatIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleChatIntent(intent)
    }

    private fun handleChatIntent(intent: Intent?) {
        val peer = intent?.getStringExtra(EXTRA_OPEN_CHAT_PEER)?.trim().orEmpty()
        if (peer.isNotBlank()) {
            (application as RadioChatApp).requestOpenChat(peer)
            intent?.removeExtra(EXTRA_OPEN_CHAT_PEER)
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as RadioChatApp
        if (app.settingsRepository.beaconEnabled.value) {
            AprsTrackingService.start(this)
        }
    }
}
