package com.betterlyrics.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterlyrics.app.AppContainer
import com.betterlyrics.app.settings.BackgroundStyle
import com.betterlyrics.app.settings.ControlsPosition
import com.betterlyrics.app.settings.FuriganaMode
import com.betterlyrics.app.settings.LyricsFont
import com.betterlyrics.app.settings.MediaPanelSide
import com.betterlyrics.app.settings.PopupShape
import com.betterlyrics.app.settings.Settings
import com.betterlyrics.app.settings.TextAnimationStyle
import com.betterlyrics.app.settings.ViewMode
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.betterlyrics.app.ui.components.AppIcons
import com.betterlyrics.app.ui.components.ReorderableColumn
import com.betterlyrics.app.settings.TranslationSource
import com.betterlyrics.app.settings.CacheServerMode
import com.betterlyrics.app.lyrics.LyricsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betterlyrics.app.R
import com.betterlyrics.app.update.UpdateChecker
import com.betterlyrics.app.update.Updater
import com.betterlyrics.app.settings.ArtworkSource
import com.betterlyrics.app.media.ServerSource
import com.betterlyrics.app.lyrics.provider.SpotifyWebToken

/**
 * Whether the long-form explanations are showing.
 *
 * A composition local rather than a parameter on every row: the flag is read by a dozen
 * leaf composables and threading it through each call site would drown the settings list
 * it is meant to clarify.
 */
private val LocalSettingsHelp = androidx.compose.runtime.compositionLocalOf { false }

@Composable
private fun Help(text: String) {
    if (!LocalSettingsHelp.current) return
    Text(
        text,
        color = Color.White.copy(alpha = 0.52f),
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
    )
}

private class ProviderInfo(
    val title: String,
    val description: String,
    /** Shown instead of the description when the provider is missing its credentials. */
    val needs: String? = null,
)

private val PROVIDER_INFO = mapOf(
    "local" to ProviderInfo(
        "Your own files",
        "Imported .lrc / .ttml. Always tried first and always wins.",
    ),
    "cacheserver" to ProviderInfo(
        "Cache server",
        "Your own server, holding what it has already fetched. Ranked first by default, because " +
            "an answer it already has cost nobody a request.",
        needs = "Needs a URL under Developer",
    ),
    "applemusic" to ProviderInfo(
        "Apple Music",
        "Word-by-word, with official romanizations and translations. The best data there is.",
        needs = "Needs a developer token and a music user token below",
    ),
    "amll" to ProviderInfo(
        "AMLL TTML Database",
        "Community-timed, word-by-word, public domain. Hand-made syllable timings with " +
            "readings and translations, and no account needed.",
    ),
    "spotify" to ProviderInfo(
        "Spotify",
        "The lyrics the Spotify app shows, matched to the exact track.",
        needs = "Unavailable — Spotify closed the endpoint this needed",
    ),
    "netease" to ProviderInfo(
        "NetEase Cloud Music",
        "Word-by-word plus hand-checked romanization and translation. Best for Japanese, Korean and Chinese.",
    ),
    "musixmatch" to ProviderInfo(
        "Musixmatch",
        "Word-by-word for most Western music. No account needed; a signed-in token avoids " +
            "the anonymous rate limit.",
    ),
    "lrclib" to ProviderInfo(
        "LRCLIB",
        "Open community database, no account needed.",
    ),
)

/**
 * How long to keep watching with nothing playing.
 *
 * Ten minutes by default: long enough to survive the gap between two songs, a phone call or a look
 * at something else, and short enough that an app finished with in the morning is not still resident
 * at lunchtime.
 */
private val BACKGROUND_TIMEOUTS = listOf(
    2 to "2 min",
    10 to "10 min",
    30 to "30 min",
    0 to "Never",
)

