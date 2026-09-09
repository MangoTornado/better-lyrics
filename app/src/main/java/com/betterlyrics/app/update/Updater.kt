package com.betterlyrics.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.betterlyrics.app.BuildConfig
import com.betterlyrics.app.lyrics.provider.Http
import com.betterlyrics.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds, downloads and hands over updates.
 *
 * The app is installed from an APK rather than a store, so there is no update mechanism
 * unless it brings its own. This is that: a check against the releases API, a download, and
 * then Android's ordinary install screen — the same one the user saw when they installed it
 * the first time. Nothing is installed silently, and nothing can be: handing the APK to the
 * system installer is the only route an app without privileged permissions has, and the
 * confirmation it shows is the user's veto.
 *
 * Two things it deliberately does not do. It does not install a differently signed APK —
 * Android refuses that outright, which is the protection that makes this safe at all. And it
 * does nothing on a debug build, whose application id ends in `.debug`: a release APK would
 * install alongside it as a second app rather than updating anything.
 */
class Updater(
    private val context: Context,
    private val settingsStore: SettingsStore,
    /**
     * How the newest release is looked up. A parameter only so a test can decide when the check
     * reaches the network, which is the whole question the throttle answers.
     */
    private val fetchLatest: suspend () -> AvailableRelease? = { UpdateChecker.latest() },
    /**
     * Whether installing over this build would work — false for a debug build. Injectable because
     * unit tests *are* the debug build, so the real value switches off the code under test.
     */
    private val updatableInPlace: Boolean = !BuildConfig.DEBUG,
) {

    sealed interface State {
        data object Idle : State
        data object Checking : State

        /** Checked, and this is already the newest. */
        data class UpToDate(val checkedAt: Long) : State

        data class Available(val release: AvailableRelease) : State

        /** [fraction] is -1 while the total size is unknown. */
        data class Downloading(val release: AvailableRelease, val fraction: Float) : State

        /** The APK is on disk and the system installer has been asked to take it. */
        data class ReadyToInstall(val release: AvailableRelease) : State

        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Whether this run has already looked.
     *
     * The launch check used to be throttled by wall clock, which made it miss the case it exists
     * for. Pressing "Check now" records the time too, so one manual check bought six hours of
     * launches that did nothing — the app would only ever find a release when asked, which is
     * exactly backwards. The interval's real job is to stop repeat checks *inside* one run, so that
     * is what it does now, and starting the app always looks.
     */
    @Volatile
    private var checkedThisLaunch = false

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    /**
     * True when installing an update over this build would actually work.
     *
     * False for a debug build, which has a different application id and would be joined by
     * the release rather than replaced by it.
     */
    val canUpdateInPlace: Boolean get() = updatableInPlace

    /**
     * Look for a newer release.
     *
     * @param automatic true for the check on launch, which respects the setting and the
     *   interval and stays silent about failures. A check the user asked for ignores both
     *   and reports what went wrong.
     */
    suspend fun check(automatic: Boolean) {
        val settings = settingsStore.current
        if (automatic) {
            if (!settings.autoUpdateCheck || !canUpdateInPlace) return
            // The first look of each run always happens; the interval only holds off the repeats,
            // which come from the player screen being composed again rather than from a launch.
            if (checkedThisLaunch) {
                val since = System.currentTimeMillis() - settings.lastUpdateCheckAt
                if (since in 0 until CHECK_INTERVAL_MS) return
            }
        }
        if (_state.value is State.Downloading) return

        checkedThisLaunch = true
        _state.value = State.Checking
        val release = runCatching { fetchLatest() }
            .onFailure { Log.w(TAG, "update check failed: ${it.message}") }
            .getOrNull()

        settingsStore.noteUpdateCheck(System.currentTimeMillis())

        _state.value = when {
            release == null ->
                if (automatic) State.Idle else State.Failed("Could not read the releases page")

            !UpdateChecker.isNewer(release.versionName, currentVersion) ->
                State.UpToDate(System.currentTimeMillis())

            // A version the user chose to skip stays skipped until a later one appears.
            automatic && release.versionName == settings.skippedUpdateVersion -> State.Idle

            else -> State.Available(release)
        }
    }

    /** Stop offering this version. A newer one will still be offered. */
    fun skip(release: AvailableRelease) {
        settingsStore.skipUpdateVersion(release.versionName)
        _state.value = State.Idle
    }

    /** Put the prompt away without skipping: it comes back at the next check. */
    fun dismiss() {
        if (_state.value is State.Available || _state.value is State.Failed ||
            _state.value is State.UpToDate
        ) {
            _state.value = State.Idle
        }
    }

    /**
     * Download the APK and hand it to the system installer.
     *
     * Streamed to the cache directory, which Android reclaims on its own — an abandoned
     * download must not cost the user storage forever.
     */
    suspend fun downloadAndInstall(release: AvailableRelease) {
        _state.value = State.Downloading(release, if (release.apkSizeBytes > 0) 0f else -1f)

        val file = runCatching { download(release) }
            .onFailure { Log.w(TAG, "download failed: ${it.message}") }
            .getOrNull()

        if (file == null) {
            _state.value = State.Failed("The download did not finish")
            return
        }

        _state.value = State.ReadyToInstall(release)
        if (!install(file)) {
            _state.value = State.Failed(
                "Could not open the installer. The APK is downloaded — " +
                    "install it from your Downloads or Files app.",
            )
        }
    }

    private suspend fun download(release: AvailableRelease): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        // One file per version, replaced rather than accumulated.
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, "better-lyrics-${release.versionName}.apk")

        Http.client.newCall(Http.request(release.apkUrl)).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("empty response")
            val total = release.apkSizeBytes.takeIf { it > 0 } ?: body.contentLength()

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPublished = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        // Publishing every buffer would be a state change every few
                        // milliseconds for a bar that moves in whole percents.
                        if (total > 0 && written - lastPublished > total / 100) {
                            lastPublished = written
                            _state.value =
                                State.Downloading(release, written.toFloat() / total)
                        }
                    }
                }
            }
        }
        target
    }

    /**
     * Ask Android to install [file].
     *
     * `ACTION_VIEW` on a content URI from our own FileProvider, which is what shows the
     * familiar "do you want to install this update?" screen. The system checks the signature
     * against the installed app; a build signed with a different key is refused there, not
     * here.
     */
    private fun install(file: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse {
        Log.w(TAG, "could not start the installer: ${it.message}")
        false
    }

    private companion object {
        const val TAG = "Updater"
        const val DIRECTORY = "updates"

        /**
         * Six hours, between repeat checks within one run.
         *
         * Not a floor on launches: see [checkedThisLaunch]. One unauthenticated GET against the
         * releases API is cheap and GitHub allows sixty an hour per address, so looking once when
         * the app starts costs nothing worth saving.
         */
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
