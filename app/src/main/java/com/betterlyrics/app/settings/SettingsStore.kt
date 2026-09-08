package com.betterlyrics.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.betterlyrics.app.lyrics.provider.ProviderCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Spicy Lyrics' "Static Background" choice. */
enum class BackgroundStyle(val label: String) {
    /** Album art, warped and drifting. Their "off", i.e. the animated background. */
    ANIMATED("Living"),

    /** Living normally, still in the floating window or on battery saver. */
    AUTO("Auto"),

    /** Album art, still, blurred by [Settings.backgroundBlur]. Their "Cover Art". */
    COVER_ART("Cover art"),

    /**
     * The artist's image instead of the album's. Their "Artist Header".
     *
     * Only Spotify knows what an artist looks like, so this needs the `sp_dc` cookie; it
     * falls back to the cover art without one.
     */
    ARTIST_HEADER("Artist"),

    /** Flat colour pulled from the artwork. Their "Color". */
    COLOR("Colour"),

    /** Pure black — best on OLED, and for reading. */
    BLACK("Black"),
}

/**
 * How the fill sweep inside a syllable is timed.
 *
 * Spicy Lyrics exposes the same choice as "Text Animation Style".
 */
enum class TextAnimationStyle(val label: String, val description: String) {
    /** Recomputed from the playhead every frame: always exact. */
    CALCULATE("Calculate", "Follows the playhead exactly, every frame"),

    /**
     * Run at a constant rate from the moment the syllable starts. Smoother when the
     * player reports its position coarsely, but drifts slightly across a seek.
     */
    ANIMATE("Animate", "Runs at a steady rate — smoother, slightly less exact"),
}

/**
 * Furigana: the small kana gloss printed over kanji.
 *
 * Hiragana is the convention in print and in songbooks; katakana is what a morphological
 * analyser reports natively and what some karaoke subtitles use, so both are offered.
 */
enum class FuriganaMode(val label: String) {
    OFF("Off"),
    HIRAGANA("Hiragana"),
    KATAKANA("Katakana"),
}

/**
 * Where a translation comes from, which is the whole question.
 *
 * The two are not interchangeable and were never worth hiding behind one switch. A
 * provider translation was written by a person, arrives with the lyrics, costs nothing and
 * works offline — but it is in whatever language that person chose. An on-device one is
 * machine output in the language *you* chose, and costs a one-off ~30 MB model download
 * per language pair.
 */
enum class TranslationSource(val label: String) {
    OFF("Off"),

    /** Only what came with the lyrics. Never downloads anything, never guesses. */
    PROVIDER("From the source"),

    /** ML Kit, into [Settings.translationTarget], ignoring what the source supplied. */
    DEVICE("On this device"),
}

/**
 * How a caching server of your own is used alongside the ordinary sources.
 *
 * Two modes because they answer different questions. [PARALLEL] is for filling the cache
 * while the server is still being written: every track is asked of both, so the server sees
 * real traffic and the app keeps working whatever the server does. [ONLY] is for testing
 * the server itself — if it cannot answer, nothing else will, which is the only way to find
 * out what it is actually missing.
 */
enum class CacheServerMode(val label: String) {
    PARALLEL("Alongside the others"),
    ONLY("Only the cache server"),
}

/** Which half of the screen the album art and track info occupy in Cinema view. */
enum class MediaPanelSide(val label: String) {
    /** Left in landscape, top in portrait. */
    START("Left / top"),

    /** Right in landscape, bottom in portrait. */
    END("Right / bottom"),
}

/** Shape of the floating window. */
enum class PopupShape(val label: String, val widthRatio: Int, val heightRatio: Int) {
    /** Wide, like the YouTube miniplayer. */
    LANDSCAPE("Wide (16:9)", 16, 9),

    /** Tall, like a mini app. */
    PORTRAIT("Tall (9:16)", 9, 16),

    /** Square — fits two or three lyric lines with the art. */
    SQUARE("Square", 1, 1),
}

/** The main layout: lyrics alone, or lyrics beside the album art. */
enum class ViewMode(val label: String) {
    LYRICS("Lyrics"),
    CINEMA("Cinema"),
}

