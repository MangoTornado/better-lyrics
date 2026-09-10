package com.melisma.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Cancelling Android's install screen.
 *
 * Reported as "the update dialogue stays there forever and you cannot back out of it. You can't
 * restart the update. Just stuck there." Both halves were true, and the cause is that Android tells
 * an app *nothing* when the user dismisses its install confirmation: the app is simply left in the
 * state it was in when it handed the file over. That state offered no buttons and ignored the back
 * gesture, because it was treated as work in progress rather than as a question.
 *
 * It survived release because the successful path never shows it — a completed install replaces the
 * process, so the only way to see this state again is to *not* install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CancelledInstallTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore

    private val release = AvailableRelease(
        versionName = "0.2.0",
        notes = "",
        apkUrl = "https://example.invalid/melisma.apk",
        apkSizeBytes = 4,
        pageUrl = "https://example.invalid/releases/0.2.0",
    )

    /** Every fetch and every hand-over, so a second attempt can be told from a first. */
    private var fetches = 0
    private var handOvers = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsStore(context)
        fetches = 0
        handOvers = 0
        File(context.cacheDir, "updates").deleteRecursively()
    }

    private fun updater(handOverSucceeds: Boolean = true) = Updater(
        context,
        settings,
        fetchLatest = { release },
        updatableInPlace = true,
        fetchApk = { _, target, _ ->
            fetches++
            target.writeText("apk!")
        },
        // Standing in for the system installer, which reports neither success nor cancellation.
        handOver = {
            handOvers++
            handOverSucceeds
        },
    )

    @Test
    fun `the prompt can be answered after the installer is cancelled`() = runBlocking {
        val updater = updater()
        updater.downloadAndInstall(release)

        // Where cancelling leaves us: handed over, nothing heard back.
        assertEquals(Updater.State.ReadyToInstall(release), updater.state.value)

        updater.dismiss()
        assertEquals(
            "downloaded-and-not-installed is a question, not work in progress",
            Updater.State.Idle,
            updater.state.value,
        )
    }

    @Test
    fun `installing again does not download again`() = runBlocking {
        val updater = updater()
        updater.downloadAndInstall(release)
        assertEquals(1, fetches)
        assertEquals(1, handOvers)

        // Pressing Install a second time, which is the whole point of being able to.
        updater.downloadAndInstall(release)
        assertEquals("the file is already there; asking for it again is 34 MB for a mis-tap", 1, fetches)
        assertEquals(2, handOvers)
    }

    @Test
    fun `a half-finished download is not offered to the installer`() = runBlocking {
        // What an interrupted download leaves behind. The real one writes to `.part` and renames at
        // the end, so this name can only mean finished — and anything else must be fetched again
        // rather than handed over as a truncated APK, which fails looking like a corrupt release.
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        File(directory, "melisma-0.2.0.apk.part").writeText("ha")

        updater().downloadAndInstall(release)
        assertEquals(1, fetches)
        assertEquals(1, handOvers)
    }

    @Test
    fun `a download in flight still cannot be dismissed`() = runBlocking {
        // The other half of the fix has to stay honest: a download does end on its own, and letting
        // it be dismissed would put the prompt back the moment it finished.
        lateinit var updater: Updater
        updater = Updater(
            context,
            settings,
            fetchLatest = { release },
            updatableInPlace = true,
            fetchApk = { _, target, publish ->
                publish(0.5f)
                // Mid-download is exactly when someone reaches for the back gesture.
                updater.dismiss()
                target.writeText("apk!")
            },
            handOver = { true },
        )

        updater.downloadAndInstall(release)
        assertTrue(
            "a download must not be answerable, and must still arrive",
            updater.state.value is Updater.State.ReadyToInstall,
        )
    }

    @Test
    fun `a check does not throw away an update already downloaded`() = runBlocking {
        val updater = updater()
        updater.downloadAndInstall(release)

        // Coming back to the app runs a check. Losing the prompt to it would leave the APK on disk
        // with nothing on screen pointing at it — and if the check had failed, for nothing.
        updater.check(automatic = false)
        assertEquals(Updater.State.ReadyToInstall(release), updater.state.value)
    }

    @Test
    fun `a failed hand-over says what to do instead`() = runBlocking {
        val updater = updater(handOverSucceeds = false)
        updater.downloadAndInstall(release)

        val state = updater.state.value
        assertTrue(state is Updater.State.Failed)
        assertTrue((state as Updater.State.Failed).message.contains("downloaded"))
        // And that one is dismissible too, as it always was.
        updater.dismiss()
        assertEquals(Updater.State.Idle, updater.state.value)
    }
}
