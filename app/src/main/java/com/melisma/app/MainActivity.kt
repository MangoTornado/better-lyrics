package com.melisma.app

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
import com.melisma.app.settings.ViewMode
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.melisma.app.ui.PlayerScreen
import com.melisma.app.ui.theme.MelismaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.melisma.app.media.MediaNotificationListener

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as MelismaApp).container

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
        // Android draws a scrim behind the navigation bar to keep its buttons legible over
        // whatever is underneath. Over a full-screen lyric page that reads as a grey strip stuck
        // to the edge; the app's own background is dark enough for the buttons without it.
        window.isNavigationBarContrastEnforced = false

        ContextCompat.registerReceiver(
            this,
            popupActionReceiver,
            IntentFilter(ACTION_POPUP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            MelismaTheme {
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

        // Keep the declared window parameters in step with everything they depend on:
        // play/pause for the transport icons, and the popup settings themselves. The old
        // version only re-declared them while already in the window, so turning popup off
        // in the settings sheet — which never resumes the activity — left auto-enter armed
        // and pressing Home still shrank the app.
        lifecycleScope.launch {
            combine(
                container.media.snapshot,
                container.settings.settings,
                container.saving,
            ) { snapshot, settings, saving ->
                PopupInputs(
                    isPlaying = snapshot.playback.isPlaying,
                    hasTrack = snapshot.hasTrack,
                    transport = snapshot.transport,
                    enabled = settings.popupLyricsEnabled,
                    autoEnter = settings.popupAutoEnter,
                    shape = settings.popupShape,
                    keepScreenOn = settings.keepScreenOn,
                    // Switching battery saver on has to let go of the screen there and then, not
                    // at the next pause.
                    releaseScreen = saving.releaseScreen,
                    cinema = settings.viewMode == ViewMode.CINEMA,
                )
            }.distinctUntilChanged().collect {
                applyPopupParams(it.isPlaying)
                // Pausing must let the screen time out, and it is the same signal.
                applyKeepScreenOn()
                applyImmersive(it.cinema)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.uiVisible = true
        // Ask for the notification listener back. It may have been stood down while nothing was
        // playing — see MediaNotificationListener — and nothing rebinds it on its own. Rebinding is
        // asynchronous, so the first read of the sessions can still fail; the retry backoff in
        // MediaSessionRepository is what covers that, and was written for the same situation after
        // the permission is first granted.
        MediaNotificationListener.rebind(this)
        // The permission may also have been granted while we were in the background, and the
        // listener service is only bound after that happens.
        container.media.refresh()
        container.media.start()
    }

    /**
     * Let go of the media sessions when nothing of ours is on screen.
     *
     * The notification-listener service keeps the process alive on its own, so without
     * this the session callbacks — and the lyrics lookups they drive — would keep running
     * for every track the user plays long after they closed the app. A floating window is
     * still on screen, so that keeps watching.
     */
    override fun onStop() {
        super.onStop()
        // A floating window is still on screen, so it still counts as visible — unless this stop is
        // the window being dismissed, which Android delivers *while still in* picture-in-picture
        // rather than by restoring a normal window first. Without the `isFinishing` half, dismissing
        // the popup left the app marked visible for ever, which gates the idle timer permanently:
        // exactly the bug the timer exists to prevent.
        container.uiVisible = isInPictureInPictureMode && !isFinishing

        // Deliberately *not* stopping the media repository here.
        //
        // The idle watch decides when to let go, and it needs a live playback signal to decide with.
        // Detaching the callbacks froze the last snapshot, so closing the app mid-song left
        // `isPlaying` true forever and nothing ever stood down — while closing it paused and then
        // playing from elsewhere could stand down mid-song. Keeping one controller's callbacks for
        // the length of the timeout costs almost nothing; being wrong about what is playing costs
        // the whole feature.
    }

    override fun onDestroy() {
        // Belt and braces for the dismissal case above: whatever the state flags said, a destroyed
        // activity is not on screen.
        container.uiVisible = false
        runCatching { unregisterReceiver(popupActionReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        container.media.refresh()
        // Battery saver may have been switched on while the process was gone.
        container.refreshPowerSave()
        applyKeepScreenOn()
        applyPopupParams(container.media.snapshot.value.playback.isPlaying)
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
            // Nothing playing means the window would contain the words "Nothing playing",
            // which is not worth taking over the screen for — least of all during first-run
            // setup, when leaving for the notification-access screen would trigger it. The
            // pre-Android-12 path has always checked this; auto-enter must too.
            builder.setAutoEnterEnabled(
                settings.popupLyricsEnabled &&
                    settings.popupAutoEnter &&
                    container.media.snapshot.value.hasTrack,
            )
            builder.setSeamlessResizeEnabled(true)
        }

        runCatching { setPictureInPictureParams(builder.build()) }
    }

    /** Everything [applyPopupParams] reads, so the collector can skip repeated work. */
    private data class PopupInputs(
        val isPlaying: Boolean,
        val hasTrack: Boolean,
        val transport: com.melisma.app.media.Transport,
        val enabled: Boolean,
        val autoEnter: Boolean,
        val shape: com.melisma.app.settings.PopupShape,
        /**
         * Not a popup input, but the same collector applies it.
         *
         * Left out, `distinctUntilChanged` swallowed the emission — the settings flow fired, the
         * tuple was unchanged, and the flag stayed as it was until playback happened to change.
         */
        val keepScreenOn: Boolean,
        /** Also not a popup input, and here for the same reason. */
        val releaseScreen: Boolean,
        /** And this one, which decides whether the navigation bar is in the way. */
        val cinema: Boolean,
    )



    /**
     * The buttons on the floating window.
     *
     * Only the ones the player will actually act on: a `RemoteAction` cannot be greyed out
     * the way an in-app button can, so an unsupported one would be a button that does
     * nothing with no way to tell.
     */
    private fun popupActions(isPlaying: Boolean): List<RemoteAction> {
        val transport = container.media.snapshot.value.transport
        if (!transport.any) return emptyList()
        return buildList {
            if (transport.skipPrevious) {
                add(
                    remoteAction(
                        android.R.drawable.ic_media_previous,
                        "Previous",
                        ACTION_PREVIOUS,
                        0,
                    ),
                )
            }
            if (transport.playPause) {
                add(
                    remoteAction(
                        if (isPlaying) {
                            android.R.drawable.ic_media_pause
                        } else {
                            android.R.drawable.ic_media_play
                        },
                        if (isPlaying) "Pause" else "Play",
                        ACTION_PLAY_PAUSE,
                        1,
                    ),
                )
            }
            if (transport.skipNext) {
                add(remoteAction(android.R.drawable.ic_media_next, "Next", ACTION_NEXT, 2))
            }
        }
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

    /**
     * Hold the screen awake — but only while something is actually playing.
     *
     * The screen is the most expensive thing on a phone by a wide margin, and this was applied on
     * the setting alone: the app would sit on "Nothing playing" holding the display on until the
     * battery ran down. The setting means "do not time out *while I am reading along*", which is a
     * statement about playback, not about the app being open.
     *
     * Re-applied whenever playback changes, not once on resume, or a track ending would leave the
     * flag set for as long as the app stayed open.
     */
    /**
     * Get the navigation bar out of the way in Cinema view.
     *
     * Cinema is the album art beside the words with nothing else on screen, and held sideways the
     * bar is a column down one edge — it covered the transport buttons and took width the lyrics
     * wanted. Hidden rather than dimmed, because a translucent bar still reserves its inset.
     *
     * Transient, so a swipe from the edge brings it back for anyone using the three buttons rather
     * than gestures, and it goes away again on its own.
     */
    private fun applyImmersive(cinema: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (cinema) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun applyKeepScreenOn() {
        val wanted = container.settings.current.keepScreenOn &&
            container.media.snapshot.value.playback.isPlaying &&
            // Battery saver, if it has been allowed this one. The screen is the most expensive
            // thing on the phone by a wide margin, so it is the measure that saves the most and
            // costs the most — which is why it is off by default and asked for explicitly.
            !container.saving.value.releaseScreen
        if (wanted) {
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
        const val ACTION_POPUP_CONTROL = "com.melisma.app.POPUP_CONTROL"
        const val EXTRA_POPUP_ACTION = "action"
        const val ACTION_PLAY_PAUSE = "play_pause"
        const val ACTION_NEXT = "next"
        const val ACTION_PREVIOUS = "previous"
    }
}