/**
 * Which typeface the lyrics use.
 *
 * Spicy Lyrics ships its own display font; that is Spikerko's asset and is not bundled
 * here, so this picks between the families already on the device.
 */
enum class LyricsFont(val familyName: String?, val label: String) {
    SYSTEM(null, "System"),
    SANS("sans-serif", "Sans"),
    CONDENSED("sans-serif-condensed", "Condensed"),
    SERIF("serif", "Serif"),
    MONO("monospace", "Mono"),
}

/** Where the action row sits — Spicy Lyrics' "View Controls Position". */
enum class ControlsPosition { TOP, BOTTOM }

data class Settings(
    // ---- look ------------------------------------------------------------
    val fontScale: Float = 1f,
    val font: LyricsFont = LyricsFont.SYSTEM,
    val lineBlur: Boolean = true,
    /** Spicy Lyrics' "Simple Lyrics Mode": flatter contrast, no letter emphasis. */
    val simpleMode: Boolean = false,
    /** "Minimal Lyrics Mode": sung lines fade away entirely. */
    val minimalMode: Boolean = false,
    /** Tighter spacing and smaller type, for split-screen or a small window. */
    val compactMode: Boolean = false,
    /** Highlight box behind a line while you press it. */
    val lineTapHighlight: Boolean = true,
    /** Indent duet lines so the two voices read as separate columns. */
    val duetLinePadding: Boolean = true,
    val backgroundStyle: BackgroundStyle = BackgroundStyle.ANIMATED,
    /** Blur radius in px for [BackgroundStyle.COVER_ART]. */
    val backgroundBlur: Int = 24,
    val controlsPosition: ControlsPosition = ControlsPosition.TOP,
    val showVolumeSlider: Boolean = false,
    /**
     * Fetch the artist image, the full-size cover and the tempo from Spotify when a
     * cookie is available. Off means the app uses only what the media session published.
     */
    val useSpotifyExtras: Boolean = true,
    val textAnimationStyle: TextAnimationStyle = TextAnimationStyle.CALCULATE,
    val viewMode: ViewMode = ViewMode.LYRICS,
    val mediaPanelSide: MediaPanelSide = MediaPanelSide.START,
    val keepScreenOn: Boolean = true,
    /** Songwriters and the lyrics source, after the last line. */
    val showCredits: Boolean = true,
    /** False until the welcome guide has been read once. */
    val welcomeSeen: Boolean = false,

    // ---- timing ----------------------------------------------------------
    /** Nudges every timestamp. Positive means "the lyrics are late, pull them earlier". */
    val syncOffsetMs: Int = 0,
    val tapLineToSeek: Boolean = true,
    /** How long after you stop dragging before the lyrics take the scroll back. */
    val autoScrollResumeMs: Int = 1_200,

    // ---- language --------------------------------------------------------
    val showRomanization: Boolean = true,
    val romanizationStripsDiacritics: Boolean = false,
    /** Only applies while romanization is off — the gloss belongs over the original text. */
    val furigana: FuriganaMode = FuriganaMode.OFF,
    val translationSource: TranslationSource = TranslationSource.PROVIDER,
    val translationTarget: String = "en",
    val translationWifiOnly: Boolean = true,

    // ---- popup lyrics ----------------------------------------------------
    /** Allow the floating window at all. */
    val popupLyricsEnabled: Boolean = true,
    /** Shrink into it automatically when you leave the app, the way YouTube does. */
    val popupAutoEnter: Boolean = true,
    val popupShape: PopupShape = PopupShape.LANDSCAPE,
    /** Show the cover and title in the floating window as well as the lyrics. */
    val popupShowArtwork: Boolean = true,

    // ---- providers -------------------------------------------------------
    /**
     * Look the next queued track up before it starts.
     *
     * Only possible when the player publishes a queue, which most do not, so this is free
     * where it does nothing and worth it where it works: the lyrics are on screen the
     * instant the track changes, and it works with no signal.
     */
    val prefetchNextTrack: Boolean = true,
    val enabledProviders: Set<String> = DEFAULT_ENABLED_PROVIDERS,
    val providerOrder: List<String> = DEFAULT_PROVIDER_ORDER,

    // ---- credentials and endpoints ---------------------------------------
    val spDcCookie: String? = null,
    val musixmatchUserToken: String? = null,
    val lrcLibBaseUrl: String = DEFAULT_LRCLIB_URL,
    val neteaseBaseUrl: String = DEFAULT_NETEASE_URL,
    val amllBaseUrl: String = DEFAULT_AMLL_URL,
    val neteaseCookie: String? = null,
    val appleDeveloperToken: String? = null,
    val appleMusicUserToken: String? = null,
    val appleStorefront: String = "us",

    // ---- developer options ------------------------------------------------
    /** Reveals the section below in Settings. Off, and none of it is reachable. */
    val developerMode: Boolean = false,
    val cacheServerUrl: String? = null,
    val cacheServerMode: CacheServerMode = CacheServerMode.PARALLEL,
) {
    /** True when a cache server is configured and the developer options are on. */
    val cacheServerActive: Boolean
        get() = developerMode && !cacheServerUrl.isNullOrBlank()

    companion object {
        val DEFAULT_PROVIDER_ORDER = listOf(
            // Highest first. `amll` outranks everything token-free because its files are
            // hand-timed per syllable — the same thing Apple ships, without the account.
            "local", "applemusic", "amll", "spotify", "netease", "musixmatch", "lrclib",
        )

        /**
         * The ones that work with no setup. The rest need a token, or are somebody
         * else's service, so they stay off until asked for.
         */
        val DEFAULT_ENABLED_PROVIDERS =
            setOf("local", "amll", "netease", "musixmatch", "lrclib")

        const val DEFAULT_LRCLIB_URL = "https://lrclib.net"
        const val DEFAULT_NETEASE_URL = "https://music.163.com"
        const val DEFAULT_AMLL_URL = "https://api.amll.dev"
    }
}

