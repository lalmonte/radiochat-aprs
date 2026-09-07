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
package com.aprs.radiochat.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aprs.radiochat.ui.connection.ConnectionViewModel
import com.aprs.radiochat.ui.settings.AboutSettingsScreen
import com.aprs.radiochat.ui.settings.BeaconSettingsScreen
import com.aprs.radiochat.ui.settings.ConnectionsSettingsScreen
import com.aprs.radiochat.ui.settings.NotificationSettingsScreen
import com.aprs.radiochat.ui.settings.StationSettingsScreen

object MoreRoutes {
    const val HUB = "hub"
    const val STATION = "station"
    const val CONNECTIONS = "connections"
    const val BEACON = "beacon"
    const val NOTIFICATIONS = "notifications"
    const val ABOUT = "about"
}

@Composable
fun MoreNavHost(
    viewModel: ConnectionViewModel,
    onNestedRouteChange: (String) -> Unit,
    navController: NavHostController = rememberNavController()
) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: MoreRoutes.HUB
    LaunchedEffect(route) {
        onNestedRouteChange(route)
    }

    NavHost(
        navController = navController,
        startDestination = MoreRoutes.HUB
    ) {
        composable(MoreRoutes.HUB) {
            MoreHubScreen(
                viewModel = viewModel,
                onOpenStation = { navController.navigate(MoreRoutes.STATION) },
                onOpenConnections = { navController.navigate(MoreRoutes.CONNECTIONS) },
                onOpenBeacon = { navController.navigate(MoreRoutes.BEACON) },
                onOpenNotifications = { navController.navigate(MoreRoutes.NOTIFICATIONS) },
                onOpenAbout = { navController.navigate(MoreRoutes.ABOUT) }
            )
        }
        composable(MoreRoutes.STATION) {
            StationSettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(MoreRoutes.CONNECTIONS) {
            ConnectionsSettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(MoreRoutes.BEACON) {
            BeaconSettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(MoreRoutes.NOTIFICATIONS) {
            NotificationSettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(MoreRoutes.ABOUT) {
            AboutSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
