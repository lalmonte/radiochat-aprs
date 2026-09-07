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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aprs.radiochat.data.model.ChatConversation
import com.aprs.radiochat.data.model.ChatMessage
import com.aprs.radiochat.data.model.conversationsFrom
import com.aprs.radiochat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

class ChatViewModel(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _selectedPeer = MutableStateFlow<String?>(null)
    val selectedPeer: StateFlow<String?> = _selectedPeer.asStateFlow()

    val conversations: StateFlow<List<ChatConversation>> = chatRepository.messages
        .map { conversationsFrom(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope + Dispatchers.Default,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val threadMessages: StateFlow<List<ChatMessage>> = combine(
        chatRepository.messages,
        _selectedPeer
    ) { msgs, peer ->
        if (peer == null) emptyList()
        else msgs.filter { it.peer.equals(peer, ignoreCase = true) }
            .sortedBy { it.timestamp }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope + Dispatchers.Default,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val sendError: StateFlow<String?> = chatRepository.sendError
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _newPeerDraft = MutableStateFlow("")
    val newPeerDraft: StateFlow<String> = _newPeerDraft.asStateFlow()

    private val _showNewChat = MutableStateFlow(false)
    val showNewChat: StateFlow<Boolean> = _showNewChat.asStateFlow()

    private val _confirmDeletePeer = MutableStateFlow<String?>(null)
    val confirmDeletePeer: StateFlow<String?> = _confirmDeletePeer.asStateFlow()

    private val _confirmDeleteAll = MutableStateFlow(false)
    val confirmDeleteAll: StateFlow<Boolean> = _confirmDeleteAll.asStateFlow()

    fun openConversation(peer: String) {
        val key = peer.uppercase()
        _selectedPeer.value = key
        _showNewChat.value = false
        chatRepository.openConversation(key)
    }

    fun closeConversation() {
        chatRepository.closeConversation()
        _selectedPeer.value = null
        _draft.value = ""
    }

    fun openNewChat() {
        _showNewChat.value = true
        _newPeerDraft.value = ""
    }

    fun dismissNewChat() {
        _showNewChat.value = false
    }

    fun onNewPeerChange(value: String) {
        _newPeerDraft.value = value.uppercase()
    }

    fun startNewChat() {
        val peer = _newPeerDraft.value.trim()
        if (peer.isEmpty()) return
        _showNewChat.value = false
        openConversation(peer)
    }

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    fun send() {
        val to = _selectedPeer.value?.trim().orEmpty()
        val text = _draft.value.trim()
        if (to.isEmpty() || text.isEmpty()) return
        if (chatRepository.sendMessage(to, text)) {
            _draft.value = ""
        }
    }

    fun requestDeleteConversation(peer: String) {
        _confirmDeletePeer.value = peer.uppercase()
    }

    fun dismissDeleteConversation() {
        _confirmDeletePeer.value = null
    }

    fun confirmDeleteConversation() {
        val peer = _confirmDeletePeer.value ?: return
        chatRepository.deleteConversation(peer)
        _confirmDeletePeer.value = null
        if (_selectedPeer.value.equals(peer, ignoreCase = true)) {
            closeConversation()
        }
    }

    fun requestDeleteAll() {
        _confirmDeleteAll.value = true
    }

    fun dismissDeleteAll() {
        _confirmDeleteAll.value = false
    }

    fun confirmDeleteAll() {
        chatRepository.deleteAllConversations()
        _confirmDeleteAll.value = false
        closeConversation()
    }

    fun clearError() = chatRepository.clearSendError()

    class Factory(
        private val chatRepository: ChatRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(chatRepository) as T
        }
    }
}
