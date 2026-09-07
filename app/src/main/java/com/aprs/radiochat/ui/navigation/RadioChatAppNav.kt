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
package com.aprs.radiochat.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aprs.radiochat.RadioChatApp
import com.aprs.radiochat.ui.chat.ChatScreen
import com.aprs.radiochat.ui.chat.ChatViewModel
import com.aprs.radiochat.ui.connection.ConnectionViewModel
import com.aprs.radiochat.ui.logs.LogsScreen
import com.aprs.radiochat.ui.logs.LogsViewModel
import com.aprs.radiochat.ui.map.MapScreen
import com.aprs.radiochat.ui.map.MapViewModel
import com.aprs.radiochat.ui.more.MoreNavHost
import com.aprs.radiochat.ui.more.MoreRoutes

private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Chat("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
    Map("map", "Map", Icons.Filled.Map),
    Logs("logs", "Logs", Icons.AutoMirrored.Filled.ListAlt),
    More("more", "More", Icons.Filled.MoreHoriz)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioChatAppNav() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as RadioChatApp
    val tabs = Tab.entries
    val imeVisible = WindowInsets.isImeVisible
    var moreNestedRoute by rememberSaveable { mutableStateOf(MoreRoutes.HUB) }

    val connectionVm: ConnectionViewModel = viewModel(
        factory = ConnectionViewModel.Factory(
            app.bleUartManager,
            app.tcpKissTncClient,
            app.aprsIsClient,
            app.iGateService,
            app.settingsRepository,
            app.beaconService,
            app.phoneLocationTracker
        )
    )
    val chatVm: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(app.chatRepository)
    )
    val mapVm: MapViewModel = viewModel(
        factory = MapViewModel.Factory(app.stationRepository, app.beaconTrackStore)
    )
    val logsVm: LogsViewModel = viewModel(
        factory = LogsViewModel.Factory(app.aprsLogRepository)
    )

    val pendingChatPeer by app.pendingChatPeer.collectAsStateWithLifecycle()
    LaunchedEffect(pendingChatPeer) {
        val peer = pendingChatPeer ?: return@LaunchedEffect
        navController.navigate(Tab.Chat.route) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = false
            }
            launchSingleTop = true
            restoreState = false
        }
        chatVm.openConversation(peer)
        app.consumePendingChatPeer()
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = navBackStackEntry?.destination
    val onMoreHub = currentTab?.hierarchy?.any { it.route == Tab.More.route } == true &&
        moreNestedRoute == MoreRoutes.HUB
    val showBottomBar = !imeVisible && (
        currentTab?.hierarchy?.any { it.route == Tab.More.route } != true || onMoreHub
    )

    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                val alreadyThere =
                                    currentTab?.hierarchy?.any { it.route == tab.route } == true
                                if (alreadyThere) return@NavigationBarItem
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = false
                                    }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Chat.route,
            modifier = Modifier.padding(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable(Tab.Chat.route) {
                ChatScreen(viewModel = chatVm)
            }
            composable(Tab.Map.route) {
                MapScreen(viewModel = mapVm)
            }
            composable(Tab.Logs.route) {
                LogsScreen(viewModel = logsVm)
            }
            composable(Tab.More.route) {
                MoreNavHost(
                    viewModel = connectionVm,
                    onNestedRouteChange = { moreNestedRoute = it }
                )
            }
        }
    }
}
