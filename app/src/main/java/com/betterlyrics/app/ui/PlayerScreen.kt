package com.betterlyrics.app.ui

import android.media.AudioManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.betterlyrics.app.AppContainer
import com.betterlyrics.app.BuildConfig
import com.betterlyrics.app.lyrics.LyricsState
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.settings.MediaPanelSide
import com.betterlyrics.app.settings.Settings
import com.betterlyrics.app.settings.ViewMode
import com.betterlyrics.app.ui.background.ArtworkColors
import com.betterlyrics.app.ui.background.DynamicBackground
import com.betterlyrics.app.ui.components.AppIcons
import com.betterlyrics.app.ui.components.MediaPanel
import com.betterlyrics.app.ui.components.NowBar
import com.betterlyrics.app.ui.components.VolumeRow
import com.betterlyrics.app.ui.lyrics.LyricsView
import com.betterlyrics.app.settings.TranslationSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.betterlyrics.app.update.Updater

@Composable
fun PlayerScreen(
    container: AppContainer,
    inPopup: StateFlow<Boolean>,
    onOpenNotificationAccess: () -> Unit,
    onEnterPopup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by container.media.snapshot.collectAsStateWithLifecycle()
    val permissionGranted by container.media.permissionGranted.collectAsStateWithLifecycle()
    val lyricsState by container.lyrics.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val popup by inPopup.collectAsStateWithLifecycle()
    val extras by container.extras.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val saving by container.saving.collectAsStateWithLifecycle()

    var demoMode by remember { mutableStateOf(false) }
    // Spotify's full-size cover beats the thumbnail a media session publishes, so prefer it
    // for the background, the palette and the artwork everywhere it is shown.
    val searchedArtwork by container.searchedArtwork.collectAsStateWithLifecycle()
    // Spotify's full-size cover first, then one found by searching, then whatever the player
    // published. In that order because each is a better claim about this exact track than the
    // next: an id beats a title match, and a title match only beats a thumbnail because the
    // thumbnail is too small to look at.
    val artwork = when {
        demoMode -> DemoLyrics.artwork
        else -> extras.cover ?: searchedArtwork ?: snapshot.artwork
    }
    val colors = remember(artwork) { ArtworkColors.from(artwork) }
    var showSettings by remember { mutableStateOf(false) }

    var showWelcome by remember { mutableStateOf(false) }
    // First run only. Deliberately not gated on the permission: the guide is what explains
    // why the permission is needed in the first place.
    LaunchedEffect(settings.welcomeSeen) {
        if (!settings.welcomeSeen) showWelcome = true
    }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }

    // Ask about updates once the screen is up. Respects the setting, and says nothing at all when
    // there is nothing to say.
    //
    // On every resume rather than once per composition: a media app is left running for days, so
    // "on launch" alone means a process that never dies never looks again. The first look of a run
    // always goes out and the rest are held off by the updater's interval, so coming back to the app
    // after a while checks, and flicking away and back does not.
    val updateState by container.updater.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            container.updater.check(automatic = true)
        }
    }

    // A floating window is the size of a postage stamp; whatever was covering the lyrics
    // has to get out of the way, or shrinking the app hands the user a miniature settings
    // sheet instead of the thing they wanted to keep watching.
    LaunchedEffect(popup) {
        if (popup) {
            showSettings = false
            showWelcome = false
            selectionMode = false
        }
    }

    if (!popup && !showWelcome) {
        UpdatePrompt(
            state = updateState,
            currentVersion = container.updater.currentVersion,
            onInstall = {
                // Both states, because pressing Install after cancelling Android's installer has to
                // hand the same file over again rather than doing nothing — which, with the only
                // button in the dialog, is what left it stuck.
                val release = when (val state = updateState) {
                    is Updater.State.Available -> state.release
                    is Updater.State.ReadyToInstall -> state.release
                    else -> null
                }
                release?.let { scope.launch { container.updater.downloadAndInstall(it) } }
            },
            onSkip = {
                (updateState as? Updater.State.Available)?.let {
                    container.updater.skip(it.release)
                }
            },
            onDismiss = { container.updater.dismiss() },
        )
    }
    var jumpSignal by remember { mutableIntStateOf(0) }

    // Leaving selection mode should not leave a highlight behind.
    LaunchedEffect(selectionMode) { if (!selectionMode) selected = emptySet() }
    LaunchedEffect(snapshot.track?.cacheKey) { selectionMode = false }

    val document = when {
        demoMode -> DemoLyrics.document
        else -> (lyricsState as? LyricsState.Loaded)?.document
    }

    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    var volume by remember { mutableFloatStateOf(audioManager.musicVolume()) }
    LaunchedEffect(settings.showVolumeSlider) {
        if (!settings.showVolumeSlider) return@LaunchedEffect
        // Poll so the slider still reflects the hardware keys — but only while there is a slider
        // on screen to reflect them on. Reading the volume is a call into the audio service, and
        // a composition outlives the window it was drawn in: unguarded, this went on asking twice
        // a second for as long as the process lived.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                volume = audioManager.musicVolume()
                delay(500)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = container.lyrics.importLocal(uri)
                Toast.makeText(
                    context,
                    if (ok) "Lyrics file loaded" else "That file isn't lyrics we understand",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun copyText(text: String, message: String) {
        if (text.isBlank()) return
        scope.launch {
            clipboard.setClipEntry(
                ClipEntry(android.content.ClipData.newPlainText("Lyrics", text)),
            )
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier.fillMaxSize()) {
        DynamicBackground(
            artwork = artwork,
            colors = colors,
            style = settings.backgroundStyle,
            blurRadius = settings.backgroundBlur,
            // A floating window is small and often on screen for a long time; a still
            // background there is both calmer and cheaper. Battery saver asks for the same
            // thing more directly.
            preferStill = (popup && settings.popupStillBackground) || saving.stillBackground,
            // Paused music stops the drift, which stops the redraws. The demo is exempt: it
            // exists to show the renderer off, and there is no playhead behind it to stop.
            playing = snapshot.playback.isPlaying || demoMode,
            artistImage = extras.artistImage,
            tempoBpm = extras.tempo,
        )

        if (popup) {
            PopupContent(
                document = document,
                settings = settings,
                snapshot = snapshot,
                artwork = artwork,
                positionProvider = rememberPositionProvider(snapshot, settings),
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                val controls: @Composable () -> Unit = {
                    ViewControls(
                        sourceLabel = if (demoMode) "Renderer preview" else snapshot.sourceLabel,
                        providerName = providerLabel(demoMode, lyricsState),
                        settings = settings,
                        accent = colors.accent,
                        romanizationAvailable = demoMode ||
                            (lyricsState as? LyricsState.Loaded)?.romanizationAvailable == true,
                        translationAvailable = demoMode ||
                            (lyricsState as? LyricsState.Loaded)?.translationPossible == true,
                        hasDocument = document != null,
                        popupAvailable = settings.popupLyricsEnabled,
                        onToggleRomanization = {
                            container.settings.setShowRomanization(!settings.showRomanization)
                        },
                        onToggleTranslation = {
                            val loaded = lyricsState as? LyricsState.Loaded
                            val supplied = loaded?.translationAvailable == true
                            val now = container.settings.toggleTranslation(supplied)
                            // Falling through to the on-device translator is the right
                            // answer but a surprising one, so it is said out loud — and it
                            // is the moment to mention the download, not after it starts.
                            if (now == TranslationSource.DEVICE && !supplied) {
                                Toast.makeText(
                                    context,
                                    "This source has no translation — translating on this " +
                                        "device instead. The first language downloads a model.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        onToggleCinema = { container.settings.toggleViewMode() },
                        onSwapSide = { container.settings.toggleMediaPanelSide() },
                        onEnterPopup = onEnterPopup,
                        onScrollToActive = { jumpSignal++ },
                        onStartSelection = { selectionMode = true },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onRetry = { container.lyrics.retry() },
                        onSettings = { showSettings = true },
                    )
                }

                if (settings.controlsPosition == com.betterlyrics.app.settings.ControlsPosition.TOP) {
                    Box(Modifier.statusBarsPadding()) { controls() }
                } else {
                    Spacer(Modifier.statusBarsPadding())
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MainContent(
                        container = container,
                        settings = settings,
                        snapshot = snapshot,
                        artwork = artwork,
                        colors = colors,
                        permissionGranted = permissionGranted,
                        lyricsState = lyricsState,
                        demoMode = demoMode,
                        document = document,
                        selectionMode = selectionMode,
                        selected = selected,
                        jumpSignal = jumpSignal,
                        volume = volume,
                        onVolumeChange = {
                            volume = it
                            audioManager.setMusicVolume(it)
                        },
                        onSelectLine = { index ->
                            selected = if (index in selected) selected - index else selected + index
                        },
                        onLongPressLine = { line ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            copyText(line.text, "Line copied")
                        },
                        onGrantPermission = onOpenNotificationAccess,
                        onStartDemo = { demoMode = true },
                    )
                }

                if (selectionMode) {
                    SelectionBar(
                        count = selected.size,
                        accent = colors.accent,
                        onCopy = {
                            val doc = document
                            if (doc != null) {
                                copyText(
                                    doc.textForLines(selected),
                                    if (selected.size == 1) "Line copied" else "${selected.size} lines copied",
                                )
                            }
                            selectionMode = false
                        },
                        onSelectAll = {
                            selected = document?.lines?.indices
                                ?.filter { document.lines[it].text.isNotBlank() }
                                ?.toSet()
                                .orEmpty()
                        },
                        onCancel = { selectionMode = false },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                } else if (settings.viewMode == ViewMode.LYRICS) {
                    // One navigation-bar inset for the pair of them. Applying it to the bar
                    // and again to the volume row put the whole bar's worth of gap between
                    // them, which is where the empty band above the slider came from.
                    Column(Modifier.navigationBarsPadding()) {
                        snapshot.track?.let { track ->
                            NowBar(
                                track = track,
                                playback = snapshot.playback,
                                artwork = artwork,
                                accent = colors.accent,
                                transport = snapshot.transport,
                                onTogglePlay = { container.media.togglePlayPause() },
                                onNext = { container.media.skipNext() },
                                onPrevious = { container.media.skipPrevious() },
                                onSeek = { container.media.seekTo(it) },
                                onOpenSource = { container.media.openSourceApp() },
                            )
                        }
                        if (settings.showVolumeSlider && snapshot.track != null) {
                            Box(Modifier.padding(horizontal = 18.dp)) {
                                VolumeRow(
                                    volume = volume,
                                    accent = colors.accent,
                                    onVolumeChange = {
                                        volume = it
                                        audioManager.setMusicVolume(it)
                                    },
                                )
                            }
                        }
                    }
                }

                if (settings.controlsPosition ==
                    com.betterlyrics.app.settings.ControlsPosition.BOTTOM
                ) {
                    Box(Modifier.navigationBarsPadding()) { controls() }
                } else if (settings.viewMode == ViewMode.CINEMA && !selectionMode) {
                    // Cinema view puts the transport buttons at the bottom of the artwork
                    // panel, and with the controls moved to the top there was nothing below
                    // to hold the navigation bar off them — so the system bar sat on top of
                    // play and skip. Reserve the inset explicitly.
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }

        if (showWelcome) {
            WelcomeSheet(
                accent = colors.accent,
                permissionGranted = permissionGranted,
                onGrantPermission = {
                    container.settings.setWelcomeSeen(true)
                    showWelcome = false
                    onOpenNotificationAccess()
                },
                onOpenSettings = {
                    container.settings.setWelcomeSeen(true)
                    showWelcome = false
                    showSettings = true
                },
                onDismiss = {
                    container.settings.setWelcomeSeen(true)
                    showWelcome = false
                },
            )
        }

        if (showSettings) {
            SettingsSheet(
                container = container,
                settings = settings,
                accent = colors.accent,
                songWriters = (lyricsState as? LyricsState.Loaded)?.document?.songWriters
                    ?: emptyList(),
                onCopyAll = {
                    val doc = document
                    if (doc != null) copyText(doc.allText(), "Lyrics copied")
                },
                onShowWelcome = {
                    showSettings = false
                    showWelcome = true
                },
                onDismiss = { showSettings = false },
            )
        }
    }
}

/** Recomputed only when the playhead report or the offset changes, never per frame. */
@Composable
private fun rememberPositionProvider(
    snapshot: com.betterlyrics.app.media.PlayerSnapshot,
    settings: Settings,
): () -> Long {
    val playback = snapshot.playback
    val offset = settings.syncOffsetMs
    return remember(playback, offset) { { playback.currentMs() + offset } }
}

private fun providerLabel(demoMode: Boolean, state: LyricsState): String? = when {
    demoMode -> DemoLyrics.document.providerName
    (state as? LyricsState.Loaded)?.translating == true -> "translating…"
    else -> (state as? LyricsState.Loaded)?.document?.providerName
}

private fun LyricsDocument.textForLines(indices: Set<Int>): String =
    lines.withIndex()
        .filter { it.index in indices && it.value.text.isNotBlank() }
        .joinToString("\n") { it.value.text }

private fun LyricsDocument.allText(): String =
    lines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }

private fun AudioManager?.musicVolume(): Float {
    if (this == null) return 0f
    val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun AudioManager?.setMusicVolume(fraction: Float) {
    if (this == null) return
    val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    runCatching {
        setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (fraction.coerceIn(0f, 1f) * max).toInt(),
            0,
        )
    }
}

// ---- content ------------------------------------------------------------------

@Composable
private fun MainContent(
    container: AppContainer,
    settings: Settings,
    snapshot: com.betterlyrics.app.media.PlayerSnapshot,
    artwork: android.graphics.Bitmap?,
    colors: ArtworkColors,
    permissionGranted: Boolean,
    lyricsState: LyricsState,
    demoMode: Boolean,
    document: LyricsDocument?,
    selectionMode: Boolean,
    selected: Set<Int>,
    jumpSignal: Int,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onSelectLine: (Int) -> Unit,
    onLongPressLine: (com.betterlyrics.app.lyrics.model.LyricLine) -> Unit,
    onGrantPermission: () -> Unit,
    onStartDemo: () -> Unit,
) {
    when {
        !permissionGranted && !demoMode -> PermissionGate(
            accent = colors.accent,
            onGrant = onGrantPermission,
        )

        !snapshot.hasTrack && !demoMode -> StatusMessage(
            title = "Nothing playing",
            body = "Start a song in Spotify — or any player — and the lyrics will show up here.",
            action = if (BuildConfig.DEBUG) "Preview the renderer" else null,
            accent = colors.accent,
            onAction = onStartDemo,
        )

        document != null -> {
            val positionProvider = rememberPositionProvider(snapshot, settings)
            val lyrics: @Composable (Modifier) -> Unit = { mod ->
                Column(mod) {
                    LyricsView(
                        document = document,
                        settings = settings,
                        positionMsProvider = positionProvider,
                        onSeek = { container.media.seekTo(it) },
                        selectionMode = selectionMode,
                        selectedIndices = selected,
                        onSelectLine = onSelectLine,
                        onLongPressLine = onLongPressLine,
                        jumpToActiveSignal = jumpSignal,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    UnsyncedNotice(visible = document.kind == LyricsKind.STATIC)
                }
            }

            val track = snapshot.track
            if (settings.viewMode == ViewMode.CINEMA && track != null) {
                CinemaLayout(
                    side = settings.mediaPanelSide,
                    media = { mod ->
                        MediaPanel(
                            track = track,
                            playback = snapshot.playback,
                            artwork = artwork,
                            accent = colors.accent,
                            transport = snapshot.transport,
                            showVolume = settings.showVolumeSlider,
                            volume = volume,
                            onVolumeChange = onVolumeChange,
                            onTogglePlay = { container.media.togglePlayPause() },
                            onNext = { container.media.skipNext() },
                            onPrevious = { container.media.skipPrevious() },
                            onSeek = { container.media.seekTo(it) },
                            onOpenSource = { container.media.openSourceApp() },
                            modifier = mod,
                        )
                    },
                    lyrics = lyrics,
                )
            } else {
                lyrics(Modifier.fillMaxSize())
            }
        }

        else -> when (val state = lyricsState) {
            LyricsState.Idle -> StatusMessage("Waiting for a track", null)

            LyricsState.Loading -> LoadingLyrics(accent = colors.accent)

            LyricsState.NotFound -> StatusMessage(
                title = "No lyrics found",
                body = "None of the enabled sources have this one. You can load your own " +
                    ".lrc or .ttml file with the note button.",
            )

            LyricsState.Offline -> StatusMessage(
                title = "You're offline",
                body = "Cached lyrics still work — this track just isn't cached yet.",
            )

            is LyricsState.Failed -> StatusMessage("Couldn't load lyrics", state.message)

            is LyricsState.Loaded -> Unit // handled above
        }
    }
}

/**
 * Cinema view: the album art beside the words in landscape, above them in portrait.
 *
 * Which side is [MediaPanelSide], flipped by the swap button — the same affordance Spicy
 * Lyrics' NowBar has.
 */
@Composable
private fun CinemaLayout(
    side: MediaPanelSide,
    media: @Composable (Modifier) -> Unit,
    lyrics: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                if (side == MediaPanelSide.START) {
                    media(Modifier.weight(0.42f).fillMaxHeight())
                    lyrics(Modifier.weight(0.58f).fillMaxHeight())
                } else {
                    lyrics(Modifier.weight(0.58f).fillMaxHeight())
                    media(Modifier.weight(0.42f).fillMaxHeight())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (side == MediaPanelSide.START) {
                    media(Modifier.weight(0.44f).fillMaxWidth())
                    lyrics(Modifier.weight(0.56f).fillMaxWidth())
                } else {
                    lyrics(Modifier.weight(0.56f).fillMaxWidth())
                    media(Modifier.weight(0.44f).fillMaxWidth())
                }
            }
        }
    }
}

/**
 * The floating window.
 *
 * Deliberately almost nothing: the system draws the transport buttons itself from the
 * activity's [android.app.RemoteAction]s, so all this has to do is put the lyrics — and
 * optionally the cover and title — in a very small space.
 */
@Composable
private fun PopupContent(
    document: LyricsDocument?,
    settings: Settings,
    snapshot: com.betterlyrics.app.media.PlayerSnapshot,
    artwork: android.graphics.Bitmap?,
    positionProvider: () -> Long,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Captured before the Row/Column below: inside those scopes `maxWidth` would be
        // ambiguous between the box's constraints and the layout scope.
        val boxWidth = maxWidth
        val boxHeight = maxHeight
        val wide = boxWidth > boxHeight * 1.2f
        // Follows the main window rather than deciding for itself: the popup is the same
        // screen made small, so shrinking it should not add a panel that was not there.
        // Cinema view is what shows the artwork, so that is the condition.
        val showArt = settings.popupShowArtwork &&
            settings.viewMode == ViewMode.CINEMA &&
            artwork != null

        val lyrics: @Composable (Modifier) -> Unit = { mod ->
            if (document != null) {
                LyricsView(
                    document = document,
                    settings = settings,
                    positionMsProvider = positionProvider,
                    onSeek = {},
                    compact = true,
                    modifier = mod,
                )
            } else {
                Box(mod, contentAlignment = Alignment.Center) {
                    Text(
                        text = snapshot.track?.title ?: "Nothing playing",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }

        if (!showArt) {
            lyrics(Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        if (wide) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                PopupArtwork(artwork, Modifier.padding(8.dp).size(boxHeight * 0.66f))
                lyrics(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                PopupArtwork(artwork, Modifier.padding(8.dp).size(boxWidth * 0.42f))
                lyrics(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PopupArtwork(artwork: android.graphics.Bitmap?, modifier: Modifier) {
    val art = artwork ?: return
    val image = remember(art) { art.asImageBitmap() }
    androidx.compose.foundation.Image(
        bitmap = image,
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
    )
}

// ---- chrome -------------------------------------------------------------------

@Composable
private fun ViewControls(
    sourceLabel: String?,
    providerName: String?,
    settings: Settings,
    accent: Color,
    romanizationAvailable: Boolean,
    /** False hides the translate chip: nothing here could translate this track. */
    translationAvailable: Boolean,
    hasDocument: Boolean,
    popupAvailable: Boolean,
    onToggleRomanization: () -> Unit,
    onToggleTranslation: () -> Unit,
    onToggleCinema: () -> Unit,
    onSwapSide: () -> Unit,
    onEnterPopup: () -> Unit,
    onScrollToActive: () -> Unit,
    onStartSelection: () -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        if (sourceLabel != null || providerName != null) {
            if (sourceLabel != null) {
                Text(
                    text = sourceLabel,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (providerName != null) {
                Text(
                    text = providerName,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        // Scrollable: there are more controls than fit across a phone, and hiding half of
        // them behind an overflow menu would only make them harder to reach.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (romanizationAvailable) {
                LabelChip("文A", "Toggle romanization", settings.showRomanization, accent, onToggleRomanization)
            }
            // Hidden when neither source could produce one — an English song read in
            // English has nothing to translate, and a button that cannot work is worse
            // than no button.
            if (translationAvailable) {
                ActionChip(
                    AppIcons.Translate,
                    // Which source is in use is a Settings decision; here it is on or off.
                    when (settings.translationSource) {
                        TranslationSource.OFF -> "Show translation"
                        TranslationSource.PROVIDER -> "Translation from the source — tap to hide"
                        TranslationSource.DEVICE -> "Translation on this device — tap to hide"
                    },
                    settings.translationSource != TranslationSource.OFF,
                    accent,
                    onToggleTranslation,
                )
            }
            ActionChip(
                AppIcons.Cinema,
                "Cinema view",
                settings.viewMode == ViewMode.CINEMA,
                accent,
                onToggleCinema,
            )
            if (settings.viewMode == ViewMode.CINEMA) {
                ActionChip(AppIcons.SwapSides, "Swap which side the artwork is on", false, accent, onSwapSide)
            }
            if (popupAvailable) {
                ActionChip(AppIcons.PopupWindow, "Popup lyrics", false, accent, onEnterPopup)
            }
            ActionChip(
                AppIcons.CenterFocus,
                "Scroll back to the line that's playing",
                false,
                accent,
                onScrollToActive,
            )
            if (hasDocument) {
                ActionChip(AppIcons.Copy, "Select lines to copy", false, accent, onStartSelection)
            }
            ActionChip(AppIcons.NoteAdd, "Load a lyrics file", false, accent, onImport)
            ActionChip(AppIcons.Refresh, "Look up again", false, accent, onRetry)
            ActionChip(AppIcons.Tune, "Settings", false, accent, onSettings)
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (active) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) Color.White else Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun LabelChip(
    label: String,
    description: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (active) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Color.White else Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    accent: Color,
    onCopy: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 0) "Tap lines to select" else "$count selected",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        TextButton("All", accent, onSelectAll)
        Spacer(Modifier.width(6.dp))
        TextButton(if (count == 0) "Copy all" else "Copy", accent, onCopy)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Close,
                contentDescription = "Cancel selection",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TextButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.24f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PermissionGate(accent: Color, onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Let Better Lyrics see what's playing",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Android publishes the track, artist and live playhead of whatever is " +
                "playing as a media session — that's what the lyrics sync to. Reading it " +
                "sits behind the “notification access” switch.\n\nNothing leaves the " +
                "phone except a track title and artist, and only to look lyrics up.",
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(accent.copy(alpha = 0.9f))
                .clickable(onClick = onGrant)
                .padding(horizontal = 26.dp, vertical = 13.dp),
        ) {
            Text(
                text = "Open notification access",
                color = Color.Black.copy(alpha = 0.86f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun StatusMessage(
    title: String,
    body: String?,
    action: String? = null,
    accent: Color = Color.White,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = body,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(accent.copy(alpha = 0.22f))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 20.dp, vertical = 11.dp),
            ) {
                Text(action, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/** Three dots pulsing in turn — the same motif the renderer uses for instrumentals. */
@Composable
private fun LoadingLyrics(accent: Color) {
    val transition = rememberInfiniteTransition(label = "loading")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val distance = kotlin.math.abs(phase - index).coerceAtMost(1f)
            val strength = 1f - distance
            Box(
                Modifier
                    .padding(horizontal = 5.dp)
                    .size((7 + 5 * strength).dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.35f + 0.6f * strength)),
            )
        }
    }
}

@Composable
private fun UnsyncedNotice(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Text(
            text = "Unsynced lyrics — no timings available for this track",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 4.dp),
        )
    }
}