/**
 * Plain [SharedPreferences] behind a [StateFlow]. No DataStore: every value here is a
 * handful of bytes read once at startup, and the synchronous commit keeps the token
 * caches simple.
 *
 * Also serves as the credential store for the providers that need one, so nothing else
 * has to know where those secrets live.
 */
class SettingsStore(context: Context) : ProviderCredentials {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("better-lyrics", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    private fun read(): Settings = Settings(
        fontScale = prefs.getFloat(KEY_FONT_SCALE, 1f),
        font = prefs.enum(KEY_FONT, LyricsFont.SYSTEM),
        lineBlur = prefs.getBoolean(KEY_LINE_BLUR, true),
        simpleMode = prefs.getBoolean(KEY_SIMPLE_MODE, false),
        minimalMode = prefs.getBoolean(KEY_MINIMAL_MODE, false),
        compactMode = prefs.getBoolean(KEY_COMPACT_MODE, false),
        lineTapHighlight = prefs.getBoolean(KEY_LINE_TAP_HIGHLIGHT, true),
        duetLinePadding = prefs.getBoolean(KEY_DUET_PADDING, true),
        backgroundStyle = prefs.enum(KEY_BACKGROUND, BackgroundStyle.ANIMATED),
        backgroundBlur = prefs.getInt(KEY_BACKGROUND_BLUR, 24),
        controlsPosition = prefs.enum(KEY_CONTROLS_POSITION, ControlsPosition.TOP),
        showVolumeSlider = prefs.getBoolean(KEY_VOLUME_SLIDER, false),
        useSpotifyExtras = prefs.getBoolean(KEY_SPOTIFY_EXTRAS, true),
        textAnimationStyle = prefs.enum(KEY_TEXT_ANIMATION, TextAnimationStyle.CALCULATE),
        viewMode = prefs.enum(KEY_VIEW_MODE, ViewMode.LYRICS),
        mediaPanelSide = prefs.enum(KEY_PANEL_SIDE, MediaPanelSide.START),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
        showCredits = prefs.getBoolean(KEY_SHOW_CREDITS, true),
        welcomeSeen = prefs.getBoolean(KEY_WELCOME_SEEN, false),

        syncOffsetMs = prefs.getInt(KEY_SYNC_OFFSET, 0),
        tapLineToSeek = prefs.getBoolean(KEY_TAP_TO_SEEK, true),
        autoScrollResumeMs = prefs.getInt(KEY_SCROLL_RESUME, 1_200),

        showRomanization = prefs.getBoolean(KEY_ROMANIZE, true),
        romanizationStripsDiacritics = prefs.getBoolean(KEY_STRIP_DIACRITICS, false),
        furigana = prefs.enum(KEY_FURIGANA, FuriganaMode.OFF),
        translationSource = prefs.translationSource(),
        translationTarget = prefs.getString(KEY_TRANSLATE_TARGET, "en") ?: "en",
        translationWifiOnly = prefs.getBoolean(KEY_TRANSLATE_WIFI, true),

        popupLyricsEnabled = prefs.getBoolean(KEY_POPUP_ENABLED, true),
        popupAutoEnter = prefs.getBoolean(KEY_POPUP_AUTO, true),
        popupShape = prefs.enum(KEY_POPUP_SHAPE, PopupShape.LANDSCAPE),
        popupShowArtwork = prefs.getBoolean(KEY_POPUP_ARTWORK, true),

        prefetchNextTrack = prefs.getBoolean(KEY_PREFETCH_NEXT, true),
        enabledProviders = prefs.getStringSet(KEY_PROVIDERS_ON, null)
            ?: Settings.DEFAULT_ENABLED_PROVIDERS,
        providerOrder = prefs.getString(KEY_PROVIDER_ORDER, null)
            ?.split(',')?.filter { it.isNotBlank() }
            ?.let { stored ->
                // Keep any provider added in a later version, even if the stored order
                // predates it.
                stored + Settings.DEFAULT_PROVIDER_ORDER.filterNot { it in stored }
            }
            ?: Settings.DEFAULT_PROVIDER_ORDER,

        spDcCookie = prefs.trimmed(KEY_SP_DC),
        musixmatchUserToken = prefs.trimmed(KEY_MXM_USER_TOKEN),
        lrcLibBaseUrl = prefs.trimmed(KEY_LRCLIB_URL) ?: Settings.DEFAULT_LRCLIB_URL,
        neteaseBaseUrl = prefs.trimmed(KEY_NETEASE_URL) ?: Settings.DEFAULT_NETEASE_URL,
        amllBaseUrl = prefs.trimmed(KEY_AMLL_URL) ?: Settings.DEFAULT_AMLL_URL,
        neteaseCookie = prefs.trimmed(KEY_NETEASE_COOKIE),
        appleDeveloperToken = prefs.trimmed(KEY_APPLE_DEV_TOKEN),
        appleMusicUserToken = prefs.trimmed(KEY_APPLE_USER_TOKEN),
        appleStorefront = prefs.trimmed(KEY_APPLE_STOREFRONT) ?: "us",

        developerMode = prefs.getBoolean(KEY_DEVELOPER_MODE, false),
        cacheServerUrl = prefs.trimmed(KEY_CACHE_SERVER_URL),
        cacheServerMode = prefs.enum(KEY_CACHE_SERVER_MODE, CacheServerMode.PARALLEL),
    )

    /**
     * Reads the source, migrating the single boolean this used to be.
     *
     * That switch only ever enabled the machine translator, so someone who had turned it
     * on wanted [TranslationSource.DEVICE]. Someone who had left it off was declining a
     * 30 MB download, not declining the free human translations that came with their
     * lyrics — there was no way to ask for those separately — so they land on
     * [TranslationSource.PROVIDER] along with everybody new.
     */
    private fun SharedPreferences.translationSource(): TranslationSource {
        getString(KEY_TRANSLATE_SOURCE, null)?.let { stored ->
            runCatching { return enumValueOf<TranslationSource>(stored) }
        }
        return if (getBoolean(KEY_TRANSLATE_LEGACY, false)) {
            TranslationSource.DEVICE
        } else {
            TranslationSource.PROVIDER
        }
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, fallback: T): T {
        val name = getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }

    private fun SharedPreferences.trimmed(key: String): String? =
        getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _settings.value = read()
    }

