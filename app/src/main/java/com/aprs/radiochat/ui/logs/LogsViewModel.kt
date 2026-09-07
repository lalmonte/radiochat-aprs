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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aprs.radiochat.data.model.AprsLogEntry
import com.aprs.radiochat.data.model.LogKind
import com.aprs.radiochat.data.model.LogTransport
import com.aprs.radiochat.data.repository.AprsLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

enum class LogFilter {
    ALL,
    RF,
    INTERNET,
    MESSAGE,
    POSITION
}

class LogsViewModel(
    private val logRepository: AprsLogRepository
) : ViewModel() {

    private val _filter = MutableStateFlow(LogFilter.ALL)
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    private val _showRaw = MutableStateFlow(false)
    val showRaw: StateFlow<Boolean> = _showRaw.asStateFlow()

    val entries: StateFlow<List<AprsLogEntry>> = combine(
        logRepository.entries,
        _filter
    ) { list, filter ->
        if (filter == LogFilter.ALL) list
        else list.filter { entry ->
            when (filter) {
                LogFilter.ALL -> true
                LogFilter.RF ->
                    entry.source == LogTransport.RF_BLE || entry.source == LogTransport.TNC_TCP
                LogFilter.INTERNET -> entry.source == LogTransport.INTERNET
                LogFilter.MESSAGE -> entry.kind == LogKind.MESSAGE
                LogFilter.POSITION -> entry.kind == LogKind.POSITION
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope + Dispatchers.Default,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    fun setFilter(filter: LogFilter) {
        _filter.value = filter
    }

    fun toggleRaw() {
        _showRaw.value = !_showRaw.value
    }

    fun clear() = logRepository.clear()

    class Factory(
        private val logRepository: AprsLogRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LogsViewModel(logRepository) as T
        }
    }
}
