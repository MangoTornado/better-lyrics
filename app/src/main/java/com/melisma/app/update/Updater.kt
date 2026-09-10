package com.melisma.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.melisma.app.BuildConfig
import com.melisma.app.lyrics.provider.Http
import com.melisma.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

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
    /**
     * How the APK is fetched, given the release, where to put it, and a progress callback taking a
     * fraction. Null means the real download.
     *
     * A seam for the tests, because the interesting behaviour here is what happens *around* the
     * download — whether a second attempt spends the bytes again, and whether the prompt can still
     * be answered afterwards — and none of that should need a network to check.
     */
    private val fetchApk: (suspend (AvailableRelease, File, (Float) -> Unit) -> Unit)? = null,
    /**
     * How the finished APK is handed to Android. Null means the real installer.
     *
     * Also a seam, and the important one: Android tells an app *nothing* when the user cancels the
     * install screen, so the only way to reproduce that case is to hand over and then look at what
     * state we were left in.
     */
    private val handOver: ((File) -> Boolean)? = null,
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
        // A download in flight, or one finished and waiting for a tap, is a better thing to be
        // showing than the result of a fresh check. Overwriting the latter would throw away the
        // prompt for an update that is already on disk — and if the check failed, throw it away for
        // nothing.
        if (_state.value is State.Downloading || _state.value is State.ReadyToInstall) return

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

    /**
     * Put the prompt away without skipping: it comes back at the next check.
     *
     * [State.ReadyToInstall] is included, and that is the fix for a prompt nobody could escape.
     * Android says nothing at all when the user cancels its install screen, so that state is
     * where the app is left — and it used to be treated as busy, which meant no buttons and a
     * dialog that ignored the back gesture. Downloaded and not installed is not busy; it is a
     * decision waiting on the user, and every decision needs a way to say no.
     *
     * A download in flight is deliberately not dismissible: it ends on its own, and its state is
     * replaced when it does.
     */
    fun dismiss() {
        if (_state.value is State.Downloading || _state.value is State.Checking) return
        _state.value = State.Idle
    }

    /**
     * Download the APK and hand it to the system installer.
     *
     * Streamed to the cache directory, which Android reclaims on its own — an abandoned
     * download must not cost the user storage forever.
     */
    suspend fun downloadAndInstall(release: AvailableRelease) {
        // A copy already on disk is handed straight over. This is what makes "Install" work a
        // second time after the system installer was cancelled — the file is downloaded, the only
        // thing that failed was the confirmation, and asking for 34 MB again to fix a mis-tap is
        // not on. Only a *finished* download qualifies: an interrupted one never gets this name.
        val ready = completedApk(release)
        val file = ready ?: run {
            _state.value = State.Downloading(release, if (release.apkSizeBytes > 0) 0f else -1f)
            runCatching { download(release) }
                .onFailure { Log.w(TAG, "download failed: ${it.message}") }
                .getOrNull()
        }

        if (file == null) {
            _state.value = State.Failed("The download did not finish")
            return
        }

        _state.value = State.ReadyToInstall(release)
        if (!(handOver ?: ::install)(file)) {
            _state.value = State.Failed(
                "Could not open the installer. The APK is downloaded — " +
                    "install it from your Downloads or Files app.",
            )
        }
    }

    /** Where a given release's APK lives once it is fully downloaded. */
    private fun apkFor(release: AvailableRelease) =
        File(File(context.cacheDir, DIRECTORY), "melisma-${release.versionName}.apk")

    /**
     * The APK for this release, if a complete one is on disk.
     *
     * "Complete" is decided by the name rather than by measuring: [download] writes to a `.part`
     * file and renames it only once the last byte has arrived, so a file with the final name cannot
     * be a half-downloaded one. Guessing from the length instead would hand the installer a
     * truncated APK, which fails in a way that looks like a corrupt release.
     */
    private fun completedApk(release: AvailableRelease): File? =
        apkFor(release).takeIf { it.isFile && it.length() > 0 }

    private suspend fun download(release: AvailableRelease): File = withContext(Dispatchers.IO) {
        File(context.cacheDir, DIRECTORY).mkdirs()
        val target = apkFor(release)
        // One file per version, replaced rather than accumulated.
        target.parentFile?.listFiles()?.forEach { it.delete() }

        val fetch = fetchApk
        if (fetch != null) {
            fetch(release, target) { fraction -> _state.value = State.Downloading(release, fraction) }
            return@withContext target
        }

        // Written under a different name and renamed at the end, so the finished name means
        // finished. Without that, a download cut off halfway leaves a file that looks complete and
        // gets handed to the installer on the next attempt.
        val part = File(target.parentFile, target.name + ".part")
        http.newCall(Http.request(release.apkUrl)).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("empty response")
            val total = release.apkSizeBytes.takeIf { it > 0 } ?: body.contentLength()

            body.byteStream().use { input ->
                part.outputStream().use { output ->
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
        if (!part.renameTo(target)) error("could not finish writing the download")
        target
    }

    /**
     * A client of its own for the download, and one line is the whole reason.
     *
     * The shared client caps an entire call at twenty seconds, which is right for a lyrics lookup
     * and hopeless for a 34 MB APK: on anything slower than about 14 Mbps every update would have
     * failed with "the download did not finish", the same message as a real failure, and the app
     * would never have updated itself on a mobile connection. The read timeout stays, so a
     * connection that dies still gives up rather than hanging the prompt forever; it is only the
     * overall limit that has no business being here.
     */
    private val http: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
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