    // ---- look ---------------------------------------------------------------

    fun setFontScale(value: Float) = edit { putFloat(KEY_FONT_SCALE, value.coerceIn(0.6f, 1.8f)) }

    fun setFont(font: LyricsFont) = edit { putString(KEY_FONT, font.name) }

    fun setLineBlur(value: Boolean) = edit { putBoolean(KEY_LINE_BLUR, value) }

    fun setSimpleMode(value: Boolean) = edit { putBoolean(KEY_SIMPLE_MODE, value) }

    fun setMinimalMode(value: Boolean) = edit { putBoolean(KEY_MINIMAL_MODE, value) }

    fun setCompactMode(value: Boolean) = edit { putBoolean(KEY_COMPACT_MODE, value) }

    fun setLineTapHighlight(value: Boolean) = edit { putBoolean(KEY_LINE_TAP_HIGHLIGHT, value) }

    fun setDuetLinePadding(value: Boolean) = edit { putBoolean(KEY_DUET_PADDING, value) }

    fun setBackgroundStyle(style: BackgroundStyle) = edit { putString(KEY_BACKGROUND, style.name) }

    fun setBackgroundBlur(px: Int) = edit { putInt(KEY_BACKGROUND_BLUR, px.coerceIn(0, 67)) }

    fun setControlsPosition(position: ControlsPosition) =
        edit { putString(KEY_CONTROLS_POSITION, position.name) }