/** A short, practical list rather than every language ML Kit supports. */
private val TRANSLATION_TARGETS = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish",
    "ru" to "Russian", "tr" to "Turkish", "ar" to "Arabic", "hi" to "Hindi",
    "id" to "Indonesian", "th" to "Thai", "vi" to "Vietnamese", "sv" to "Swedish",
    "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    container: AppContainer,
    settings: Settings,
    accent: Color,
    songWriters: List<String>,
    onCopyAll: () -> Unit,
    onShowWelcome: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val store = container.settings
    val updater = container.updater
    val context = LocalContext.current
    var showCredits by remember { mutableStateOf(false) }

    // One section open at a time.
    //
    // There are a hundred-odd controls here, and a single flat list of them buries the
    // three anybody actually came for. Collapsed headings turn it back into something you
    // can read: a dozen names, and the one you want. Not remembered between openings —
    // coming back to a sheet folded exactly as you left it is worse than coming back to a
    // list, because the thing you were looking at last time is rarely the thing you want now.
    var openSection by remember { mutableStateOf<String?>(null) }
    // Off by default: the one-line description under each row is enough most of the time,
    // and the longer explanations get in the way once you know what things do.
    var showHelp by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF14141A),
        contentColor = Color.White,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSettingsHelp provides showHelp) {
        val sheetScroll = rememberScrollState()
        // Where the scrolling area is on screen, so dragging a source towards the top or bottom
        // of the sheet can scroll it. Measured on the node outside the scroll modifier, which is
        // the viewport rather than the content, so this settles once and does not change again
        // as the sheet scrolls.
        var viewport by remember { mutableStateOf(Rect.Zero) }
        Column(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { viewport = it.boundsInWindow() }
                .verticalScroll(sheetScroll)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            if (showCredits) {
                CreditsPanel(accent = accent, onBack = { showCredits = false })
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Settings",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (showHelp) accent.copy(alpha = 0.3f)
                            else Color.White.copy(alpha = 0.08f),
                        )
                        .clickable { showHelp = !showHelp },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "?",
                        color = if (showHelp) Color.White else Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                if (showHelp) "Tap ? again to hide the explanations."
                else "Tap ? to explain what each setting does.",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )

            Section(
                title = "View",
                subtitle = "Cinema, where the controls sit",
                open = openSection == "view",
                accent = accent,
                onToggle = { openSection = if (openSection == "view") null else "view" },
            ) {

                ChipGroup(
                    label = "Layout",
                    options = ViewMode.entries.map { it to it.label },
                    selected = settings.viewMode,
                    accent = accent,
                    onSelect = { store.setViewMode(it) },
                )
                Hint(
                    "Cinema puts the album art beside the words — above them on a phone held " +
                        "upright, to the side when it is turned.",
                )

                if (settings.viewMode == ViewMode.CINEMA) {
                    ChipGroup(
                        label = "Artwork on the",
                        options = MediaPanelSide.entries.map { it to it.label },
                        selected = settings.mediaPanelSide,
                        accent = accent,
                        onSelect = { store.setMediaPanelSide(it) },
                    )
                }

                ChipGroup(
                    label = "Controls",
                    options = ControlsPosition.entries.map {
                        it to if (it == ControlsPosition.TOP) "Top" else "Bottom"
                    },
                    selected = settings.controlsPosition,
                    accent = accent,
                    onSelect = { store.setControlsPosition(it) },
                )

                ToggleRow(
                    title = "Volume slider",
                    subtitle = "A volume band under the artwork and the now-playing bar",
                    checked = settings.showVolumeSlider,
                    accent = accent,
                    onCheckedChange = { store.setShowVolumeSlider(it) },
                )
                Help(
                    "Drives the phone's own music volume, not the player's mixer, so it moves with the hardware keys.",
                )

                ChipGroup(
                    label = "Look for bigger cover art",
                    options = ArtworkSource.entries.map { it to it.label },
                    selected = settings.artworkSource,
                    accent = accent,
                    onSelect = { store.setArtworkSource(it) },
                )
                Help(
                    "A media session's artwork is often 300 pixels or less — fine in a " +
                        "notification, visibly soft behind full-screen lyrics. Both of these " +
                        "are public and need no account: iTunes is one request and returns " +
                        "squares up to 1000px; the Cover Art Archive is community-run, slower, " +
                        "and reaches releases Apple does not carry. Either is only used when " +
                        "what the player gave us is too small to look at, and a match that " +
                        "does not look convincing is discarded — a wrong cover is worse than " +
                        "a soft one.",
                )

                ToggleRow(
                    title = "Artist image and full-size cover",
                    subtitle = "From Spotify if you have a token, otherwise Apple Music",
                    checked = settings.useSpotifyExtras,
                    accent = accent,
                    onCheckedChange = { store.setUseSpotifyExtras(it) },
                )
                Help(
                    "The artist's photo, the cover at full size rather than the thumbnail a " +
                        "media session publishes, and — from Spotify only — the tempo, which " +
                        "paces how fast the background drifts.\n\nSpotify goes first when " +
                        "there is a token, because it identifies the track by the id the " +
                        "player handed over and so cannot be matching the wrong song. Apple " +
                        "Music is next: it can only match on title and artist, but its " +
                        "developer token lasts months where Spotify's lasts an hour, so it is " +
                        "the one still working tomorrow. Both need a token pasted under " +
                        "Developer. With neither, the cover-art search above is used instead.",
                )

                ToggleRow(
                    title = "Compact mode",
                    subtitle = "Smaller type and tighter spacing, for split screen",
                    checked = settings.compactMode,
                    accent = accent,
                    onCheckedChange = { store.setCompactMode(it) },
                )
                Help(
                    "Meant for split screen or a small window. The floating window switches to it on its own, so you do not need this for that.",
                )
            }

            Section(
                title = "Popup lyrics",
                subtitle = "The floating window",
                open = openSection == "popup",
                accent = accent,
                onToggle = { openSection = if (openSection == "popup") null else "popup" },
            ) {

                ToggleRow(
                    title = "Popup lyrics",
                    subtitle = "Carry on in a floating window over other apps",
                    checked = settings.popupLyricsEnabled,
                    accent = accent,
                    onCheckedChange = { store.setPopupLyricsEnabled(it) },
                )

                if (settings.popupLyricsEnabled) {
                    ToggleRow(
                        title = "Shrink automatically",
                        subtitle = "Enter the window when you leave the app, the way YouTube does",
                        checked = settings.popupAutoEnter,
                        accent = accent,
                        onCheckedChange = { store.setPopupAutoEnter(it) },
                    )
                Help(
                    "On Android 12 and up the system does the shrinking itself, which is why it animates smoothly. Below that the app asks as you leave, which is a little more abrupt.",
                )
                    ChipGroup(
                        label = "Shape",
                        options = PopupShape.entries.map { it to it.label },
                        selected = settings.popupShape,
                        accent = accent,
                        onSelect = { store.setPopupShape(it) },
                    )
                    ToggleRow(
                        title = "Show the cover in the window",
                        subtitle = "Only in Cinema view · off leaves the whole window to the words",
                        checked = settings.popupShowArtwork,
                        accent = accent,
                        onCheckedChange = { store.setPopupShowArtwork(it) },
                    )
                    Help(
                        "The floating window follows the main one: it is the same screen made " +
                            "small, so it shows the cover when Cinema view does and only then. " +
                            "Shrinking the app should not add a panel that was not on screen a " +
                            "moment ago.",
                    )
                    Help(
                        "The window's background is held still rather than drifting, because it is " +
                            "on screen over whatever else you are doing for far longer than the " +
                            "app itself is. The switch for that is under Battery, with everything " +
                            "else that trades a little of the look for charge.",
                    )
                }
            }

            Section(
                title = "Lyrics display",
                subtitle = "Size, font, effects",
                open = openSection == "display",
                accent = accent,
                onToggle = { openSection = if (openSection == "display") null else "display" },
            ) {

                SliderRow(
                    label = "Text size",
                    value = settings.fontScale,
                    range = 0.7f..1.6f,
                    display = "${(settings.fontScale * 100).toInt()}%",
                    accent = accent,
                    onChange = { store.setFontScale(it) },
                )

                ChipGroup(
                    label = "Font",
                    options = LyricsFont.entries.map { it to it.label },
                    selected = settings.font,
                    accent = accent,
                    onSelect = { store.setFont(it) },
                )

                ToggleRow(
                    title = "Depth blur",
                    subtitle = "Lines further from the current one fall out of focus",
                    checked = settings.lineBlur,
                    accent = accent,
                    onCheckedChange = { store.setLineBlur(it) },
                )
                Help(
                    "Lines away from the current one are drawn as a blur of themselves, which is what gives the page depth. It is done by drawing the glyphs transparent with a shadow behind them; if that renders oddly on your device, turn it off.",
                )

                ToggleRow(
                    title = "Simple mode",
                    subtitle = "Flatter contrast and no letter-by-letter emphasis",
                    checked = settings.simpleMode,
                    accent = accent,
                    onCheckedChange = { store.setSimpleMode(it) },
                )
                Help(
                    "Drops the letter-by-letter emphasis on long syllables and flattens the contrast between sung and unsung lines. Calmer, and cheaper to draw.",
                )

                ToggleRow(
                    title = "Minimal mode",
                    subtitle = "Sung lines shrink and leave the page instead of dimming",
                    checked = settings.minimalMode,
                    accent = accent,
                    onCheckedChange = { store.setMinimalMode(it) },
                )
                Help(
                    "Only the line being sung and the ones still to come stay on screen. Good for following along, less good for reading ahead or behind.",
                )

                ChipGroup(
                    label = "Text animation",
                    options = TextAnimationStyle.entries.map { it to it.label },
                    selected = settings.textAnimationStyle,
                    accent = accent,
                    onSelect = { store.setTextAnimationStyle(it) },
                )
                Hint(settings.textAnimationStyle.description)
                Help(
                    "Calculate reads the playhead every frame, so it is always exactly right " +
                        "but only as smooth as the player's reporting. Animate starts a steady " +
                        "sweep when each syllable begins, which looks smoother but can drift a " +
                        "little across a seek — it snaps back if it drifts far.",
                )

                ToggleRow(
                    title = "Highlight the line you hold",
                    subtitle = "A tinted box appears under your finger; holding also copies the line",
                    checked = settings.lineTapHighlight,
                    accent = accent,
                    onCheckedChange = { store.setLineTapHighlight(it) },
                )
                Help(
                    "A desktop shows this when the mouse is over a line; a finger has no hover, so it appears while you hold — which is also the gesture that copies the line.",
                )

                ToggleRow(
                    title = "Duet indent",
                    subtitle = "Inset duet lines so the two voices read as separate columns",
                    checked = settings.duetLinePadding,
                    accent = accent,
                    onCheckedChange = { store.setDuetLinePadding(it) },
                )
                Help(
                    "Only affects songs whose lyrics mark two voices. Off gives every line the same small inset.",
                )

                ToggleRow(
                    title = "Show credits",
                    subtitle = "Songwriters and the lyrics source, after the last line",
                    checked = settings.showCredits,
                    accent = accent,
                    onCheckedChange = { store.setShowCredits(it) },
                )
                Help(
                    "Printed after the last line, inside the scroll rather than in the app's chrome, so it reads as the end of the song.",
                )
            }

            Section(
                title = "Background",
                subtitle = "What sits behind the words",
                open = openSection == "background",
                accent = accent,
                onToggle = { openSection = if (openSection == "background") null else "background" },
            ) {

                ChipGroup(
                    label = "Style",
                    options = BackgroundStyle.entries.map { it to it.label },
                    selected = settings.backgroundStyle,
                    accent = accent,
                    onSelect = { store.setBackgroundStyle(it) },
                )
                if (settings.backgroundStyle == BackgroundStyle.ARTIST_HEADER &&
                    !container.spotifyExtrasAvailable
                ) {
                    Hint(
                        "The artist background needs the Spotify cookie below — without it this " +
                            "falls back to the cover art.",
                    )
                }

                if (settings.backgroundStyle == BackgroundStyle.COVER_ART ||
                    settings.backgroundStyle == BackgroundStyle.AUTO
                ) {
                    SliderRow(
                        label = "Cover blur",
                        value = settings.backgroundBlur.toFloat(),
                        range = 0f..67f,
                        display = "${settings.backgroundBlur}px",
                        accent = accent,
                        onChange = { store.setBackgroundBlur(it.toInt()) },
                    )
                Help(
                    "The blur comes from how far the image is shrunk before being scaled back up, so 0 leaves the artwork nearly sharp and 67 leaves a wash of its colours.",
                )
                }
            }

            Section(
                title = "Timing",
                subtitle = "Offset, scrolling, taps",
                open = openSection == "timing",
                accent = accent,
                onToggle = { openSection = if (openSection == "timing") null else "timing" },
            ) {

                SliderRow(
                    label = "Sync offset",
                    value = settings.syncOffsetMs.toFloat(),
                    range = -3000f..3000f,
                    display = "${settings.syncOffsetMs}ms",
                    accent = accent,
                    onChange = { store.setSyncOffset((it / 10).toInt() * 10) },
                )
                Hint(
                    when {
                        settings.syncOffsetMs == 0 -> "In step with the player."
                        settings.syncOffsetMs > 0 -> "Lyrics run ${settings.syncOffsetMs}ms early."
                        else -> "Lyrics run ${-settings.syncOffsetMs}ms late."
                    } + " Bluetooth needs a negative value.",
                )
                Help(
                    "Which way to go, in terms of what you notice rather than the sign: if the " +
                        "highlight reaches a word before you hear it, go negative. If you hear a " +
                        "word before the highlight gets there, go positive.\n\nBluetooth is " +
                        "almost always the first case, and needs a negative value. The player " +
                        "reports where it is in the file, but the earbuds are 150 to 250 " +
                        "milliseconds behind that — so the lyrics are running ahead of your ears " +
                        "and need holding back.\n\nThe scrubber and the times either side of it " +
                        "are deliberately not shifted. They report where the player actually is, " +
                        "which is what you want when tapping a line to jump.",
                )
                Row(Modifier.padding(bottom = 8.dp)) {
                    StepButton("−50", accent) { store.setSyncOffset(settings.syncOffsetMs - 50) }
                    Spacer(Modifier.width(8.dp))
                    StepButton("Reset", accent) { store.setSyncOffset(0) }
                    Spacer(Modifier.width(8.dp))
                    StepButton("+50", accent) { store.setSyncOffset(settings.syncOffsetMs + 50) }
                }

                ToggleRow(
                    title = "Tap a line to jump there",
                    subtitle = "Seeks the player to that line",
                    checked = settings.tapLineToSeek,
                    accent = accent,
                    onCheckedChange = { store.setTapLineToSeek(it) },
                )
                Help(
                    "Only works on lyrics that have timings. Holding a line still copies it either way.",
                )

                SliderRow(
                    label = "Take scrolling back after",
                    value = settings.autoScrollResumeMs / 1000f,
                    range = 0.5f..6f,
                    display = "%.1fs".format(settings.autoScrollResumeMs / 1000f),
                    accent = accent,
                    onChange = { store.setAutoScrollResumeMs((it * 1000).toInt()) },
                )
                Help(
                    "After you drag the lyrics by hand, this is how long they wait before scrolling themselves again.",
                )
            }

            Section(
                title = "Players to follow",
                subtitle = "Which apps count as music",
                open = openSection == "players",
                accent = accent,
                onToggle = { openSection = if (openSection == "players") null else "players" },
            ) {

                Hint(
                    "Anything that plays audio publishes the same kind of notification a music player " +
                        "does — a title, an artist, a length — so a video, a podcast or a browser tab " +
                        "arrives looking exactly like a song, gets looked up, and fills the cache with " +
                        "tracks that do not exist. Turn one off here and it is ignored entirely.",
                )
                Help(
                    "Entirely, in the sense that matters for the battery as well as for the " +
                        "lyrics: a player switched off here does not count as something playing. It " +
                        "cannot hold the screen awake, it cannot keep the drifting background " +
                        "moving, and it cannot stop the app standing down and letting go of the " +
                        "process. A video left running for an hour is a video, not a song.",
                )

                val players = settings.seenPlayers.entries.sortedBy { it.value.lowercase() }
                if (players.isEmpty()) {
                    Hint("Nothing yet. Play something and the app that played it appears here.")
                } else {
                    for ((packageName, label) in players) {
                        ToggleRow(
                            title = label,
                            subtitle = packageName,
                            checked = packageName !in settings.ignoredPlayers,
                            accent = accent,
                            // Stored the other way round — an ignore list rather than an allow list —
                            // so a player nobody has an opinion about is followed. Anything else would
                            // mean a new music app silently doing nothing until it was found in here.
                            onCheckedChange = { store.setPlayerIgnored(packageName, !it) },
                        )
                    }
                    ActionRow(
                        title = "Forget this list",
                        subtitle = "Clears the names above; they come back as each app plays again",
                        accent = accent,
                        onClick = { store.forgetSeenPlayers() },
                    )
                }
            }

            Section(
                title = "Language",
                subtitle = "Romanization, furigana, translation",
                open = openSection == "language",
                accent = accent,
                onToggle = { openSection = if (openSection == "language") null else "language" },
            ) {

                ToggleRow(
                    title = "Romanization",
                    subtitle = "Japanese, Korean, Chinese, Cyrillic and Greek in Latin letters",
                    checked = settings.showRomanization,
                    accent = accent,
                    onCheckedChange = { store.setShowRomanization(it) },
                )
                Help(
                    "Replaces the words with their Latin transcription, per syllable, so the karaoke fill still follows what you are singing. Japanese goes through a dictionary rather than a character table, because kanji have no fixed reading.",
                )

                if (settings.showRomanization) {
                    ToggleRow(
                        title = "Drop tone marks",
                        subtitle = "`ni hao` instead of `nǐ hǎo`",
                        checked = settings.romanizationStripsDiacritics,
                        accent = accent,
                        onCheckedChange = { store.setStripDiacritics(it) },
                    )
                }

                ChipGroup(
                    label = "Furigana",
                    options = FuriganaMode.entries.map { it to it.label },
                    selected = settings.furigana,
                    accent = accent,
                    onSelect = { store.setFurigana(it) },
                )
                Hint(
                    if (settings.showRomanization) {
                        "Turn romanization off to see it: furigana is a gloss over the original " +
                            "Japanese, so the two are not shown together."
                    } else {
                        "Prints the kana reading in small type over the kanji, the way a " +
                            "songbook does. Japanese only, and the readings come from the same " +
                            "dictionary the romanization uses."
                    },
                )

                ChipGroup(
                    label = "Translation",
                    options = TranslationSource.entries.map { it to it.label },
                    selected = settings.translationSource,
                    accent = accent,
                    onSelect = { store.setTranslationSource(it) },
                )
                Hint(
                    when (settings.translationSource) {
                        TranslationSource.OFF -> "No translation under the lyrics."
                        TranslationSource.PROVIDER ->
                            "Whatever came with the lyrics. Free, instant, and written by a " +
                                "person — but in the language they chose."
                        TranslationSource.DEVICE ->
                            "Machine translation into the language you pick below. Downloads a " +
                                "model the first time."
                    },
                )
                Help(
                    "These are two different things, which is why they are two different " +
                        "choices. NetEase and the AMLL database ship human translations with the " +
                        "lyrics: nothing to download, nothing sent anywhere, available the " +
                        "instant the words are — but a Japanese song is usually translated into " +
                        "Chinese, because that is who transcribed it. On-device translation is " +
                        "ML Kit running on your phone, into the language you asked for, and it " +
                        "replaces whatever the source supplied rather than leaving you reading " +
                        "a language you did not choose. It still sends nothing anywhere; the " +
                        "cost is a one-off model download per language pair.",
                )

                if (settings.translationSource == TranslationSource.DEVICE) {
                    ChipGroup(
                        label = "Translate into",
                        options = TRANSLATION_TARGETS,
                        selected = settings.translationTarget,
                        accent = accent,
                        perRow = 4,
                        onSelect = { store.setTranslationTarget(it) },
                    )
                    ToggleRow(
                        title = "Download models on Wi-Fi only",
                        subtitle = "Each language is roughly 30 MB",
                        checked = settings.translationWifiOnly,
                        accent = accent,
                        onCheckedChange = { store.setTranslationWifiOnly(it) },
                    )
                    Help(
                        "Only affects the one-off model download, not the translating itself, " +
                            "which is offline.",
                    )
                }
            }

            Section(
                title = "Where lyrics come from",
                subtitle = "Which sources, and in what order",
                open = openSection == "sources",
                accent = accent,
                onToggle = { openSection = if (openSection == "sources") null else "sources" },
            ) {
                Hint(
                    "Every enabled source is asked at once and the best answer wins — " +
                        "word-by-word beats line-by-line. Order breaks ties. Hold a handle to " +
                        "drag a source up or down.",
                )
                Help(
                    "Asking in parallel rather than in turn is why a track resolves in one round " +
                        "trip instead of five. A source you have not given a token to is skipped " +
                        "rather than queried, so leaving it enabled costs nothing. Results are " +
                        "cached for 30 days, so each source is asked at most once per track.",
                )

                // The cache server only appears once the developer options are on. Everyone else
                // would see a row for something they have never heard of and cannot use, and its
                // ranking would mean nothing.
                val listed = settings.providerOrder.filter {
                    it != "cacheserver" || settings.developerMode
                }
                ReorderableColumn(
                    items = listed,
                    keyOf = { it },
                    scroll = sheetScroll,
                    viewportInWindow = { viewport },
                    // The rows are a filtered view of the stored order, so the new order is
                    // written back over the slots that were visible and anything hidden — a cache
                    // server with developer options off — keeps the place it had.
                    onReordered = { order ->
                        store.setProviderOrder(settings.providerOrder.withVisibleOrder(order))
                    },
                ) { id, raised, handle ->
                    val info = PROVIDER_INFO[id] ?: ProviderInfo(id, "")
                    val provider = container.providers.firstOrNull { it.id == id }
                    val configured = provider?.isConfigured ?: true
                    ProviderRow(
                        title = info.title,
                        subtitle = if (configured) info.description else (info.needs ?: info.description),
                        warn = !configured,
                        enabled = id in settings.enabledProviders,
                        raised = raised,
                        handle = handle,
                        accent = accent,
                        onToggle = { store.setProviderEnabled(id, it) },
                    )
                }

                ToggleRow(
                    title = "Look up the next track early",
                    subtitle = "Only when the player says what is queued next",
                    checked = settings.prefetchNextTrack,
                    accent = accent,
                    onCheckedChange = { store.setPrefetchNextTrack(it) },
                )
                Help(
                    "Fetches the queued track's lyrics while the current one is still playing, " +
                        "so they are on screen the moment it changes — and are there later even " +
                        "with no signal. Most players publish no queue at all (Spotify among " +
                        "them), in which case this does nothing rather than guessing. It always " +
                        "waits for the track on screen to resolve first, and a track it fails to " +
                        "find is looked up again properly when it actually plays.",
                )
            }

            Section(
                title = "Tokens and endpoints",
                subtitle = "All optional, all stay on this device",
                open = openSection == "tokens",
                accent = accent,
                onToggle = { openSection = if (openSection == "tokens") null else "tokens" },
            ) {
                Hint(
                    "All optional. Every field here stays on this device, and each provider " +
                        "simply stays quiet without what it needs.",
                )
                Help(
                    "These are your own credentials, read from a browser session you are already " +
                        "signed in to — there is no app account and no server in between. They " +
                        "are stored in this app's private preferences and sent only to the " +
                        "service they belong to.",
                )

                SecretField(
                    label = "Spotify sp_dc cookie",
                    help = "No longer enough on its own. Turning the cookie into an access " +
                        "token meant an endpoint Spotify has closed — one address is blocked " +
                        "outright, the other refuses every caller with or without a cookie. " +
                        "The endpoints that token opened are still there, though, so paste " +
                        "the token itself under Developer instead. This field stays in case " +
                        "the mint reopens.",
                    value = settings.spDcCookie.orEmpty(),
                    accent = accent,
                    onChange = { store.updateSpDcCookie(it) },
                )

                SecretField(
                    label = "Apple Music developer token",
                    help = "A JWT, valid for months rather than an hour — which makes it the " +
                        "token worth having. Open music.apple.com, developer tools, Network " +
                        "tab, and copy the Authorization header off any request to " +
                        "amp-api.music.apple.com — the whole header or just the token, either " +
                        "works.\n\nOn its own this gets the artist image " +
                        "and the full-size cover art, no subscription needed. Syllable-level " +
                        "lyrics need the music user token below as well, and a subscription.",
                    value = settings.appleDeveloperToken.orEmpty(),
                    accent = accent,
                    onChange = { store.updateAppleDeveloperToken(it) },
                )

                SecretField(
                    label = "Apple Music user token",
                    help = "The `media-user-token` cookie from a signed-in music.apple.com " +
                        "session. Needs an active subscription.",
                    value = settings.appleMusicUserToken.orEmpty(),
                    accent = accent,
                    onChange = { store.updateAppleMusicUserToken(it) },
                )

                SecretField(
                    label = "Apple Music storefront",
                    help = "Two-letter country code for the catalogue to search, e.g. us, gb, jp.",
                    value = settings.appleStorefront,
                    accent = accent,
                    onChange = { store.updateAppleStorefront(it) },
                )

                SecretField(
                    label = "Musixmatch user token",
                    help = "Optional — the app mints an anonymous token for itself, which is " +
                        "rate-limited per network. A signed-in one is not. The desktop app " +
                        "this used to come from is discontinued, so take it from the site: " +
                        "sign in at musixmatch.com, then copy the whole musixmatchUserToken " +
                        "cookie from your browser's developer tools and paste it here. The " +
                        "cookie holds one token per Musixmatch client and only some of them " +
                        "work, so paste the whole thing rather than picking one — it finds the " +
                        "right one. A bare token works too if you know which client it belongs " +
                        "to. Anything this endpoint rejects is ignored rather than allowed to " +
                        "break the source.",
                    value = settings.musixmatchUserToken.orEmpty(),
                    accent = accent,
                    onChange = { store.updateMusixmatchUserToken(it) },
                )

                SecretField(
                    label = "AMLL TTML instance",
                    help = "The community lyrics index. The default is the project's own server, " +
                        "run by volunteers — if you use it heavily, run your own copy of " +
                        "amll-ttml-api and point this at it.",
                    value = settings.amllBaseUrl,
                    accent = accent,
                    onChange = { store.updateAmllBaseUrl(it) },
                )

                SecretField(
                    label = "LRCLIB instance",
                    help = "Point at your own mirror if you run one.",
                    value = settings.lrcLibBaseUrl,
                    accent = accent,
                    onChange = { store.updateLrcLibBaseUrl(it) },
                )

                SecretField(
                    label = "NetEase instance",
                    help = "Point at a self-hosted NetEase API if the public one is blocked for you.",
                    value = settings.neteaseBaseUrl,
                    accent = accent,
                    onChange = { store.updateNeteaseBaseUrl(it) },
                )

                SecretField(
                    label = "NetEase cookie",
                    help = "Optional. Raises the per-IP limits and unlocks some regional catalogues.",
                    value = settings.neteaseCookie.orEmpty(),
                    accent = accent,
                    onChange = { store.updateNeteaseCookie(it) },
                )
            }

            Section(
                title = "This track",
                subtitle = "Copy, import, look it up again",
                open = openSection == "track",
                accent = accent,
                onToggle = { openSection = if (openSection == "track") null else "track" },
            ) {

                ActionRow(
                    title = "Copy all the lyrics",
                    subtitle = "Puts the whole song on the clipboard",
                    accent = accent,
                    onClick = onCopyAll,
                )

                // Only offered when there is something to remove: importing the wrong file
                // otherwise leaves no way back, because a local file outranks every provider.
                var localRemoved by remember { mutableStateOf(false) }
                val hasLocal by produceState(initialValue = false, localRemoved) {
                    value = !localRemoved && container.lyrics.hasLocal()
                }
                if (hasLocal) {
                    ActionRow(
                        title = "Forget the imported lyrics file",
                        subtitle = "Goes back to looking this track up online",
                        accent = accent,
                        onClick = {
                            scope.launch {
                                container.lyrics.removeLocal()
                                localRemoved = true
                            }
                        },
                    )
                }

                ActionRow(
                    title = "Look this track up again",
                    subtitle = "Drops it from the cache and asks every source afresh",
                    accent = accent,
                    onClick = { container.lyrics.retry() },
                )
            }

            Section(
                title = "Battery",
                subtitle = "The screen, standing down, battery saver",
                open = openSection == "battery",
                accent = accent,
                onToggle = { openSection = if (openSection == "battery") null else "battery" },
            ) {
                // Everything that trades a little of the app for charge lives here, wherever the
                // thing it affects is configured. Splitting them across the sections they belong to
                // — the screen under View, the window's background under Popup — meant nobody could
                // see what the app was costing them, or find the three switches that change it.
                Hint("Every setting that trades something for battery, in one place.")

                ToggleRow(
                    title = "Keep the screen on while playing",
                    subtitle = "Only while something is playing — it times out normally otherwise",
                    checked = settings.keepScreenOn,
                    accent = accent,
                    onCheckedChange = { store.setKeepScreenOn(it) },
                )
                Help(
                    "The screen is the most expensive thing on a phone by a wide margin, so this " +
                        "is tied to playback rather than to the app being open. Pausing, or " +
                        "reaching the end of a track, lets it time out as usual — and a player you " +
                        "have switched off under Players to follow does not count as playing.",
                )

                ToggleRow(
                    title = "Hold the background still in the floating window",
                    subtitle = "The blurred cover instead of the drifting one",
                    checked = settings.popupStillBackground,
                    accent = accent,
                    onCheckedChange = { store.setPopupStillBackground(it) },
                )
                Help(
                    "The window is what stays on screen over whatever else you are doing, " +
                        "sometimes for an hour, and the drifting background is the most expensive " +
                        "thing this app draws. Off keeps it moving in there too.",
                )
                Help(
                    "Two things need no setting because they cost nothing: the drift stops on its " +
                        "own whenever the music is paused, and so does a title too long to fit.",
                )

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                Spacer(Modifier.height(10.dp))

                ChipGroup(
                    label = "Stop watching after",
                    options = BACKGROUND_TIMEOUTS,
                    selected = settings.backgroundTimeoutMinutes,
                    accent = accent,
                    perRow = 4,
                    onSelect = { store.setBackgroundTimeoutMinutes(it) },
                )
                Hint(
                    when (val minutes = settings.backgroundTimeoutMinutes) {
                        0 -> "Never stops. Watches for a track until you turn the permission off."
                        else -> "After $minutes minutes with nothing playing and the app off " +
                            "screen, it lets go and stops using power."
                    },
                )
                Help(
                    "Reading what is playing needs notification access, and an enabled " +
                        "notification listener is a bound service: Android keeps this app's " +
                        "process resident and hands it every notification on the device — a " +
                        "wake-up for each one, from every app, whether or not any music is " +
                        "playing. That is why closing the app from the recent-apps screen did not " +
                        "appear to close it, and why it could warm the phone hours " +
                        "later.\n\nWhen the timer runs out the app gives that binding back. " +
                        "Nothing then keeps the process alive, so the system reclaims it, and " +
                        "there is nothing left running to wake up.\n\nThe cost, plainly: while " +
                        "stopped it cannot notice a track starting, so lyrics will not be waiting " +
                        "for you. Opening the app starts it watching again immediately. Music " +
                        "already playing always keeps it awake, however long the " +
                        "timer.\n\n\"Playing\" means a player you follow. One switched off under " +
                        "Players to follow is not watched and does not hold this open — a video " +
                        "left running for an hour cannot keep the app awake.",
                )

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                Spacer(Modifier.height(10.dp))

                val saving by container.saving.collectAsStateWithLifecycle()
                ToggleRow(
                    title = "Follow the system battery saver",
                    subtitle = if (saving.any) {
                        "Battery saver is on now, and these are in force"
                    } else {
                        "Ease off while the phone is in battery saver"
                    },
                    checked = settings.followBatterySaver,
                    accent = accent,
                    onCheckedChange = { store.setFollowBatterySaver(it) },
                )
                Help(
                    "Nothing here changes anything until the phone puts itself in battery saver — " +
                        "and then only what you have ticked. Android holds its own animations still " +
                        "in that mode for the same reason; an app meant to be left on screen has " +
                        "more to give back than most.",
                )

                // Each measure separately, because each one costs something different. Shown only
                // while they can do anything: three switches that are all no-ops explain nothing.
                AnimatedVisibility(visible = settings.followBatterySaver) {
                    Column(Modifier.fillMaxWidth()) {
                        ToggleRow(
                            title = "Hold the background still",
                            subtitle = "The drifting cover stops moving",
                            checked = settings.saverStillBackground,
                            accent = accent,
                            onCheckedChange = { store.setSaverStillBackground(it) },
                        )
                        Help(
                            "The biggest saving of the three by a distance, and the least missed: " +
                                "the moving background is three full-screen layers redrawn thirty " +
                                "times a second, and what replaces it is the same cover, blurred " +
                                "and still.",
                        )
                        ToggleRow(
                            title = "Let the screen time out",
                            subtitle = "Even while the music is playing",
                            checked = settings.saverReleaseScreen,
                            accent = accent,
                            onCheckedChange = { store.setSaverReleaseScreen(it) },
                        )
                        Help(
                            "Off by default, alone among these. The screen is by far the most " +
                                "expensive thing on a phone, so this saves the most — and it is " +
                                "also the one that stops the app doing its job, since the lyrics " +
                                "go dark in the middle of the song. Only worth it if you would " +
                                "rather have the battery.",
                        )
                        ToggleRow(
                            title = "Skip the early next-track lookup",
                            subtitle = "Fetch lyrics when the track starts instead",
                            checked = settings.saverSkipPrefetch,
                            accent = accent,
                            onCheckedChange = { store.setSaverSkipPrefetch(it) },
                        )
                        Help(
                            "Costs one request and a little parsing per track, and only does " +
                                "anything at all for a player that publishes its queue. The lyrics " +
                                "still arrive when the track does; they are just not already there.",
                        )
                    }
                }
            }

            Section(
                title = "Storage",
                subtitle = "The lyrics cache",
                open = openSection == "storage",
                accent = accent,
                onToggle = { openSection = if (openSection == "storage") null else "storage" },
            ) {

                var cacheCleared by remember { mutableStateOf(false) }
                val cacheSize by produceState(initialValue = -1L, cacheCleared) {
                    value = container.lyricsCacheSizeBytes()
                }
                ActionRow(
                    title = if (cacheCleared) "Cache cleared" else "Clear the lyrics cache",
                    subtitle = when {
                        cacheCleared -> "Forces a fresh lookup for every track"
                        cacheSize < 0 -> "Forces a fresh lookup for every track"
                        else -> "%.1f MB stored · forces a fresh lookup for every track"
                            .format(cacheSize / 1_048_576f)
                    },
                    accent = accent,
                    onClick = {
                        scope.launch {
                            container.lyrics.clearCache()
                            cacheCleared = true
                        }
                    },
                )
            }

            Section(
                title = "About",
                subtitle = "Version, updates, credits",
                open = openSection == "about",
                accent = accent,
                onToggle = { openSection = if (openSection == "about") null else "about" },
            ) {

                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_on_dark),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Better Lyrics",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Version ${updater.currentVersion} " +
                                "(build ${updater.currentVersionCode})" +
                                if (!updater.canUpdateInPlace) " · debug" else "",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                        )
                    }
                }

                Text(
                    "Word-by-word lyrics for whatever your phone is playing. A port of " +
                        "Spicy Lyrics' renderer to Android, under the AGPL-3.0.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                // ---- updates ------------------------------------------------
                ToggleRow(
                    title = "Check for updates automatically",
                    subtitle = "On launch, at most every few hours",
                    checked = settings.autoUpdateCheck,
                    accent = accent,
                    onCheckedChange = { store.setAutoUpdateCheck(it) },
                )
                Help(
                    "There is no app store here, so nothing else will ever mention that a fix " +
                        "exists. This asks GitHub what the newest release is — one small " +
                        "request, no account — and downloads nothing until you say so. " +
                        "Installing goes through Android's own confirmation screen, and " +
                        "Android refuses an APK that is not signed with the same key as the " +
                        "copy you already have.",
                )

                val updateState by updater.state.collectAsStateWithLifecycle()
                ActionRow(
                    title = when (val state = updateState) {
                        is Updater.State.Checking -> "Checking…"
                        is Updater.State.Available -> "Update to ${state.release.versionName}"
                        is Updater.State.Downloading -> when {
                            state.fraction < 0f -> "Downloading…"
                            else -> "Downloading — ${(state.fraction * 100).toInt()}%"
                        }
                        is Updater.State.ReadyToInstall -> "Ready to install"
                        is Updater.State.UpToDate -> "You are up to date"
                        is Updater.State.Failed -> "Check for updates"
                        Updater.State.Idle -> "Check for updates"
                    },
                    subtitle = when (val state = updateState) {
                        is Updater.State.Available ->
                            "You have ${updater.currentVersion} · tap to download and install"
                        is Updater.State.Failed -> state.message
                        is Updater.State.UpToDate -> "${updater.currentVersion} is the newest release"
                        else -> "Asks GitHub for the newest release"
                    },
                    accent = accent,
                    onClick = {
                        scope.launch {
                            when (val state = updateState) {
                                is Updater.State.Available ->
                                    updater.downloadAndInstall(state.release)

                                is Updater.State.Downloading -> Unit
                                else -> updater.check(automatic = false)
                            }
                        }
                    },
                )

                if (!updater.canUpdateInPlace) {
                    Hint(
                        "This is a debug build, which installs under a different name — a " +
                            "release cannot replace it, so updates are not offered.",
                    )
                }

                (updateState as? Updater.State.Available)?.let { available ->
                    ActionRow(
                        title = "Skip ${available.release.versionName}",
                        subtitle = "Stops asking until a later release appears",
                        accent = accent,
                        onClick = { updater.skip(available.release) },
                    )
                }

                // ---- links --------------------------------------------------
                ActionRow(
                    title = "Source code and releases",
                    subtitle = UpdateChecker.REPOSITORY,
                    accent = accent,
                    onClick = { openUrl(context, UpdateChecker.RELEASES_URL) },
                )

                ActionRow(
                    title = "Report a problem",
                    subtitle = "Issues on GitHub",
                    accent = accent,
                    onClick = {
                        openUrl(context, "https://github.com/${UpdateChecker.REPOSITORY}/issues")
                    },
                )

                ActionRow(
                    title = "Read the welcome guide again",
                    subtitle = "What is required, what is optional, and what each token adds",
                    accent = accent,
                    onClick = onShowWelcome,
                )

                ActionRow(
                    title = "Credits and licences",
                    subtitle = "Built on Spicy Lyrics, and who else made this possible",
                    accent = accent,
                    onClick = { showCredits = true },
                )

                if (songWriters.isNotEmpty()) {
                    Text(
                        text = "This track was written by ${songWriters.joinToString(", ")}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            Section(
                title = "Developer",
                subtitle = "For testing a server of your own",
                open = openSection == "developer",
                accent = accent,
                onToggle = { openSection = if (openSection == "developer") null else "developer" },
            ) {

                ToggleRow(
                    title = "Developer options",
                    subtitle = "For testing a lyrics server of your own",
                    checked = settings.developerMode,
                    accent = accent,
                    onCheckedChange = { store.setDeveloperMode(it) },
                )
                Help(
                    "Nothing here is needed to use the app, and with this off none of it is " +
                        "reachable — a server URL left in preferences stops being used the moment " +
                        "the switch goes off, rather than quietly answering.",
                )

                if (settings.developerMode) {
                    SecretField(
                        label = "Spotify web access token",
                        help = "Brings back Spotify's own lyrics, the artist image, the " +
                            "full-size cover and the tempo — for about an hour, then it " +
                            "expires and wants replacing.\n\nSpotify closed the endpoint that " +
                            "turned a cookie into a token, but not the endpoints that token " +
                            "opens: they answer \u201c401, bring a token\u201d rather than " +
                            "\u201cno\u201d. So bring the token. Open open.spotify.com signed " +
                            "in, developer tools, Network tab, play something, click any " +
                            "request to spclient.wg.spotify.com, and copy the Authorization " +
                            "header. Paste the whole thing — the \u201cBearer \u201d prefix is " +
                            "stripped for you.\n\nIt is here rather than above because " +
                            "something that lasts an hour is not a setting, it is an errand.",
                        value = settings.spotifyWebToken.orEmpty(),
                        accent = accent,
                        onChange = { store.updateSpotifyWebToken(it) },
                    )

                    if (!settings.spotifyWebToken.isNullOrBlank()) {
                        val spotify = container.providers.firstOrNull { it.id == "spotify" }
                        Hint(
                            spotify?.unavailableReason
                                ?: "Accepted — Spotify's lyrics, artist image, cover and tempo " +
                                    "are available until it expires.",
                        )
                    }

                    ToggleRow(
                        title = "Renew that token automatically",
                        subtitle = "Uses your sp_dc cookie in a hidden WebView. No server needed",
                        checked = settings.spotifyBrowserToken,
                        accent = accent,
                        onCheckedChange = { store.setSpotifyBrowserToken(it) },
                    )
                    val harvest by SpotifyWebToken.harvest.collectAsStateWithLifecycle()
                    Hint(
                        when {
                            !settings.spotifyBrowserToken ->
                                "Off, so the token above stays an errand — about one an hour."
                            settings.spDcCookie.isNullOrBlank() ->
                                "On, but there is no sp_dc cookie to use. Add one above."
                            harvest.running -> "Asking the player for a token…"
                            harvest.detail != null -> harvest.detail!!
                            else ->
                                "On. Renew now to check the cookie works, or wait for the next " +
                                    "time Spotify is asked."
                        },
                    )

                    if (settings.spotifyBrowserToken && !settings.spDcCookie.isNullOrBlank()) {
                        val scope = rememberCoroutineScope()
                        ActionRow(
                            title = if (harvest.running) "Renewing…" else "Renew now",
                            subtitle = "Loads the player and reads the token it is given",
                            accent = accent,
                            onClick = {
                                if (!harvest.running) {
                                    scope.launch { SpotifyWebToken.renewNow(container.settings) }
                                }
                            },
                        )

                        // Shown rather than hidden: the point of pressing the button is to find out
                        // whether it worked, and a masked field cannot tell you that.
                        harvest.token?.let { token ->
                            // Recomputed on a ticker rather than once: the sheet can sit open for
                            // longer than the token lives, and a number frozen at the moment it was
                            // drawn is the one thing a countdown must not be.
                            val expiresAt = harvest.expiresAt
                            val remaining by produceState(
                                expiresAt?.minus(System.currentTimeMillis()),
                                expiresAt,
                            ) {
                                while (expiresAt != null) {
                                    value = expiresAt - System.currentTimeMillis()
                                    delay(15_000)
                                }
                            }
                            val countdown = remaining?.let { left ->
                                when {
                                    left <= 0L -> "\n\nExpired — renew for another."
                                    left < 60_000L -> "\n\nExpires in under a minute."
                                    else -> "\n\nExpires in ${left / 60_000} min."
                                }
                            }
                            SecretField(
                                label = "The token it fetched",
                                help = "Read-only, and kept in memory rather than in the box above " +
                                    "— a token pasted there is treated as your choice and is never " +
                                    "replaced, which would stop the renewal that just produced " +
                                    "this one. Copy it if you want it elsewhere." +
                                    (countdown ?: ""),
                                value = token,
                                accent = accent,
                                onChange = {},
                                readOnly = true,
                            )
                        }
                    }
                    Help(
                        "Spotify closed the endpoint that traded a cookie for a token, so the " +
                            "only thing that still mints one is the player itself — and " +
                            "Android ships a Chromium to run it in. This loads open.spotify.com " +
                            "in a WebView with your cookie and reads the token the player is " +
                            "given. Nothing is drawn on screen, and the view is destroyed as soon " +
                            "as a token arrives.\n\nWhich is a different thing from reproducing " +
                            "the signature the player signs that request with. That would be " +
                            "defeating a check with a lifted secret, and this app does not do it. " +
                            "Here the player signs its own request, as itself, with your cookie — " +
                            "the token is one your own browser would have received.\n\nIt is " +
                            "behind these developer options because it is still automated access " +
                            "to a service whose terms discourage it. That is a call for whoever " +
                            "runs this build, not something to ship switched on.",
                    )

                    SecretField(
                        label = "Cache server URL",
                        help = "A server of your own that sits in front of the free sources and " +
                            "remembers what it fetched. Leave empty to not use one. Sent as " +
                            "GET {url}/v1/lyrics?title=&artist=&album=&durationMs=&spotifyId= — " +
                            "TTML, LRC, or JSON wrapping either is accepted.",
                        value = settings.cacheServerUrl.orEmpty(),
                        accent = accent,
                        onChange = { store.updateCacheServerUrl(it) },
                    )

                    SecretField(
                        label = "Cache server key",
                        help = "Only needed if the server is not on your own network. A server " +
                            "holding an Apple Music token should not answer strangers, so one " +
                            "reachable from further away will want a key — it prints one when it " +
                            "starts. Leave empty for a server on your Wi-Fi, which should let a " +
                            "lookup through without it.",
                        value = settings.cacheServerKey.orEmpty(),
                        accent = accent,
                        onChange = { store.updateCacheServerKey(it) },
                    )

                    ChipGroup(
                        label = "How to use it",
                        options = CacheServerMode.entries.map { it to it.label },
                        selected = settings.cacheServerMode,
                        accent = accent,
                        onSelect = { store.setCacheServerMode(it) },
                    )
                    Hint(
                        when (settings.cacheServerMode) {
                            CacheServerMode.PARALLEL ->
                                "Asked alongside every source you have enabled, and ranked among " +
                                    "them in Where lyrics come from. The best answer still wins, " +
                                    "so the app keeps working whatever the server does — and the " +
                                    "server sees every track you play."
                            CacheServerMode.ONLY ->
                                "The only source asked, whatever its ranking says — there is " +
                                    "nothing else to rank it against. Nothing falls back, so a " +
                                    "track with no lyrics means the server could not answer it."
                        },
                    )
                    Help(
                        "Two modes because they answer different questions. Alongside is for " +
                            "filling the cache while the server is still being written: real " +
                            "traffic reaches it, and a gap in it costs you nothing because the " +
                            "ordinary sources are answering too. Only is for testing the server " +
                            "itself — when nothing else can answer, what it is missing becomes " +
                            "visible. Your own imported files still win in either mode, and " +
                            "results are still cached on the phone, so use \u201cLook this track " +
                            "up again\u201d above when you want to force a fresh request.",
                    )

                    ToggleRow(
                        title = "Ask the server for artwork and tempo",
                        subtitle = "Not just the words",
                        checked = settings.cacheServerExtras,
                        accent = accent,
                        onCheckedChange = { store.setCacheServerExtras(it) },
                    )
                    Help(
                        "The reason to want this: a Spotify token lasts an hour and an Apple one " +
                            "a few months, but a cover URL, an ISRC and a tempo, once known, are " +
                            "true forever. Your server collects them for itself every time it " +
                            "looks a track up, so the tokens live on that one machine rather " +
                            "than on every phone — and this asks for what it has.\n\nNothing " +
                            "is sent from here but the track's title and artist. The phone has " +
                            "nothing to contribute and no credential to hand over; asking is " +
                            "all it does. Tempo comes from Spotify alone, so a server that " +
                            "holds it is the only way to have it without a live token of your " +
                            "own.\n\nUsed only when no token on this phone can answer — a live " +
                            "token is about the track playing now, where the server is a record " +
                            "of one that matched before.",
                    )

                    if (settings.cacheServerUrl.isNullOrBlank()) {
                        Hint("No URL set, so the cache server is not being asked.")
                    }

                    // ---- what each source actually said --------------------------
                    var probe by remember { mutableStateOf<List<LyricsRepository.SourceReport>?>(null) }
                    var probing by remember { mutableStateOf(false) }

                    ActionRow(
                        title = if (probing) "Asking every source…" else "Test the sources",
                        subtitle = "Asks each one about this track and reports what came back",
                        accent = accent,
                        onClick = {
                            if (!probing) {
                                scope.launch {
                                    probing = true
                                    probe = runCatching { container.lyrics.diagnose() }.getOrNull()
                                    probing = false
                                }
                            }
                        },
                    )
                    Help(
                        "A source that is switched off, one that cannot reach its endpoint, and " +
                            "one that reached it and found nothing all look identical from the " +
                            "lyrics screen — which makes \u201conly some of them work\u201d " +
                            "impossible to act on. This asks each one directly, ignoring the " +
                            "cache, and prints what it said.",
                    )

                        // ---- and what the server says about its own ------------------
                    var serverProbe by remember { mutableStateOf<List<ServerSource>?>(null) }
                    var serverProbing by remember { mutableStateOf(false) }
                    var serverFailed by remember { mutableStateOf(false) }

                    ActionRow(
                        title = if (serverProbing) "Asking the server…" else "Test the server",
                        subtitle = "Asks your server which of its own sources and tokens work",
                        accent = accent,
                        onClick = {
                            if (!serverProbing) {
                                scope.launch {
                                    serverProbing = true
                                    serverFailed = false
                                    val result = runCatching {
                                        container.cacheServerStatus()
                                    }.getOrNull()
                                    serverProbe = result
                                    serverFailed = result == null
                                    serverProbing = false
                                }
                            }
                        },
                    )
                    Help(
                        "The test above cannot answer this one. Pointing the app at a server puts " +
                            "every source behind one hop, and \u201cthe server returned no " +
                            "lyrics\u201d covers a source switched off, a token that expired last " +
                            "week, and a track nobody has transcribed — which need telling apart. " +
                            "This asks the server to test each of its own sources and report back. " +
                            "No token comes back, only whether one works.",
                    )

                    if (serverFailed) {
                        Hint(
                            "No answer. Check the URL and, if the server is not on this network, " +
                                "the key.",
                        )
                    }

                    serverProbe?.forEach { source ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                source.name,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(112.dp),
                            )
                            Text(
                                buildString {
                                    append(if (source.ok) "✓ " else "· ")
                                    append(source.detail.ifBlank { if (source.ok) "working" else "no" })
                                    source.ms?.let { append(" (").append(it).append("ms)") }
                                },
                                color = Color.White.copy(
                                    alpha = if (source.ok) 0.7f else 0.5f,
                                ),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    probe?.forEach { report ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                report.name,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(112.dp),
                            )
                            Text(
                                report.outcome,
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
        }
    }

    LaunchedEffect(Unit) { sheetState.expand() }
}

/**
 * Attribution, in the app rather than only in the repository.
 *
 * This app is a port of somebody else's work, under a licence that asks to be
 * acknowledged; the people who read that acknowledgement are the people using it.
 */
@Composable
private fun CreditsPanel(accent: Color, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Close,
                contentDescription = "Back to settings",
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("Credits and licences", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }

    Credit(
        title = "Spicy Lyrics — Spikerko",
        body = "This app is a port of Spicy Lyrics: its look, its animation curves, its " +
            "lyric model and its TTML dialect. Spicy Lyrics is licensed AGPL-3.0, so " +
            "Better Lyrics is too.",
        link = "github.com/Spikerko/spicy-lyrics",
        accent = accent,
    )
    Credit(
        title = "spr — Fraktality (MIT)",
        body = "The analytic spring that drives every scale, lift and glow in the renderer.",
        link = "github.com/Fraktality/spr",
        accent = accent,
    )
    Credit(
        title = "cubic-spline — Morgan Herlocker (MIT)",
        body = "The natural cubic spline the animation curves are evaluated with.",
        link = "github.com/morganherlocker/cubic-spline",
        accent = accent,
    )
    Credit(
        title = "Beautiful Lyrics — surfbryce",
        body = "Prior art and a reference point. No code from it is used — it carries no " +
            "licence grant, so it was read, not borrowed from.",
        link = "github.com/surfbryce/beautiful-lyrics",
        accent = accent,
    )
    Credit(
        title = "Kuromoji — Atilika (Apache-2.0)",
        body = "Japanese morphological analysis. The only reason kanji get the right reading.",
        link = "github.com/atilika/kuromoji",
        accent = accent,
    )
    Credit(
        title = "ML Kit — Google",
        body = "On-device translation, so the lyrics never leave the phone to be translated.",
        link = "developers.google.com/ml-kit",
        accent = accent,
    )
    Credit(
        title = "AMLL TTML Database — amll-dev and its contributors (CC0 1.0)",
        body = "The word-by-word lyrics this app shows without any account. Every file in it " +
            "was timed by hand and dedicated to the public domain; the contributor who made " +
            "the one you are reading is named under the last line.",
        link = "https://github.com/amll-dev/amll-ttml-db",
        accent = accent,
    )
    Credit(
        title = "OkHttp — Square (Apache-2.0), AndroidX and Kotlin (Apache-2.0)",
        body = "Networking, UI and language runtime.",
        link = null,
        accent = accent,
    )
    Credit(
        title = "Lyrics sources",
        body = "The AMLL TTML Database, LRCLIB, NetEase Cloud Music, Musixmatch, Spotify " +
            "and Apple Music. None are affiliated with this app. Lyrics belong to their " +
            "writers and publishers; nothing is stored anywhere but this device's own cache.",
        link = null,
        accent = accent,
    )
}

@Composable
private fun Credit(title: String, body: String, link: String?, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (link != null) {
            Text(
                link,
                color = accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Lay a reordered list of visible ids back over the full stored order.
 *
 * The rows on screen are a filtered view — a cache server with developer options off sits in the
 * stored order with no row of its own — so a visible position is not a stored one, and writing
 * the visible list back as-is would delete whatever it does not show. Instead each slot that held
 * a visible id takes the next id the user's new order calls for, and anything hidden stays exactly
 * where it was.
 *
 * Refuses anything that is not a rearrangement of the same ids, so a filtered list that has since
 * changed underneath cannot rewrite the order into something the user did not ask for.
 */
internal fun List<String>.withVisibleOrder(visible: List<String>): List<String> {
    val ids = visible.toSet()
    if (ids.size != visible.size) return this
    if (count { it in ids } != visible.size) return this
    val next = visible.iterator()
    return map { if (it in ids) next.next() else it }
}

/**
 * Open a link in whatever the user browses with.
 *
 * Wrapped rather than called directly: a device with no browser at all throws, and an About
 * screen is not worth crashing over.
 */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// ---- building blocks ----------------------------------------------------------

/**
 * A collapsible group of settings.
 *
 * The header is always visible and says what is inside; the contents appear when it is
 * tapped. Only one is open at a time, so the sheet is never longer than one section plus a
 * short list of names.
 */
@Composable
private fun Section(
    title: String,
    subtitle: String,
    open: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (open) accent else Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            // A chevron drawn as a rotation of one vector, so open and closed cannot drift
            // apart the way two separate icons would.
            val rotation by animateFloatAsState(
                targetValue = if (open) 90f else 0f,
                label = "sectionChevron",
            )
            Icon(
                AppIcons.ChevronRight,
                contentDescription = if (open) "Collapse" else "Expand",
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) { content() }
        }
    }
}


@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = Color.White.copy(alpha = 0.08f),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent.copy(alpha = 0.6f),
                checkedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    accent: Color,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(display, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.16f),
            ),
        )
    }
}

