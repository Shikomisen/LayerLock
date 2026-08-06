package com.shikomisen.layerlock.lockscreen

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the custom lock screen ready to show.
 *
 * ## Why a foreground service
 *
 * Nothing else can react to the screen turning on. `ACTION_SCREEN_ON` cannot be received from a
 * manifest-declared receiver — it has to be registered at runtime by a component that is already
 * alive — so showing a custom lock screen at the moment the device wakes requires a process that
 * stays running. A foreground service is the honest way to do that: it costs a permanent, visible
 * notification, which is precisely the point. The user can see the app is running, and can turn the
 * whole feature off from that notification.
 *
 * ## The constraint worth knowing before testing this
 *
 * Android 10 restricted starting activities from the background. A foreground service does not
 * blanket-exempt an app from that, and the exemptions that do exist vary by OEM and Android version —
 * which is exactly why §11 lists Samsung, Xiaomi, OnePlus and a Pixel as required test hardware and
 * §12 flags this as the highest-risk surface in the app. On devices where the start is refused, the
 * lock scene simply does not appear and the stock keyguard is shown unchanged; nothing breaks, and
 * the device stays secure either way.
 *
 * This is the piece to validate first on real hardware.
 */
class LockScreenService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> showLockScene()
                // Already unlocked: stop any retry still waiting for a keyguard that will not come.
                Intent.ACTION_USER_PRESENT -> handler.removeCallbacksAndMessages(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    /**
     * Shows the lock scene once the keyguard has actually come up.
     *
     * `ACTION_SCREEN_ON` fires *before* the keyguard is necessarily up, so a single
     * [KeyguardManager.isKeyguardLocked] check races it and loses often enough to look random — the
     * scene appears on some wakes and not others. Re-checking for a short window converts that race
     * into a wait. If the keyguard never appears (the device was unlocked, or the user unlocked
     * within the window) nothing is shown, which is the correct outcome.
     */
    private fun showLockScene(attempt: Int = 0) {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            runCatching { startActivity(LockScreenActivity.intent(this)) }
            return
        }
        if (attempt < KEYGUARD_ATTEMPTS) {
            handler.postDelayed({ showLockScene(attempt + 1) }, KEYGUARD_RETRY_MS)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Lock screen",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "Shown while your custom lock screen is active."
                },
            )
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LayerLock lock screen is on")
            .setContentText("Tap to change or turn it off")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "layerlock_lockscreen"
        private const val NOTIFICATION_ID = 4201

        /** ~1.5s total. Long enough for a slow keyguard, short enough not to flash in late. */
        private const val KEYGUARD_ATTEMPTS = 10
        private const val KEYGUARD_RETRY_MS = 150L

        fun start(context: Context) {
            val intent = Intent(context, LockScreenService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockScreenService::class.java))
        }
    }
}
