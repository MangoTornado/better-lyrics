package com.melisma.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.melisma.app.lyrics.provider.ProviderCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Spicy Lyrics' "Static Background" choice. */
enum class BackgroundStyle(val label: String) {
    /** Album art, warped and drifting. Their "off", i.e. the animated background. */
    ANIMATED("Living"),

    /**
     * Living normally, still where the battery settings ask for it.
     *
     * Which is now the floating window and battery saver, and both are switches of their own — so
     * this and [ANIMATED] differ only in that this one has no argument with them.
     */
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
 * Where to look for cover art the player did not publish at a useful size.
 *
 * A media session's thumbnail is often 300px or less, which a full-screen background shows
 * up for what it is. Both alternatives here are keyless and public.
 */
enum class ArtworkSource(val label: String) {
    /** Only what the player publishes. No extra requests. */
    PLAYER("Player only"),

    /** Apple's public search. Fast, wide, and returns squares up to 1000px. */
    ITUNES("iTunes"),

    /** MusicBrainz then the Cover Art Archive. Slower, community-run, no rate key. */
    COVER_ART_ARCHIVE("Cover Art Archive"),
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
    START("Left / Top"),

    /** Right in landscape, bottom in portrait. */
    END("Right / Bottom"),
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

    /**
     * Hold the background still in the floating window.
     *
     * On by default: the window is what stays on screen over whatever else you are doing, often for
     * an hour at a time, and a drifting background is the most expensive thing this app draws. Off
     * keeps the animated one there too.
     */
    val popupStillBackground: Boolean = true,

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

    /**
     * Players whose sessions to ignore, by package name.
     *
     * The app follows every media session on the device except its own, which is right for a music
     * player and wrong for everything else that publishes one: a video, a podcast, a browser tab. Those
     * arrive shaped exactly like a track — a title, an artist, a duration — get looked up, and land in
     * the cache as songs that do not exist.
     *
     * Empty by default. Nothing is guessed at from the package name or the metadata: a long duration and
     * "Official Video" in the title describe a great many real songs, and silently ignoring a player
     * would be a worse failure than a cluttered cache, because it looks like the app is broken.
     */
    val ignoredPlayers: Set<String> = emptySet(),

    /**
     * Every player that has published a session, by package name, with the label to show for it.
     *
     * Remembered so the list in Settings has something in it: a player is only visible while it holds a
     * session, and "turn off the app that polluted your cache yesterday" needs it to be listed today.
     */
    val seenPlayers: Map<String, String> = emptyMap(),

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

    // ---- background --------------------------------------------------------
    /**
     * Minutes of nothing playing, with the app off screen, before it stops watching. 0 never does.
     *
     * Reading what is playing requires an enabled notification listener, and an enabled listener is
     * a bound service: Android keeps the process resident and delivers every notification on the
     * device to it, whether or not anything is playing. That is why the app appeared never to close.
     * Standing the listener down gives the process back.
     *
     * The cost, stated plainly because it is real: while stood down the app cannot see a track
     * starting. Opening it starts watching again.
     */
    val backgroundTimeoutMinutes: Int = 10,

    // ---- battery saver -----------------------------------------------------
    /**
     * Take the phone's own battery saver as a instruction to ease off.
     *
     * The system dims its own animations in this mode for exactly this reason, and an app whose
     * entire purpose is to be left on screen has more to give back than most. What it actually
     * does is the three switches below, each one separate: every measure here trades something
     * away, and which trade is worth making is not the same answer for everybody.
     */
    val followBatterySaver: Boolean = true,

    /** Hold the drifting background still while saving. The largest saving, and the cheapest to give up. */
    val saverStillBackground: Boolean = true,

    /**
     * Stop holding the screen awake while saving.
     *
     * Off by default, alone among these: the screen is far and away the most expensive thing on the
     * phone, but a lyrics screen that goes dark mid-song is the one measure here that breaks what
     * the app is for.
     */
    val saverReleaseScreen: Boolean = false,

    /** Skip looking the next queued track up early while saving. */
    val saverSkipPrefetch: Boolean = true,