/** A labelled row of choices — the settings equivalent of a radio group. */
@Composable
private fun <T> ChipGroup(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    accent: Color,
    perRow: Int = 3,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        // Two options were being squeezed into two thirds of the row and padded with a
        // spacer, which is what cut "Right / bottom" and "Only the cache server" in half.
        // A group narrower than a full row gets the whole row instead.
        val columns = minOf(perRow, options.size).coerceAtLeast(1)
        options.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (value, text) ->
                    ChoiceChip(
                        text = text,
                        selected = value == selected,
                        accent = accent,
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            // Two lines, wrapped and centred, rather than one silently cut off. A label
            // that does not fit should get taller, not shorter — a chip reading "From the"
            // says nothing about what it does.
            maxLines = 2,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun StepButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.22f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProviderRow(
    title: String,
    subtitle: String,
    warn: Boolean,
    enabled: Boolean,
    /** True while this row is the one being dragged. */
    raised: Boolean,
    /** Attached to the grab handle; hold it to drag the row. */
    handle: Modifier,
    accent: Color,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left of the name rather than right of the switch: a handle is for the whole row, and
        // putting it where the row starts keeps every one of them on the same vertical line
        // however long the description underneath runs.
        Box(
            handle.size(38.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.DragHandle,
                contentDescription = "Reorder $title",
                tint = Color.White.copy(alpha = if (raised) 0.9f else 0.35f),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    color = if (warn && enabled) {
                        accent.copy(alpha = 0.85f)
                    } else {
                        Color.White.copy(alpha = 0.5f)
                    },
                    fontSize = 12.sp,
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent.copy(alpha = 0.6f),
                checkedThumbColor = Color.White,
            ),
        )
    }
}

/** A single-line text field for a token, cookie or endpoint. */
@Composable
private fun SecretField(
    label: String,
    help: String,
    value: String,
    accent: Color,
    onChange: (String) -> Unit,
    /** For a value the app produced rather than one the user types. Still selectable, to copy. */
    readOnly: Boolean = false,
) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Text(
            text = help,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it.trim()
                    onChange(text)
                },
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = SolidColor(accent),
                singleLine = true,
                readOnly = readOnly,
                modifier = Modifier.fillMaxWidth(),
            )
            if (text.isEmpty()) {
                Text("Not set", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
    }
}
