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
package com.aprs.radiochat.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aprs.radiochat.data.aprs.AprsSymbolIcons
import com.aprs.radiochat.data.model.AprsLogEntry
import com.aprs.radiochat.data.model.LogKind
import com.aprs.radiochat.data.model.LogTransport
import com.aprs.radiochat.ui.common.AprsSymbolImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    modifier: Modifier = Modifier
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val showRaw by viewModel.showRaw.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    // Follow new traffic while the user sits at the top; leave them alone once they scroll down to read.
    var followNewest by remember { mutableStateOf(true) }
    val newestId = entries.firstOrNull()?.id

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            AprsSymbolIcons.preload(context)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { dragging ->
            if (!dragging) {
                followNewest = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset < 80
            }
        }
    }

    LaunchedEffect(newestId) {
        if (followNewest && newestId != null) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("APRS traffic")
                        Text(
                            if (entries.isEmpty()) "Live" else "${entries.size} packets",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    FilterChip(
                        selected = showRaw,
                        onClick = viewModel::toggleRaw,
                        label = { Text("RAW") },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(
                        onClick = viewModel::clear,
                        enabled = entries.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { viewModel.setFilter(f) },
                        leadingIcon = {
                            Icon(
                                filterIcon(f),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(filterLabel(f)) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                if (entries.isEmpty()) {
                    EmptyLogsHint()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            entries,
                            key = { it.id },
                            contentType = { it.kind }
                        ) { entry ->
                            LogCard(entry = entry, showRaw = showRaw)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLogsHint() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ListAlt,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("No traffic yet", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Connect BLE, KISS TCP or APRS-IS to see packets with each station's icon.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LogCard(entry: AprsLogEntry, showRaw: Boolean) {
    val timeFmt = remember {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
    val time = remember(entry.timestamp) {
        timeFmt.format(Date(entry.timestamp))
    }
    val kindTint = kindColor(entry.kind)
    val call = entry.from.ifBlank { "?" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                tonalElevation = 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AprsSymbolImage(
                        symbol = entry.symbol,
                        size = 32.dp,
                        contentDescription = call
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = call,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = routeLine(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KindBadge(kindLabel(entry.kind), kindTint)
                    KindBadge(transportLabel(entry.source), MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = entry.summary.ifBlank { "(no info)" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (showRaw) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (showRaw && entry.raw.isNotBlank()) {
                    Text(
                        text = entry.raw.take(220),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun KindBadge(label: String, tint: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun kindColor(kind: LogKind): Color = when (kind) {
    LogKind.MESSAGE -> MaterialTheme.colorScheme.tertiary
    LogKind.POSITION -> MaterialTheme.colorScheme.primary
    LogKind.OTHER -> MaterialTheme.colorScheme.secondary
    LogKind.RAW -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun routeLine(entry: AprsLogEntry): String = buildString {
    append(entry.from.ifBlank { "?" })
    append(" → ")
    append(entry.to.ifBlank { "?" })
    if (entry.path.isNotBlank()) {
        append("  ·  ")
        append(entry.path)
    }
}

private fun transportLabel(source: LogTransport): String = when (source) {
    LogTransport.RF_BLE -> "BLE"
    LogTransport.TNC_TCP -> "TCP"
    LogTransport.INTERNET -> "IS"
}

private fun kindLabel(kind: LogKind): String = when (kind) {
    LogKind.MESSAGE -> "Message"
    LogKind.POSITION -> "Position"
    LogKind.OTHER -> "Other"
    LogKind.RAW -> "Raw"
}

private fun filterLabel(filter: LogFilter): String = when (filter) {
    LogFilter.ALL -> "All"
    LogFilter.RF -> "RF"
    LogFilter.INTERNET -> "IS"
    LogFilter.MESSAGE -> "Msg"
    LogFilter.POSITION -> "Pos"
}

private fun filterIcon(filter: LogFilter): ImageVector = when (filter) {
    LogFilter.ALL -> Icons.Filled.FilterList
    LogFilter.RF -> Icons.Filled.CellTower
    LogFilter.INTERNET -> Icons.Filled.Cloud
    LogFilter.MESSAGE -> Icons.AutoMirrored.Filled.Chat
    LogFilter.POSITION -> Icons.Filled.LocationOn
}