    fun setShowVolumeSlider(value: Boolean) = edit { putBoolean(KEY_VOLUME_SLIDER, value) }

    fun setUseSpotifyExtras(value: Boolean) = edit { putBoolean(KEY_SPOTIFY_EXTRAS, value) }

    fun setTextAnimationStyle(style: TextAnimationStyle) =
        edit { putString(KEY_TEXT_ANIMATION, style.name) }

    fun setViewMode(mode: ViewMode) = edit { putString(KEY_VIEW_MODE, mode.name) }

    fun setMediaPanelSide(side: MediaPanelSide) = edit { putString(KEY_PANEL_SIDE, side.name) }

    /** The swap button in Cinema view. */
    fun toggleMediaPanelSide() = setMediaPanelSide(
        if (current.mediaPanelSide == MediaPanelSide.START) MediaPanelSide.END
        else MediaPanelSide.START,
    )

    fun toggleViewMode() = setViewMode(
        if (current.viewMode == ViewMode.LYRICS) ViewMode.CINEMA else ViewMode.LYRICS,
    )

    fun setKeepScreenOn(value: Boolean) = edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    fun setShowCredits(value: Boolean) = edit { putBoolean(KEY_SHOW_CREDITS, value) }

    fun setWelcomeSeen(value: Boolean) = edit { putBoolean(KEY_WELCOME_SEEN, value) }

    // ---- timing -------------------------------------------------------------

    fun setSyncOffset(ms: Int) = edit { putInt(KEY_SYNC_OFFSET, ms.coerceIn(-5_000, 5_000)) }

    fun setTapLineToSeek(value: Boolean) = edit { putBoolean(KEY_TAP_TO_SEEK, value) }

    fun setAutoScrollResumeMs(ms: Int) = edit { putInt(KEY_SCROLL_RESUME, ms.coerceIn(300, 10_000)) }

    // ---- language -----------------------------------------------------------

    fun setShowRomanization(value: Boolean) = edit { putBoolean(KEY_ROMANIZE, value) }

    fun setStripDiacritics(value: Boolean) = edit { putBoolean(KEY_STRIP_DIACRITICS, value) }

    fun setFurigana(mode: FuriganaMode) = edit { putString(KEY_FURIGANA, mode.name) }

    fun setPrefetchNextTrack(value: Boolean) = edit { putBoolean(KEY_PREFETCH_NEXT, value) }

    fun setTranslationSource(value: TranslationSource) = edit {
        putString(KEY_TRANSLATE_SOURCE, value.name)
        // Remember which of the two the user actually wanted, so the toggle over the
        // lyrics can put it back without a third state on screen.
        if (value != TranslationSource.OFF) putString(KEY_TRANSLATE_LAST, value.name)
    }

    /**
     * What the chip over the lyrics does: off, or back to whichever source was last in
     * use. Never silently starts a model download — an install that has never chosen
     * lands on [TranslationSource.PROVIDER].
     */
    fun toggleTranslation() {
        val current = current.translationSource
        if (current != TranslationSource.OFF) {
            setTranslationSource(TranslationSource.OFF)
            return
        }
        val last = prefs.getString(KEY_TRANSLATE_LAST, null)
            ?.let { name -> runCatching { enumValueOf<TranslationSource>(name) }.getOrNull() }
        setTranslationSource(last?.takeIf { it != TranslationSource.OFF } ?: TranslationSource.PROVIDER)
    }

