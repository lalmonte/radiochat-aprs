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
package com.aprs.radiochat.data.repository

import android.content.Context
import android.util.Log
import com.aprs.radiochat.data.model.ChatMessage
import com.aprs.radiochat.data.model.MessageAckStatus
import com.aprs.radiochat.data.model.MessageSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Chat message persistence in `files/chat_messages.json`.
 */
class ChatStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<ChatMessage> {
        return try {
            if (!file.exists()) return emptyList()
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            buildList {
                for (i in 0 until arr.length()) {
                    parseMessage(arr.getJSONObject(i))?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading chats: ${e.message}", e)
            emptyList()
        }
    }

    fun save(messages: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            // Cap the history so it does not grow without bound
            val trimmed = if (messages.size > MAX_MESSAGES) {
                messages.takeLast(MAX_MESSAGES)
            } else {
                messages
            }
            trimmed.forEach { msg ->
                arr.put(
                    JSONObject()
                        .put("id", msg.id)
                        .put("from", msg.from)
                        .put("to", msg.to)
                        .put("text", msg.text)
                        .put("timestamp", msg.timestamp)
                        .put("isOutgoing", msg.isOutgoing)
                        .put("source", msg.source.name)
                        .put("peer", msg.peer)
                        .put("isRead", msg.isRead)
                        .put("messageId", msg.messageId ?: "")
                        .put("ackStatus", msg.ackStatus.name)
                )
            }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chats: ${e.message}", e)
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun parseMessage(obj: JSONObject): ChatMessage? {
        return try {
            val source = runCatching {
                MessageSource.valueOf(obj.getString("source"))
            }.getOrDefault(MessageSource.INTERNET)
            val outgoing = obj.getBoolean("isOutgoing")
            val storedAck = obj.optString("ackStatus").takeIf { it.isNotBlank() }
            val ackStatus = runCatching {
                if (storedAck != null) MessageAckStatus.valueOf(storedAck)
                else if (outgoing) MessageAckStatus.SENT else MessageAckStatus.NONE
            }.getOrDefault(if (outgoing) MessageAckStatus.SENT else MessageAckStatus.NONE)
            val messageId = obj.optString("messageId").takeIf { it.isNotBlank() }
            ChatMessage(
                id = obj.getString("id"),
                from = obj.getString("from"),
                to = obj.getString("to"),
                text = obj.getString("text"),
                timestamp = obj.getLong("timestamp"),
                isOutgoing = outgoing,
                source = source,
                peer = obj.getString("peer"),
                // Old history without the field: already considered read
                isRead = obj.optBoolean("isRead", true),
                messageId = messageId,
                ackStatus = ackStatus
            )
        } catch (e: Exception) {
            Log.w(TAG, "Invalid message in store: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ChatStore"
        private const val FILE_NAME = "chat_messages.json"
        private const val MAX_MESSAGES = 5_000
    }
}
