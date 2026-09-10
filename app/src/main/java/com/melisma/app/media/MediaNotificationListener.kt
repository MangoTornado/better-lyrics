package com.melisma.app.media

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Declared purely for the permission it carries — and released as soon as it is not needed.
 *
 * `MediaSessionManager.getActiveSessions()` — the only way to read another app's playhead — takes
 * the [ComponentName] of an *enabled notification listener* as proof that the user opted in. So we
 * register one, and it does nothing with notifications at all.
 *
 * The cost of that is easy to miss and not small. An enabled listener is a **bound** service, which
 * means Android keeps this process resident indefinitely, and it delivers *every notification on the
 * device* to [onNotificationPosted] — a binder call and a process wake for each one, from every app,
 * whether or not anything is playing. On a busy phone that is a steady drip of work for an app the
 * user finished with hours ago, and it is why this one appeared never to close.
 *
 * [standDown] is the way out. `requestUnbind` releases the service, after which Android will not
 * rebind on its own — so the process keeps nothing alive, stops being woken, and becomes an empty
 * background process the system can reclaim at will. [rebind] asks for it back, which the app does
 * when it is next opened.
 *
 * The trade is explicit and it is the point: while stood down the app cannot see what is playing.
 * Opening it starts watching again.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        bound = this
    }

    override fun onListenerDisconnected() {
        bound = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        bound = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Intentionally empty: notifications are never read. They arrive anyway, which is the
        // reason [standDown] exists.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty.
    }

    companion object {
        private const val TAG = "MediaListener"

        /**
         * The live service, while it is bound.
         *
         * A static reference to a service is normally a leak; this one is cleared on both
         * disconnect and destroy, which are the only ways it ends.
         */
        @Volatile
        private var bound: MediaNotificationListener? = null

        val isBound: Boolean get() = bound != null

        /**
         * Give up the binding.
         *
         * Nothing rebinds afterwards without [rebind] — that is the whole point, and also the whole
         * risk: forget to call it and the app never sees a media session again.
         */
        fun standDown() {
            val service = bound ?: return
            Log.i(TAG, "releasing the notification listener; nothing is playing")
            runCatching { service.requestUnbind() }
        }

        /** Ask for the binding back. Safe to call when already bound. */
        fun rebind(context: Context) {
            if (isBound) return
            runCatching {
                requestRebind(ComponentName(context, MediaNotificationListener::class.java))
            }
        }
    }
}