    // ---- updates ----------------------------------------------------------
    /**
     * Look for a new release on launch, at most every few hours.
     *
     * On by default: the app is installed from an APK, so nothing else will ever tell the
     * user a fix exists. It is one small request to a public endpoint, and it downloads
     * nothing until they say so.
     */
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheckAt: Long = 0L,

    /** A version the user chose to skip. A later one is still offered. */
    val skippedUpdateVersion: String? = null,

    // ---- developer options ------------------------------------------------
    /** Reveals the section below in Settings. Off, and none of it is reachable. */
    val developerMode: Boolean = false,
    val cacheServerUrl: String? = null,
    val cacheServerKey: String? = null,
    val cacheServerMode: CacheServerMode = CacheServerMode.PARALLEL,

    /**
     * A web access token copied out of Spotify's player, since the cookie route is closed.
     *
     * Short-lived by nature — an hour or so — so it lives with the developer options rather
     * than being offered as something to set up and forget.
     */
    val spotifyWebToken: String? = null,

    val artworkSource: ArtworkSource = ArtworkSource.PLAYER,

    /**
     * Let the cache server hold the artwork and tempo as well as the words.
     *
     * The reason to want this: a Spotify token lasts an hour and an Apple one a few months,
     * but a cover URL and a tempo, once known, are true forever. Whenever a token does produce
     * them the app hands them to the server, and from then on the server can answer for that
     * track with no token at all.
     */
    val cacheServerExtras: Boolean = false,