    fun setTranslationTarget(tag: String) = edit { putString(KEY_TRANSLATE_TARGET, tag) }

    fun setTranslationWifiOnly(value: Boolean) = edit { putBoolean(KEY_TRANSLATE_WIFI, value) }

    // ---- popup --------------------------------------------------------------

    fun setPopupLyricsEnabled(value: Boolean) = edit { putBoolean(KEY_POPUP_ENABLED, value) }

    fun setPopupAutoEnter(value: Boolean) = edit { putBoolean(KEY_POPUP_AUTO, value) }

    fun setPopupShape(shape: PopupShape) = edit { putString(KEY_POPUP_SHAPE, shape.name) }

    fun setPopupShowArtwork(value: Boolean) = edit { putBoolean(KEY_POPUP_ARTWORK, value) }

    // ---- providers ----------------------------------------------------------

    fun setProviderEnabled(id: String, enabled: Boolean) = edit {
        val next = current.enabledProviders.toMutableSet()
        if (enabled) next += id else next -= id
        putStringSet(KEY_PROVIDERS_ON, next)
    }

    fun setProviderOrder(order: List<String>) = edit {
        putString(KEY_PROVIDER_ORDER, order.joinToString(","))
    }

    // ---- credentials --------------------------------------------------------

    fun updateSpDcCookie(value: String?) = edit {
        putString(KEY_SP_DC, value?.trim())
        // A new cookie invalidates whatever token the old one minted.
        remove(KEY_SP_TOKEN)
        remove(KEY_SP_TOKEN_EXPIRY)
    }

    fun updateMusixmatchUserToken(value: String?) = edit {
        putString(KEY_MXM_USER_TOKEN, value?.trim())
        // Stop using the anonymous token so the user's own one takes effect at once.
        remove(KEY_MXM_GUEST_TOKEN)
    }

    fun updateLrcLibBaseUrl(value: String?) = edit {
        putString(KEY_LRCLIB_URL, value?.trim()?.trimEnd('/'))
    }

    fun updateNeteaseBaseUrl(value: String?) = edit {
        putString(KEY_NETEASE_URL, value?.trim()?.trimEnd('/'))
    }

    fun setDeveloperMode(value: Boolean) = edit { putBoolean(KEY_DEVELOPER_MODE, value) }

    fun updateCacheServerUrl(value: String?) = edit {
        putString(KEY_CACHE_SERVER_URL, value?.trim()?.trimEnd('/'))
    }

    fun setCacheServerMode(value: CacheServerMode) = edit {
        putString(KEY_CACHE_SERVER_MODE, value.name)
    }

    fun updateAmllBaseUrl(value: String?) = edit {
        putString(KEY_AMLL_URL, value?.trim()?.trimEnd('/'))
    }

    fun updateNeteaseCookie(value: String?) = edit { putString(KEY_NETEASE_COOKIE, value?.trim()) }

    fun updateAppleDeveloperToken(value: String?) =
        edit { putString(KEY_APPLE_DEV_TOKEN, value?.trim()) }

    fun updateAppleMusicUserToken(value: String?) =
        edit { putString(KEY_APPLE_USER_TOKEN, value?.trim()) }

    fun updateAppleStorefront(value: String?) = edit {
        putString(KEY_APPLE_STOREFRONT, value?.trim()?.lowercase()?.takeIf { it.length == 2 })
    }

    // ---- ProviderCredentials ------------------------------------------------

    override val lrcLibBaseUrl: String get() = current.lrcLibBaseUrl
    override val neteaseBaseUrl: String get() = current.neteaseBaseUrl
    override val amllBaseUrl: String get() = current.amllBaseUrl

    // Null unless the developer options are on, so a URL left behind in preferences
    // cannot keep answering after the switch is turned off.
    override val cacheServerUrl: String?
        get() = current.cacheServerUrl?.takeIf { current.developerMode }
    override val neteaseCookie: String? get() = current.neteaseCookie
    override val musixmatchUserToken: String? get() = current.musixmatchUserToken
    override val appleDeveloperToken: String? get() = current.appleDeveloperToken
    override val appleMusicUserToken: String? get() = current.appleMusicUserToken
    override val appleStorefront: String get() = current.appleStorefront

