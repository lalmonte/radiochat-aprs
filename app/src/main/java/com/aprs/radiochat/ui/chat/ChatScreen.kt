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
package com.aprs.radiochat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aprs.radiochat.data.model.ChatConversation
import com.aprs.radiochat.data.model.ChatMessage
import com.aprs.radiochat.data.model.MessageAckStatus
import com.aprs.radiochat.data.model.MessageSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val selectedPeer by viewModel.selectedPeer.collectAsStateWithLifecycle()
    val showNewChat by viewModel.showNewChat.collectAsStateWithLifecycle()
    val newPeerDraft by viewModel.newPeerDraft.collectAsStateWithLifecycle()
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    val confirmDeletePeer by viewModel.confirmDeletePeer.collectAsStateWithLifecycle()
    val confirmDeleteAll by viewModel.confirmDeleteAll.collectAsStateWithLifecycle()

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = viewModel::dismissNewChat,
            title = { Text("New conversation") },
            text = {
                OutlinedTextField(
                    value = newPeerDraft,
                    onValueChange = viewModel::onNewPeerChange,
                    label = { Text("Destination callsign") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::startNewChat,
                    enabled = newPeerDraft.isNotBlank()
                ) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissNewChat) { Text("Cancel") }
            }
        )
    }

    if (confirmDeletePeer != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConversation,
            title = { Text("Delete conversation") },
            text = {
                Text("Delete every message with ${confirmDeletePeer}?")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteConversation) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConversation) {
                    Text("Cancel")
                }
            }
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteAll,
            title = { Text("Delete all chats") },
            text = {
                Text("The full history of every conversation will be deleted. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteAll) {
                    Text("Delete all")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteAll) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (selectedPeer == null) {
            ConversationList(
                viewModel = viewModel,
                error = sendError,
                onClearError = viewModel::clearError
            )
        } else {
            ConversationThread(
                peer = selectedPeer!!,
                viewModel = viewModel,
                error = sendError,
                onClearError = viewModel::clearError
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationList(
    viewModel: ChatViewModel,
    error: String?,
    onClearError: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APRS conversations") },
                actions = {
                    if (conversations.isNotEmpty()) {
                        IconButton(onClick = viewModel::requestDeleteAll) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = "Delete all"
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openNewChat) {
                Icon(Icons.Filled.Add, contentDescription = "New")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = "History is saved · Swipe to delete",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (error != null) {
                ErrorBanner(error, onClearError)
            }
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No conversations yet.\nTap + to write over the Internet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(conversations, key = { it.peerCallsign }) { conv ->
                        SwipeDeleteConversation(
                            conv = conv,
                            onOpen = { viewModel.openConversation(conv.peerCallsign) },
                            onDelete = { viewModel.requestDeleteConversation(conv.peerCallsign) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDeleteConversation(
    conv: ChatConversation,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart ||
                value == SwipeToDismissBoxValue.StartToEnd
            ) {
                onDelete()
                false // no dismiss until confirmed
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        content = {
            ConversationRow(
                conv = conv,
                onClick = onOpen,
                onDeleteClick = onDelete
            )
        }
    )
}

@Composable
private fun ConversationRow(
    conv: ChatConversation,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val time = remember(conv.lastTimestamp) {
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(conv.lastTimestamp))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = conv.peerCallsign.take(2),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = conv.peerCallsign,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (conv.unread) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = conv.lastMessage,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (conv.unread) FontWeight.SemiBold else FontWeight.Normal,
                color = if (conv.unread) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = if (conv.unread) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (conv.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (conv.unreadCount > 99) "99+" else "${conv.unreadCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        IconButton(onClick = onDeleteClick) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete conversation",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationThread(
    peer: String,
    viewModel: ChatViewModel,
    error: String?,
    onClearError: () -> Unit
) {
    val thread by viewModel.threadMessages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val listState = remember(peer) { LazyListState() }
    val newestId = thread.lastOrNull()?.id

    LaunchedEffect(peer, newestId) {
        if (thread.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(thread.lastIndex)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(peer) },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeConversation) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.requestDeleteConversation(peer) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete conversation")
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
            if (error != null) {
                ErrorBanner(error, onClearError)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(thread, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = viewModel::onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message (IS or KISS TCP)…") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.isNotBlank()) viewModel.send()
                        }
                    )
                )
                IconButton(
                    onClick = viewModel::send,
                    enabled = draft.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClear) { Text("OK") }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val align = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bg = if (message.isOutgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (message.isOutgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(Date(message.timestamp))
    val sourceIcon = when (message.source) {
        MessageSource.RF_BLE -> Icons.Filled.Radio
        MessageSource.RF_TCP -> Icons.Filled.Dns
        MessageSource.INTERNET -> Icons.Filled.Cloud
        MessageSource.OUTGOING -> Icons.Filled.CellTower
        MessageSource.OUTGOING_TCP -> Icons.Filled.Dns
    }
    val sourceLabel = when (message.source) {
        MessageSource.RF_BLE -> "BLE"
        MessageSource.RF_TCP -> "TCP"
        MessageSource.INTERNET -> "IS"
        MessageSource.OUTGOING -> "IS"
        MessageSource.OUTGOING_TCP -> "TCP"
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .align(align)
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    sourceIcon,
                    contentDescription = sourceLabel,
                    modifier = Modifier.size(14.dp),
                    tint = fg.copy(alpha = 0.85f)
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.85f)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = message.text, color = fg)
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.7f)
                )
                AckStatusIcon(message = message, bubbleFg = fg)
            }
        }
    }
}

@Composable
private fun AckStatusIcon(message: ChatMessage, bubbleFg: Color) {
    val status = when {
        message.isOutgoing && message.ackStatus == MessageAckStatus.NONE ->
            MessageAckStatus.SENT
        else -> message.ackStatus
    }
    if (status == MessageAckStatus.NONE) return
    val icon = when (status) {
        MessageAckStatus.SENT -> Icons.Filled.Done
        MessageAckStatus.DELIVERED -> Icons.Filled.DoneAll
        MessageAckStatus.REJECTED -> Icons.Filled.ErrorOutline
        MessageAckStatus.NONE -> return
    }
    val label = when (status) {
        MessageAckStatus.SENT -> if (message.isOutgoing) "Sent" else "No ACK"
        MessageAckStatus.DELIVERED -> if (message.isOutgoing) "Delivered" else "ACK sent"
        MessageAckStatus.REJECTED -> "Rejected"
        MessageAckStatus.NONE -> return
    }
    val tint = when (status) {
        MessageAckStatus.SENT -> bubbleFg.copy(alpha = 0.55f)
        MessageAckStatus.DELIVERED ->
            if (message.isOutgoing) Color(0xFFB3E5FC) else bubbleFg.copy(alpha = 0.75f)
        MessageAckStatus.REJECTED -> Color(0xFFFF8A80)
        MessageAckStatus.NONE -> return
    }
    Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.size(14.dp),
        tint = tint
    )
}
