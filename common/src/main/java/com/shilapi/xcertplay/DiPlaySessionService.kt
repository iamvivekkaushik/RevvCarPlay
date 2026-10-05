package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.shilapi.xcertplay.embed.EmbeddedCarPlay
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
        // While a host app (Revv) shows CarPlay, the notification goes back to that app.
        val hostLaunch = EmbeddedCarPlay.hostPackage?.takeIf { EmbeddedCarPlay.running }?.let { packageManager.getLaunchIntentForPackage(it) }
        val open = PendingIntent.getActivity(this, 0, hostLaunch ?: Intent(this, CarPlayHostActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("CarPlay connection running")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Without it Android stops location updates while another car app (the reversing camera,
            // the car's own map) covers CarPlay, and the iPhone gets no position until DiPlay is back.
            // Wireless needs it too: on Android 11/12 Wi-Fi Direct group lookups need location, and
            // without it a reconnect while another app is in front cannot see, reclaim or replace
            // this app's own previous group, so every createGroup answers "busy".
            val wantsLocation = AirPlayPersistence.loadLocationReportingEnabled(this) || AirPlayPersistence.loadWirelessEnabled(this)
            if (wantsLocation && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            // Android 14+ refuses the location and microphone types when the app is not eligible at this
            // moment (started from the background, e.g. by Revv's embed binding: Android 16 on a Galaxy
            // S23 refused the microphone even with RECORD_AUDIO granted); carry on without them.
            val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            val attempts = listOf(types, types and location.inv(), types and microphone.inv(), types and (location or microphone).inv()).distinct()
            for ((index, attempt) in attempts.withIndex()) {
                try {
                    startForeground(1, notification, attempt)
                    break
                } catch (denied: SecurityException) {
                    if (index == attempts.lastIndex) throw denied
                }
            }
        } else startForeground(1, notification)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // BYD's recents force-stops the package ~10 ms after removing the task: end guidance first.
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        private const val CHANNEL = "diplay_connection"
    }
}
