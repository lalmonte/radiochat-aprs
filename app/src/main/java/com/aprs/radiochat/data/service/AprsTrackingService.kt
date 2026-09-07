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
package com.aprs.radiochat.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aprs.radiochat.RadioChatApp
import com.aprs.radiochat.MainActivity
import com.aprs.radiochat.R
import com.aprs.radiochat.data.location.PhoneLocationFix
import com.aprs.radiochat.data.location.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (location type) for GPS + beacon while the app is in the
 * background, another app is in front, or the screen is off.
 */
class AprsTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notifyJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        startForegroundInternal(buildNotification("Starting GPS…"))
        val app = application as RadioChatApp
        scope.launch { app.phoneLocationTracker.start() }
        observeForNotification(app)
        Log.i(TAG, "Tracking service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                (application as RadioChatApp).settingsRepository.setBeaconEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val app = application as RadioChatApp
                if (!app.settingsRepository.beaconEnabled.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                scope.launch { app.phoneLocationTracker.start() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        notifyJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Tracking service destroyed")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stay alive when the user swipes the task away from recents.
        Log.i(TAG, "Task removed; tracking continues in the background")
    }

    private var lastNotifyAt = 0L
    private var lastNotifyText = ""

    private fun observeForNotification(app: RadioChatApp) {
        notifyJob?.cancel()
        notifyJob = scope.launch {
            combine(
                app.phoneLocationTracker.fix,
                app.beaconService.lastStatus
            ) { fix, status -> fix to status }
                .collect { (fix, status) ->
                    val km = TrackPoint.distanceKm(app.beaconTrackStore.points.value)
                    val text = summary(fix, km, status)
                    val now = System.currentTimeMillis()
                    val tx = status?.startsWith("TX") == true && text != lastNotifyText
                    if (!tx && now - lastNotifyAt < NOTIFY_MIN_INTERVAL_MS) return@collect
                    lastNotifyAt = now
                    lastNotifyText = text
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(text))
                }
        }
    }

    private fun summary(fix: PhoneLocationFix?, km: Double, status: String?): String {
        val gps = if (fix != null) {
            "%.4f, %.4f".format(fix.latitude, fix.longitude)
        } else {
            "Waiting for GPS…"
        }
        val dist = if (km >= 0.05) " · %.1f km".format(km) else ""
        val tx = status?.takeIf { it.startsWith("TX") }?.let { " · $it" }.orEmpty()
        return "$gps$dist$tx"
    }

    private fun startForegroundInternal(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AprsTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gps)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.tracking_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.tracking_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "radiochat:gps"
        ).also {
            it.setReferenceCounted(false)
            it.acquire(WAKE_LOCK_MAX_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "AprsTracking"
        private const val CHANNEL_ID = "aprs_gps_tracking"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.aprs.radiochat.STOP_TRACKING"
        /** 12 h; the service stops earlier if the user turns the beacon off. */
        private const val WAKE_LOCK_MAX_MS = 12 * 60 * 60 * 1000L
        private const val NOTIFY_MIN_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, AprsTrackingService::class.java)
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start background tracking: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.stopService(Intent(app, AprsTrackingService::class.java))
            }
        }
    }
}