    /**
     * Let a hidden WebView renew the Spotify token from the `sp_dc` cookie.
     *
     * Off by default and behind developer options, because it is automated access to a service
     * whose terms discourage it. A private server can take that position for one person; a signed
     * APK that anybody installs is a different proposition, so this is a switch its owner throws
     * rather than a feature that ships on.
     */
    val spotifyBrowserToken: Boolean = false,
) {
    /** True when a cache server is configured and the developer options are on. */
    val cacheServerActive: Boolean
        get() = developerMode && !cacheServerUrl.isNullOrBlank()

    /** True when that server should also hold the artwork and the tempo. */
    val cacheServerExtrasActive: Boolean
        get() = cacheServerActive && cacheServerExtras

    /** Only with the switch on, developer options on, and a cookie to use. */
    val spotifyBrowserTokenActive: Boolean
        get() = developerMode && spotifyBrowserToken && !spDcCookie.isNullOrBlank()

    companion object {
        val DEFAULT_PROVIDER_ORDER = listOf(
            // Highest first, and the order only breaks ties: a word-by-word result always
            // beats a line-by-line one whatever the order says, because that is the
            // difference between karaoke and a teleprompter.
            //
            // Within a tier this is about how the match was made. Your own file is a
            // certainty. Apple and Spotify identify the recording — Spotify by the id the
            // media session handed us, which cannot be the wrong song — so they come before
            // the databases that have to guess from a title.
            "local", "cacheserver", "applemusic", "spotify", "amll", "netease", "musixmatch",
            "lrclib",
        )

        /**
         * The ones that work with no setup. The rest need a token, or are somebody
         * else's service, so they stay off until asked for.
         */
        /**
         * On unless it needs something the user has not given it.
         *
         * `spotify` is here despite needing a token: it reports itself unconfigured without
         * one and is skipped rather than queried, so leaving it on costs nothing — and
         * leaving it *off* meant pasting a token changed nothing, which is not what anyone
         * would expect. `applemusic` stays off because it needs two tokens and one of them
         * is a paid membership away.
         */
        val DEFAULT_ENABLED_PROVIDERS =
            setOf("local", "cacheserver", "spotify", "amll", "netease", "musixmatch", "lrclib")

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
        context.getSharedPreferences("melisma", Context.MODE_PRIVATE)

    /**
     * Cookies and tokens, kept in their own file so backup can be told to leave them
     * alone.
     *
     * Settings are worth restoring onto a new phone; a signed-in Spotify session is not.
     * These are live credentials for the user's own accounts, and the app tells them so —
     * "stays on this device" has to be true, which means it cannot be in a cloud backup or
     * a device-to-device transfer. Backup rules work per file, so the only way to say it is
     * to put them in a different one.
     */
    private val secrets: SharedPreferences =
        context.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    init {
        adoptPreRenameFiles(context)
        migrateSecrets()
    }

    /**
     * Take over the preference files written when the app was called Better Lyrics.
     *
     * The rename changed the application id, which means Android treats the new build as a different
     * app and gives it empty storage — so a phone that had the old one installed keeps its settings on
     * disk under the old file names with nothing reading them. This adopts them once.
     *
     * Only when the current files are *empty*, so it can never overwrite a live setting, and it
     * copies rather than moves: if this turns out to have gone wrong, the originals are still there.
     * Delete this once nobody is upgrading across the rename any more.
     */
    private fun adoptPreRenameFiles(context: Context) {
        fun adopt(from: String, into: SharedPreferences) {
            if (into.all.isNotEmpty()) return
            val legacy = context.getSharedPreferences(from, Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) return
            val editor = into.edit()
            for ((key, value) in legacy.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
        adopt(LEGACY_FILE, prefs)
        adopt(LEGACY_SECRETS_FILE, secrets)
    }

    // ---- backup and restore -------------------------------------------------

    /**
     * Every ordinary setting, for a file the user keeps. Never a credential: those are asked for
     * separately, encrypted separately, and it must not be possible to include one by forgetting.
     */
    fun exportableSettings(): Map<String, Any?> = prefs.all.filterKeys { it !in SECRET_KEYS }

    /** Every credential. Only ever called when the user has asked for them and given a passphrase. */
    fun exportableCredentials(): Map<String, Any?> = secrets.all

    /**
     * Put values back.
     *
     * Keys absent from the file are left alone rather than reset, so restoring an old backup onto a
     * newer version does not wipe settings that did not exist when it was written. Unknown keys are
     * written anyway and simply never read — harmless, and cheaper than a list to keep in step.
     */
    fun restore(settings: Map<String, Any?>, credentials: Map<String, Any?>) {
        if (settings.isNotEmpty()) apply(prefs, settings)
        if (credentials.isNotEmpty()) apply(secrets, credentials)
        _settings.value = read()
    }

    private fun apply(target: SharedPreferences, values: Map<String, Any?>) {
        val editor = target.edit()
        for ((key, value) in values) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    /**
     * Move credentials written by an earlier version out of the backed-up file.
     *
     * Runs once — after it, the keys are gone from `melisma` and this finds nothing.
     * Anything already carried into a backup is beyond reach here; this stops it happening
     * again, and re-entering a cookie is a smaller cost than leaving one in the cloud.
     */
    private fun migrateSecrets() {
        val present = SECRET_KEYS.filter { prefs.contains(it) }
        if (present.isEmpty()) return

        val target = secrets.edit()
        val source = prefs.edit()
        for (key in present) {
            when (val value = prefs.all[key]) {
                is String -> target.putString(key, value)
                is Long -> target.putLong(key, value)
                is Int -> target.putInt(key, value)
                is Boolean -> target.putBoolean(key, value)
            }
            source.remove(key)
        }
        target.commit()
        source.commit()
    }

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
        popupStillBackground = prefs.getBoolean(KEY_POPUP_STILL_BACKGROUND, true),

        prefetchNextTrack = prefs.getBoolean(KEY_PREFETCH_NEXT, true),
        enabledProviders = prefs.getStringSet(KEY_PROVIDERS_ON, null)
            ?: Settings.DEFAULT_ENABLED_PROVIDERS,
        ignoredPlayers = prefs.getStringSet(KEY_PLAYERS_IGNORED, null).orEmpty(),
        seenPlayers = prefs.getStringSet(KEY_PLAYERS_SEEN, null).orEmpty()
            .mapNotNull { entry ->
                // `package\u0000label`, because a label can contain anything a developer typed and a
                // separator that cannot appear in either half is the only kind worth using.
                val at = entry.indexOf('\u0000')
                if (at <= 0) null else entry.take(at) to entry.substring(at + 1)
            }
            .toMap(),
        providerOrder = prefs.getString(KEY_PROVIDER_ORDER, null)
            ?.split(',')?.filter { it.isNotBlank() }
            ?.let { stored ->
                // Keep any provider added in a later version, even if the stored order predates it.
                // Appended at the end, because a source nobody has ranked should not outrank the
                // ones they have — except the cache server, whose whole purpose is to answer before
                // anything goes to the network. Last would make it pointless.
                val added = Settings.DEFAULT_PROVIDER_ORDER.filterNot { it in stored }
                val front = added.filter { it == "cacheserver" }
                front + stored + added.filterNot { it == "cacheserver" }
            }
            ?: Settings.DEFAULT_PROVIDER_ORDER,

        spDcCookie = secrets.trimmed(KEY_SP_DC),
        musixmatchUserToken = secrets.trimmed(KEY_MXM_USER_TOKEN),
        lrcLibBaseUrl = prefs.trimmed(KEY_LRCLIB_URL) ?: Settings.DEFAULT_LRCLIB_URL,
        neteaseBaseUrl = prefs.trimmed(KEY_NETEASE_URL) ?: Settings.DEFAULT_NETEASE_URL,
        amllBaseUrl = prefs.trimmed(KEY_AMLL_URL) ?: Settings.DEFAULT_AMLL_URL,
        neteaseCookie = secrets.trimmed(KEY_NETEASE_COOKIE),
        appleDeveloperToken = secrets.trimmed(KEY_APPLE_DEV_TOKEN),
        appleMusicUserToken = secrets.trimmed(KEY_APPLE_USER_TOKEN),
        appleStorefront = prefs.trimmed(KEY_APPLE_STOREFRONT) ?: "us",

        backgroundTimeoutMinutes = prefs.getInt(KEY_BACKGROUND_TIMEOUT, 10),
        followBatterySaver = prefs.getBoolean(KEY_FOLLOW_SAVER, true),
        saverStillBackground = prefs.getBoolean(KEY_SAVER_STILL_BACKGROUND, true),
        saverReleaseScreen = prefs.getBoolean(KEY_SAVER_RELEASE_SCREEN, false),
        saverSkipPrefetch = prefs.getBoolean(KEY_SAVER_SKIP_PREFETCH, true),
        autoUpdateCheck = prefs.getBoolean(KEY_AUTO_UPDATE, true),
        lastUpdateCheckAt = prefs.getLong(KEY_UPDATE_CHECKED_AT, 0L),
        skippedUpdateVersion = prefs.trimmed(KEY_UPDATE_SKIPPED),

        developerMode = prefs.getBoolean(KEY_DEVELOPER_MODE, false),
        spotifyBrowserToken = prefs.getBoolean(KEY_SPOTIFY_BROWSER_TOKEN, false),
        cacheServerUrl = prefs.trimmed(KEY_CACHE_SERVER_URL),
        cacheServerKey = secrets.trimmed(KEY_CACHE_SERVER_KEY),
        cacheServerMode = prefs.enum(KEY_CACHE_SERVER_MODE, CacheServerMode.PARALLEL),
        spotifyWebToken = secrets.trimmed(KEY_SP_WEB_TOKEN),
        artworkSource = prefs.enum(KEY_ARTWORK_SOURCE, ArtworkSource.PLAYER),
        cacheServerExtras = prefs.getBoolean(KEY_CACHE_SERVER_EXTRAS, false),
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
     * What the chip over the lyrics does: off, or back to whichever source was last in use.
     *
     * @param providerHasTranslation whether the track on screen actually arrived with one.
     *   When it did not — LRCLIB supplies none at all, and most Western tracks have none
     *   anywhere — choosing "from the source" would turn the button into a no-op, so it
     *   falls through to the on-device translator instead. Pressing a button labelled
     *   translate should translate.
     *
     * @return the source now in use, so the caller can say what happened.
     */
    fun toggleTranslation(providerHasTranslation: Boolean): TranslationSource {
        if (current.translationSource != TranslationSource.OFF) {
            setTranslationSource(TranslationSource.OFF)
            return TranslationSource.OFF
        }

        val last = prefs.getString(KEY_TRANSLATE_LAST, null)
            ?.let { name -> runCatching { enumValueOf<TranslationSource>(name) }.getOrNull() }
            ?.takeIf { it != TranslationSource.OFF }

        val next = when {
            last == TranslationSource.DEVICE -> TranslationSource.DEVICE
            providerHasTranslation -> TranslationSource.PROVIDER
            else -> TranslationSource.DEVICE
        }
        setTranslationSource(next)
        return next
    }

    fun setTranslationTarget(tag: String) = edit { putString(KEY_TRANSLATE_TARGET, tag) }

    fun setTranslationWifiOnly(value: Boolean) = edit { putBoolean(KEY_TRANSLATE_WIFI, value) }

    // ---- popup --------------------------------------------------------------

    fun setPopupLyricsEnabled(value: Boolean) = edit { putBoolean(KEY_POPUP_ENABLED, value) }

    fun setPopupAutoEnter(value: Boolean) = edit { putBoolean(KEY_POPUP_AUTO, value) }

    fun setPopupShape(shape: PopupShape) = edit { putString(KEY_POPUP_SHAPE, shape.name) }

    fun setPopupShowArtwork(value: Boolean) = edit { putBoolean(KEY_POPUP_ARTWORK, value) }

    fun setPopupStillBackground(value: Boolean) = edit {
        putBoolean(KEY_POPUP_STILL_BACKGROUND, value)
    }

    // ---- providers ----------------------------------------------------------

    fun setProviderEnabled(id: String, enabled: Boolean) = edit {
        val next = current.enabledProviders.toMutableSet()
        if (enabled) next += id else next -= id
        putStringSet(KEY_PROVIDERS_ON, next)
    }

    fun setProviderOrder(order: List<String>) = edit {
        putString(KEY_PROVIDER_ORDER, order.joinToString(","))
    }

    // ---- which players to follow --------------------------------------------

    fun setPlayerIgnored(packageName: String, ignored: Boolean) = edit {
        val next = current.ignoredPlayers.toMutableSet()
        if (ignored) next += packageName else next -= packageName
        putStringSet(KEY_PLAYERS_IGNORED, next)
    }

    /**
     * Records that a player exists, so Settings can offer it.
     *
     * Called from the session watcher, which means every session change — so it writes only when
     * something is actually new. A preference commit per media event would be a write on every pause.
     */
    fun notePlayerSeen(packageName: String, label: String) {
        if (packageName.isBlank()) return
        if (current.seenPlayers[packageName] == label) return
        edit {
            val next = current.seenPlayers.toMutableMap()
            next[packageName] = label
            putStringSet(KEY_PLAYERS_SEEN, next.map { (pkg, name) -> "$pkg\u0000$name" }.toSet())
        }
    }

    /** Forgets the list, for when it has filled up with players long since uninstalled. */
    fun forgetSeenPlayers() = edit { remove(KEY_PLAYERS_SEEN) }

    // ---- credentials --------------------------------------------------------

    /** Like [edit], but writing to the file backup is told to skip. */
    private inline fun editSecrets(block: SharedPreferences.Editor.() -> Unit) {
        secrets.edit().apply(block).apply()
        _settings.value = read()
    }

    fun updateSpDcCookie(value: String?) = editSecrets {
        putString(KEY_SP_DC, value?.trim())
        // A new cookie invalidates whatever token the old one minted.
        remove(KEY_SP_TOKEN)
        remove(KEY_SP_TOKEN_EXPIRY)
    }

    fun updateMusixmatchUserToken(value: String?) = editSecrets {
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

    fun setBackgroundTimeoutMinutes(value: Int) = edit {
        putInt(KEY_BACKGROUND_TIMEOUT, value.coerceIn(0, 24 * 60))
    }

    fun setFollowBatterySaver(value: Boolean) = edit { putBoolean(KEY_FOLLOW_SAVER, value) }

    fun setSaverStillBackground(value: Boolean) = edit {
        putBoolean(KEY_SAVER_STILL_BACKGROUND, value)
    }

    fun setSaverReleaseScreen(value: Boolean) = edit {
        putBoolean(KEY_SAVER_RELEASE_SCREEN, value)
    }

    fun setSaverSkipPrefetch(value: Boolean) = edit {
        putBoolean(KEY_SAVER_SKIP_PREFETCH, value)
    }

    fun setAutoUpdateCheck(value: Boolean) = edit { putBoolean(KEY_AUTO_UPDATE, value) }

    fun noteUpdateCheck(at: Long) = edit { putLong(KEY_UPDATE_CHECKED_AT, at) }

    fun skipUpdateVersion(version: String?) = edit { putString(KEY_UPDATE_SKIPPED, version) }

    fun setDeveloperMode(value: Boolean) = edit { putBoolean(KEY_DEVELOPER_MODE, value) }

    fun setSpotifyBrowserToken(value: Boolean) = edit {
        putBoolean(KEY_SPOTIFY_BROWSER_TOKEN, value)
    }

    fun updateCacheServerUrl(value: String?) = edit {
        putString(KEY_CACHE_SERVER_URL, value?.trim()?.trimEnd('/'))
    }

    /**
     * A bearer key, so it belongs with the other credentials rather than with the settings.
     *
     * It authenticates to a server that may itself be holding an Apple Music token, which
     * makes it exactly the sort of thing that must not travel in a backup.
     */
    fun updateCacheServerKey(value: String?) = editSecrets {
        putString(KEY_CACHE_SERVER_KEY, value?.trim())
    }

    fun updateSpotifyWebToken(value: String?) = editSecrets {
        putString(KEY_SP_WEB_TOKEN, value?.trim())
    }

    fun setCacheServerExtras(value: Boolean) = edit {
        putBoolean(KEY_CACHE_SERVER_EXTRAS, value)
    }

    fun setArtworkSource(value: ArtworkSource) = edit {
        putString(KEY_ARTWORK_SOURCE, value.name)
    }

    fun setCacheServerMode(value: CacheServerMode) = edit {
        putString(KEY_CACHE_SERVER_MODE, value.name)
    }

    fun updateAmllBaseUrl(value: String?) = edit {
        putString(KEY_AMLL_URL, value?.trim()?.trimEnd('/'))
    }

    fun updateNeteaseCookie(value: String?) =
        editSecrets { putString(KEY_NETEASE_COOKIE, value?.trim()) }

    fun updateAppleDeveloperToken(value: String?) =
        editSecrets { putString(KEY_APPLE_DEV_TOKEN, value?.trim()) }

    fun updateAppleMusicUserToken(value: String?) =
        editSecrets { putString(KEY_APPLE_USER_TOKEN, value?.trim()) }

    fun updateAppleStorefront(value: String?) = edit {
        putString(KEY_APPLE_STOREFRONT, value?.trim()?.lowercase()?.takeIf { it.length == 2 })
    }

    // ---- ProviderCredentials ------------------------------------------------

    override val lrcLibBaseUrl: String get() = current.lrcLibBaseUrl
    override val neteaseBaseUrl: String get() = current.neteaseBaseUrl
    override val amllBaseUrl: String get() = current.amllBaseUrl

    // Null unless the developer options are on, so a URL left behind in preferences
    // cannot keep answering after the switch is turned off.
    override val spotifyWebToken: String?
        get() = current.spotifyWebToken?.takeIf { current.developerMode }

    override val cacheServerUrl: String?
        get() = current.cacheServerUrl?.takeIf { current.developerMode }

    override val spotifyBrowserTokenEnabled: Boolean
        get() = current.spotifyBrowserTokenActive

    override val cacheServerKey: String?
        get() = current.cacheServerKey?.takeIf { current.developerMode }
    override val neteaseCookie: String? get() = current.neteaseCookie
    override val musixmatchUserToken: String? get() = current.musixmatchUserToken
    override val appleDeveloperToken: String? get() = current.appleDeveloperToken
    override val appleMusicUserToken: String? get() = current.appleMusicUserToken
    override val appleStorefront: String get() = current.appleStorefront

    override var musixmatchGuestToken: String?
        get() = secrets.getString(KEY_MXM_GUEST_TOKEN, null)
        set(value) {
            secrets.edit().putString(KEY_MXM_GUEST_TOKEN, value).apply()
        }

    override var spDcCookie: String?
        get() = secrets.getString(KEY_SP_DC, null)
        set(value) = updateSpDcCookie(value)

    override var cachedSpotifyToken: String?
        get() = secrets.getString(KEY_SP_TOKEN, null)
        set(value) {
            secrets.edit().putString(KEY_SP_TOKEN, value).apply()
        }

    override var cachedSpotifyTokenExpiresAt: Long
        get() = secrets.getLong(KEY_SP_TOKEN_EXPIRY, 0L)
        set(value) {
            secrets.edit().putLong(KEY_SP_TOKEN_EXPIRY, value).apply()
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
        const val KEY_POPUP_STILL_BACKGROUND = "popup_still_background"

        const val KEY_PREFETCH_NEXT = "prefetch_next"
        const val KEY_PROVIDERS_ON = "providers_enabled"
        const val KEY_PROVIDER_ORDER = "provider_order"
        const val KEY_PLAYERS_IGNORED = "players_ignored"
        const val KEY_PLAYERS_SEEN = "players_seen"

        /**
         * The file the credential keys live in. Named in `backup_rules.xml` and
         * `data_extraction_rules.xml`, which is the whole point of it existing.
         */
        const val SECRETS_FILE = "melisma-credentials"

        /** What the same two files were called before the app was renamed. See adoptPreRenameFiles. */
        const val LEGACY_FILE = "better-lyrics"
        const val LEGACY_SECRETS_FILE = "better-lyrics-credentials"

        /** Everything that authenticates as the user. Nothing here may be backed up. */
        val SECRET_KEYS = listOf(
            "sp_dc", "sp_access_token", "sp_access_token_expiry",
            "mxm_token", "mxm_user_token",
            "netease_cookie", "apple_dev_token", "apple_user_token",
            "cache_server_key", "sp_web_token",
        )

        const val KEY_SP_DC = "sp_dc"
        const val KEY_SP_TOKEN = "sp_access_token"
        const val KEY_SP_TOKEN_EXPIRY = "sp_access_token_expiry"
        const val KEY_MXM_GUEST_TOKEN = "mxm_token"
        const val KEY_MXM_USER_TOKEN = "mxm_user_token"
        const val KEY_LRCLIB_URL = "lrclib_url"
        const val KEY_NETEASE_URL = "netease_url"
        const val KEY_AMLL_URL = "amll_url"
        const val KEY_BACKGROUND_TIMEOUT = "background_timeout_minutes"
        const val KEY_FOLLOW_SAVER = "follow_battery_saver"
        const val KEY_SAVER_STILL_BACKGROUND = "saver_still_background"
        const val KEY_SAVER_RELEASE_SCREEN = "saver_release_screen"
        const val KEY_SAVER_SKIP_PREFETCH = "saver_skip_prefetch"
        const val KEY_AUTO_UPDATE = "auto_update_check"
        const val KEY_UPDATE_CHECKED_AT = "update_checked_at"
        const val KEY_UPDATE_SKIPPED = "update_skipped_version"
        const val KEY_DEVELOPER_MODE = "developer_mode"
        const val KEY_SPOTIFY_BROWSER_TOKEN = "spotify_browser_token"
        const val KEY_CACHE_SERVER_URL = "cache_server_url"
        const val KEY_CACHE_SERVER_KEY = "cache_server_key"
        const val KEY_CACHE_SERVER_MODE = "cache_server_mode"
        const val KEY_SP_WEB_TOKEN = "sp_web_token"
        const val KEY_ARTWORK_SOURCE = "artwork_source"
        const val KEY_CACHE_SERVER_EXTRAS = "cache_server_extras"
        const val KEY_NETEASE_COOKIE = "netease_cookie"
        const val KEY_APPLE_DEV_TOKEN = "apple_dev_token"
        const val KEY_APPLE_USER_TOKEN = "apple_user_token"
        const val KEY_APPLE_STOREFRONT = "apple_storefront"
    }
}
