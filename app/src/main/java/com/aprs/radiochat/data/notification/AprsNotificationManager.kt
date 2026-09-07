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
package com.aprs.radiochat.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aprs.radiochat.MainActivity
import com.aprs.radiochat.R
import com.aprs.radiochat.data.repository.SettingsRepository

/**
 * Notifications for chat, ACK/REJ and dropped links.
 */
class AprsNotificationManager(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private val nm = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    fun showIncomingMessage(peer: String, from: String, text: String, isBulletin: Boolean) {
        if (!settings.notifyMessages.value) return
        if (isBulletin && !settings.notifyBulletins.value) return
        if (!canNotify()) return

        val peerKey = peer.uppercase()
        val notifId = messageNotifId(peerKey)
        val body = text.take(200).ifBlank { "(empty message)" }
        val pending = chatPendingIntent(peerKey, notifId)

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(from)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(GROUP_MESSAGES)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(notifId, notification)
        showMessageSummary()
    }

    fun showAck(peer: String, messageId: String, rejected: Boolean) {
        if (!settings.notifyAck.value) return
        if (!canNotify()) return

        val peerKey = peer.uppercase()
        val title = if (rejected) {
            context.getString(R.string.notify_ack_rejected_title, peerKey)
        } else {
            context.getString(R.string.notify_ack_delivered_title, peerKey)
        }
        val body = if (rejected) {
            context.getString(R.string.notify_ack_rejected_body, messageId)
        } else {
            context.getString(R.string.notify_ack_delivered_body, messageId)
        }
        val notifId = ackNotifId(peerKey, messageId)
        val notification = NotificationCompat.Builder(context, CHANNEL_ACK)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(chatPendingIntent(peerKey, notifId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(notifId, notification)
    }

    fun showConnectionLost(label: String, detail: String) {
        if (!settings.notifyConnection.value) return
        if (!canNotify()) return

        val notifId = connectionNotifId(label)
        val notification = NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.notify_connection_lost_title, label))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notifId,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(notifId, notification)
    }

    fun cancelForPeer(peer: String) {
        val key = peer.uppercase()
        nm.cancel(messageNotifId(key))
        updateMessageSummary()
    }

    private fun showMessageSummary() {
        val summary = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.notify_messages_summary_title))
            .setContentText(context.getString(R.string.notify_messages_summary_body))
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(SUMMARY_ID, summary)
    }

    private fun updateMessageSummary() {
        nm.cancel(SUMMARY_ID)
    }

    private fun chatPendingIntent(peer: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_CHAT_PEER, peer)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return nm.areNotificationsEnabled()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sys = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sys.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.notify_channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notify_channel_messages_desc)
            }
        )
        sys.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACK,
                context.getString(R.string.notify_channel_ack),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notify_channel_ack_desc)
            }
        )
        sys.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONNECTION,
                context.getString(R.string.notify_channel_connection),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notify_channel_connection_desc)
            }
        )
    }

    companion object {
        const val CHANNEL_MESSAGES = "aprs_messages"
        const val CHANNEL_ACK = "aprs_ack"
        const val CHANNEL_CONNECTION = "aprs_connection"
        private const val GROUP_MESSAGES = "aprs_chat_messages"
        private const val SUMMARY_ID = 10_001

        private fun messageNotifId(peer: String): Int =
            ("msg:$peer").hashCode() and 0x7FFF

        private fun ackNotifId(peer: String, messageId: String): Int =
            ("ack:$peer:$messageId").hashCode() and 0x7FFF

        private fun connectionNotifId(label: String): Int =
            ("conn:$label").hashCode() and 0x7FFF
    }
}
