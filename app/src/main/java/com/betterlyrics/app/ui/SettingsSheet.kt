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
import com.betterlyrics.app.ui.components.AppIcons
import kotlinx.coroutines.launch

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
    "applemusic" to ProviderInfo(
        "Apple Music",
        "Word-by-word, with official romanizations and translations. The best data there is.",
        needs = "Needs a developer token and a music user token below",
    ),
    "spotify" to ProviderInfo(
        "Spotify",
        "The lyrics the Spotify app shows, matched to the exact track.",
        needs = "Needs your sp_dc cookie below",
    ),
    "netease" to ProviderInfo(
        "NetEase Cloud Music",
        "Word-by-word plus hand-checked romanization and translation. Best for Japanese, Korean and Chinese.",
    ),
    "musixmatch" to ProviderInfo(
        "Musixmatch",
        "Word-by-word for most Western music. A token of your own widens the catalogue.",
    ),
    "lrclib" to ProviderInfo(
        "LRCLIB",
        "Open community database, no account needed.",
    ),
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
    var showCredits by remember { mutableStateOf(false) }
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
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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

            // ---- view ------------------------------------------------------
            SectionTitle("View")

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

            ToggleRow(
                title = "Use extras from Spotify",
                subtitle = if (container.spotifyExtrasAvailable) {
                    "Artist image, full-size cover art, and pacing the background to the tempo"
                } else {
                    "Needs the Spotify cookie below"
                },
                checked = settings.useSpotifyExtras,
                accent = accent,
                onCheckedChange = { store.setUseSpotifyExtras(it) },
            )
            Help(
                "One small request per track against Spotify's own API, using the cookie below. It gets the artist's photo, the cover at full size instead of the thumbnail a media session publishes, and the tempo — which paces how fast the background drifts.",
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

            ToggleRow(
                title = "Keep the screen on",
                subtitle = "Applies next time the lyrics screen comes to the front",
                checked = settings.keepScreenOn,
                accent = accent,
                onCheckedChange = { store.setKeepScreenOn(it) },
            )

            // ---- popup lyrics ---------------------------------------------
            Divider()
            SectionTitle("Popup lyrics")

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
                    subtitle = "Off leaves the whole window to the words",
                    checked = settings.popupShowArtwork,
                    accent = accent,
                    onCheckedChange = { store.setPopupShowArtwork(it) },
                )
            }

            // ---- lyrics display -------------------------------------------
            Divider()
            SectionTitle("Lyrics display")

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

            // ---- background -----------------------------------------------
            Divider()
            SectionTitle("Background")

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

            // ---- timing ----------------------------------------------------
            Divider()
            SectionTitle("Timing")

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
                } + " Bluetooth usually needs a positive value.",
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

            // ---- language --------------------------------------------------
            Divider()
            SectionTitle("Language")

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

            ToggleRow(
                title = "Translation",
                subtitle = "Runs on the phone. Downloads a language model the first time",
                checked = settings.showTranslation,
                accent = accent,
                onCheckedChange = { store.setShowTranslation(it) },
            )
            Help(
                "Runs entirely on the phone, so nothing is sent anywhere to be translated. A translation the lyrics source already provided is always preferred over a machine one.",
            )

            if (settings.showTranslation) {
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
                "Only affects the one-off model download, not the translating itself, which is offline.",
            )
            }

            // ---- providers -------------------------------------------------
            Divider()
            SectionTitle("Where lyrics come from")
            Hint(
                "Every enabled source is asked at once and the best answer wins — " +
                    "word-by-word beats line-by-line. Order breaks ties.",
            )
            Help(
                "Asking in parallel rather than in turn is why a track resolves in one round " +
                    "trip instead of five. A source you have not given a token to is skipped " +
                    "rather than queried, so leaving it enabled costs nothing. Results are " +
                    "cached for 30 days, so each source is asked at most once per track.",
            )

            settings.providerOrder.forEachIndexed { index, id ->
                val info = PROVIDER_INFO[id] ?: ProviderInfo(id, "")
                val provider = container.providers.firstOrNull { it.id == id }
                val configured = provider?.isConfigured ?: true
                ProviderRow(
                    title = info.title,
                    subtitle = if (configured) info.description else (info.needs ?: info.description),
                    warn = !configured,
                    enabled = id in settings.enabledProviders,
                    canMoveUp = index > 0,
                    canMoveDown = index < settings.providerOrder.lastIndex,
                    accent = accent,
                    onToggle = { store.setProviderEnabled(id, it) },
                    onMoveUp = {
                        store.setProviderOrder(settings.providerOrder.swapped(index, index - 1))
                    },
                    onMoveDown = {
                        store.setProviderOrder(settings.providerOrder.swapped(index, index + 1))
                    },
                )
            }

            // ---- keys ------------------------------------------------------
            Divider()
            SectionTitle("Tokens and endpoints")
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
                help = "Sign in at open.spotify.com in a browser, copy the sp_dc cookie. It " +
                    "unlocks Spotify's own lyrics — matched to the exact track rather than " +
                    "searched for by name — and, with the toggle above, the artist image, " +
                    "the full-size cover and the song's tempo.",
                value = settings.spDcCookie.orEmpty(),
                accent = accent,
                onChange = { store.updateSpDcCookie(it) },
            )

            SecretField(
                label = "Apple Music developer token",
                help = "A JWT. Apple's web player embeds one; it is valid for months.",
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
                help = "Optional. A token from the Musixmatch desktop app covers more of the " +
                    "catalogue than the anonymous one this app can mint for itself.",
                value = settings.musixmatchUserToken.orEmpty(),
                accent = accent,
                onChange = { store.updateMusixmatchUserToken(it) },
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

            // ---- this track ------------------------------------------------
            Divider()
            SectionTitle("This track")

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

            // ---- storage ---------------------------------------------------
            Divider()
            SectionTitle("Storage")

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

            // ---- about -----------------------------------------------------
            Divider()
            SectionTitle("About")

            if (songWriters.isNotEmpty()) {
                Text(
                    text = "Written by ${songWriters.joinToString(", ")}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

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
        title = "OkHttp — Square (Apache-2.0), AndroidX and Kotlin (Apache-2.0)",
        body = "Networking, UI and language runtime.",
        link = null,
        accent = accent,
    )
    Credit(
        title = "Lyrics sources",
        body = "LRCLIB, NetEase Cloud Music, Musixmatch, Spotify and Apple Music. None are " +
            "affiliated with this app. Lyrics belong to their writers and publishers; " +
            "nothing is stored anywhere but this device's own cache.",
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

private fun List<String>.swapped(a: Int, b: Int): List<String> {
    if (a !in indices || b !in indices) return this
    val out = toMutableList()
    val tmp = out[a]
    out[a] = out[b]
    out[b] = tmp
    return out
}

// ---- building blocks ----------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        color = Color.White.copy(alpha = 0.42f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
    )
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
        options.chunked(perRow).forEach { row ->
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
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
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
            maxLines = 1,
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
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        if (canMoveUp) {
            IconTap(AppIcons.ArrowUpward, "Move $title up", onMoveUp)
        }
        if (canMoveDown) {
            IconTap(AppIcons.ArrowDownward, "Move $title down", onMoveDown)
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

@Composable
private fun IconTap(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(17.dp),
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
                modifier = Modifier.fillMaxWidth(),
            )
            if (text.isEmpty()) {
                Text("not set", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp)
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
