package com.betterlyrics.app

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.betterlyrics.app.ui.PlayerScreen
import com.betterlyrics.app.ui.theme.BetterLyricsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as BetterLyricsApp).container

    private val _inPopup = MutableStateFlow(false)

    /** True while the lyrics are running in the floating window. */
    val inPopup: StateFlow<Boolean> = _inPopup.asStateFlow()

    /**
     * Transport buttons for the floating window.
     *
     * The window is too small for the app's own controls, so Android draws these itself
     * from [RemoteAction]s. They come back as broadcasts.
     */
    private val popupActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(EXTRA_POPUP_ACTION)) {
                ACTION_PLAY_PAUSE -> container.media.togglePlayPause()
                ACTION_NEXT -> container.media.skipNext()
                ACTION_PREVIOUS -> container.media.skipPrevious()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ContextCompat.registerReceiver(
            this,
            popupActionReceiver,
            IntentFilter(ACTION_POPUP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            BetterLyricsTheme {
                PlayerScreen(
                    container = container,
                    inPopup = inPopup,
                    onOpenNotificationAccess = { openNotificationAccess() },
                    onEnterPopup = { enterPopup() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        handleIntent(intent)

        // Keep the window's own controls in step with play/pause.
        lifecycleScope.launch {
            container.media.snapshot.collect { snapshot ->
                if (_inPopup.value) applyPopupParams(snapshot.playback.isPlaying)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // The permission may have been granted while we were in the background, and the
        // listener service is only bound after that happens.
        container.media.refresh()
        container.media.start()
    }

    override fun onResume() {
        super.onResume()
        container.media.refresh()
        applyKeepScreenOn()
        applyPopupParams(container.media.snapshot.value.playback.isPlaying)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(popupActionReceiver) }
        super.onDestroy()
    }

    // ---- popup lyrics -------------------------------------------------------

    /**
     * Declares how the floating window should behave.
     *
     * On Android 12+ `setAutoEnterEnabled` is what makes leaving the app shrink the
     * lyrics into a window without a gesture of its own — the behaviour people know from
     * YouTube. Below that there is no auto-enter, so [onUserLeaveHint] asks explicitly.
     */
    private fun applyPopupParams(isPlaying: Boolean) {
        if (!popupSupported()) return
        val settings = container.settings.current

        val shape = settings.popupShape
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(shape.widthRatio, shape.heightRatio))
            .setActions(popupActions(isPlaying))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(settings.popupLyricsEnabled && settings.popupAutoEnter)
            builder.setSeamlessResizeEnabled(true)
        }

        runCatching { setPictureInPictureParams(builder.build()) }
    }

    private fun popupActions(isPlaying: Boolean): List<RemoteAction> {
        if (!container.media.snapshot.value.canControl) return emptyList()
        return listOf(
            remoteAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                ACTION_PREVIOUS,
                0,
            ),
            remoteAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                ACTION_PLAY_PAUSE,
                1,
            ),
            remoteAction(android.R.drawable.ic_media_next, "Next", ACTION_NEXT, 2),
        )
    }

    private fun remoteAction(
        iconRes: Int,
        title: String,
        action: String,
        requestCode: Int,
    ): RemoteAction {
        val intent = Intent(ACTION_POPUP_CONTROL)
            .setPackage(packageName)
            .putExtra(EXTRA_POPUP_ACTION, action)
        val pending = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pending)
    }

    /** Manual entry: the button in the app, and the pre-Android-12 auto-enter path. */
    fun enterPopup() {
        if (!popupSupported() || !container.settings.current.popupLyricsEnabled) return
        val playing = container.media.snapshot.value.playback.isPlaying
        val shape = container.settings.current.popupShape
        applyPopupParams(playing)
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(shape.widthRatio, shape.heightRatio))
                    .setActions(popupActions(playing))
                    .build(),
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ handles this itself via setAutoEnterEnabled; doing it twice would
        // fight the system animation.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        val settings = container.settings.current
        if (!settings.popupLyricsEnabled || !settings.popupAutoEnter) return
        if (!container.media.snapshot.value.hasTrack) return
        enterPopup()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _inPopup.value = isInPictureInPictureMode
    }

    private fun popupSupported(): Boolean =
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    // ---- misc ---------------------------------------------------------------

    private fun applyKeepScreenOn() {
        if (container.settings.current.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Opening a `.lrc` / `.ttml` from a file manager attaches it to the current track. */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        lifecycleScope.launch { container.lyrics.importLocal(uri) }
    }

    private fun openNotificationAccess() {
        runCatching { startActivity(container.media.notificationAccessIntent()) }
    }

    private companion object {
        const val ACTION_POPUP_CONTROL = "com.betterlyrics.app.POPUP_CONTROL"
        const val EXTRA_POPUP_ACTION = "action"
        const val ACTION_PLAY_PAUSE = "play_pause"
        const val ACTION_NEXT = "next"
        const val ACTION_PREVIOUS = "previous"
    }
}