    override var musixmatchGuestToken: String?
        get() = prefs.getString(KEY_MXM_GUEST_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_MXM_GUEST_TOKEN, value).apply()
        }

    override var spDcCookie: String?
        get() = prefs.getString(KEY_SP_DC, null)
        set(value) = updateSpDcCookie(value)

    override var cachedSpotifyToken: String?
        get() = prefs.getString(KEY_SP_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_SP_TOKEN, value).apply()
        }

    override var cachedSpotifyTokenExpiresAt: Long
        get() = prefs.getLong(KEY_SP_TOKEN_EXPIRY, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SP_TOKEN_EXPIRY, value).apply()
        }

    // Internal rather than private so the migration tests can plant an old install's
    // preferences without guessing at the key names.
    internal companion object {
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_FONT = "font"
        const val KEY_LINE_BLUR = "line_blur"
        const val KEY_SIMPLE_MODE = "simple_mode"
        const val KEY_MINIMAL_MODE = "minimal_mode"
        const val KEY_COMPACT_MODE = "compact_mode"
        const val KEY_LINE_TAP_HIGHLIGHT = "line_tap_highlight"
        const val KEY_DUET_PADDING = "duet_padding"
        const val KEY_BACKGROUND = "background_style"
        const val KEY_BACKGROUND_BLUR = "background_blur"
        const val KEY_CONTROLS_POSITION = "controls_position"
        const val KEY_VOLUME_SLIDER = "volume_slider"
        const val KEY_SPOTIFY_EXTRAS = "spotify_extras"
        const val KEY_TEXT_ANIMATION = "text_animation_style"
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_PANEL_SIDE = "media_panel_side"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SHOW_CREDITS = "show_credits"
        const val KEY_WELCOME_SEEN = "welcome_seen"

        const val KEY_SYNC_OFFSET = "sync_offset_ms"
        const val KEY_TAP_TO_SEEK = "tap_to_seek"
        const val KEY_SCROLL_RESUME = "scroll_resume_ms"

        const val KEY_ROMANIZE = "show_romanization"
        const val KEY_STRIP_DIACRITICS = "strip_diacritics"
        const val KEY_FURIGANA = "furigana"
        /** The boolean this setting used to be. Read once, to migrate; never written. */
        const val KEY_TRANSLATE_LEGACY = "show_translation"
        const val KEY_TRANSLATE_SOURCE = "translation_source"
        const val KEY_TRANSLATE_LAST = "translation_source_last"
        const val KEY_TRANSLATE_TARGET = "translation_target"
        const val KEY_TRANSLATE_WIFI = "translation_wifi_only"

        const val KEY_POPUP_ENABLED = "popup_enabled"
        const val KEY_POPUP_AUTO = "popup_auto_enter"
        const val KEY_POPUP_SHAPE = "popup_shape"
        const val KEY_POPUP_ARTWORK = "popup_artwork"

        const val KEY_PREFETCH_NEXT = "prefetch_next"
        const val KEY_PROVIDERS_ON = "providers_enabled"
        const val KEY_PROVIDER_ORDER = "provider_order"

        const val KEY_SP_DC = "sp_dc"
        const val KEY_SP_TOKEN = "sp_access_token"
        const val KEY_SP_TOKEN_EXPIRY = "sp_access_token_expiry"
        const val KEY_MXM_GUEST_TOKEN = "mxm_token"
        const val KEY_MXM_USER_TOKEN = "mxm_user_token"
        const val KEY_LRCLIB_URL = "lrclib_url"
        const val KEY_NETEASE_URL = "netease_url"
        const val KEY_AMLL_URL = "amll_url"
        const val KEY_DEVELOPER_MODE = "developer_mode"
        const val KEY_CACHE_SERVER_URL = "cache_server_url"
        const val KEY_CACHE_SERVER_MODE = "cache_server_mode"
        const val KEY_NETEASE_COOKIE = "netease_cookie"
        const val KEY_APPLE_DEV_TOKEN = "apple_dev_token"
        const val KEY_APPLE_USER_TOKEN = "apple_user_token"
        const val KEY_APPLE_STOREFRONT = "apple_storefront"
    }
}
