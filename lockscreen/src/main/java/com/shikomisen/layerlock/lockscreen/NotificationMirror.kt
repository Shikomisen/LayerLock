package com.shikomisen.layerlock.lockscreen

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification data mirrored onto the custom lock screen.
 *
 * Deliberately minimal: a count, and the currently playing track if there is one. Notification
 * content is among the most sensitive data an app can be granted, so nothing is persisted, nothing
 * leaves the device, and nothing is retained after the listener disconnects. What is held is the
 * least that makes the `notifications` and `music` widget layers work.
 */
object NotificationMirror {

    @Volatile
    var count: Int = 0
        private set

    @Volatile
    var nowPlaying: String? = null
        private set

    internal fun update(active: Array<StatusBarNotification>?) {
        val notifications = active.orEmpty().filterNot { it.isOngoing && it.notification.isMedia() }
        count = notifications.size
        nowPlaying = active.orEmpty()
            .firstOrNull { it.notification.isMedia() }
            ?.notification
            ?.extras
            ?.let { extras ->
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                listOfNotNull(title, artist).joinToString(" · ").ifBlank { null }
            }
    }

    internal fun clear() {
        count = 0
        nowPlaying = null
    }

    private fun Notification.isMedia(): Boolean =
        category == Notification.CATEGORY_TRANSPORT

    /** Whether the user has granted notification access. Never assume — always check. */
    fun isListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val component = ComponentName(context, LayerLockNotificationListener::class.java)
        return enabled.split(':').any { it.equals(component.flattenToString(), ignoreCase = true) }
    }

    /** The system settings screen where access is granted. Shown only after an in-app rationale. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}

/**
 * Optional notification listener.
 *
 * This is entirely opt-in and the app is fully usable without it — which matters, because §10 calls
 * for a clear in-app rationale *before* the system prompt, and Google reviews this permission
 * closely. The service does nothing until the user has granted access.
 */
class LayerLockNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationMirror.update(runCatching { activeNotifications }.getOrNull())
    }

    override fun onListenerDisconnected() {
        NotificationMirror.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        NotificationMirror.update(runCatching { activeNotifications }.getOrNull())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        NotificationMirror.update(runCatching { activeNotifications }.getOrNull())
    }
}
