package com.betterlyrics.app.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Declared purely for the permission it carries.
 *
 * `MediaSessionManager.getActiveSessions()` — the only way to read another app's
 * playhead — takes the [android.content.ComponentName] of an *enabled notification
 * listener* as proof that the user opted in. So we register one, and it does nothing
 * with notifications at all.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Intentionally empty: notifications are never read.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty.
    }
}
