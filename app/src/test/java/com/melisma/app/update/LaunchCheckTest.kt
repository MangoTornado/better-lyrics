package com.melisma.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Starting the app looks for a release.
 *
 * Reported as "the app doesn't detect a new update until I press the check button even if auto
 * update is on". It did check on launch — but behind a six-hour wall-clock interval that the manual
 * check also reset, so pressing the button once bought six hours of launches that did nothing. The
 * only way to find a release was to ask, which is the opposite of the point.
 *
 * A regression here is silent: the app simply stops noticing releases, and nobody finds out until
 * they go and look, which is how this was found in the first place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchCheckTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private var looks = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsStore(context)
        looks = 0
    }

    private fun updater() = Updater(
        context,
        settings,
        fetchLatest = {
            looks++
            null
        },
        // Unit tests are the debug build, whose real answer here is "cannot update in place" — which
        // would switch off the code under test.
        updatableInPlace = true,
    )

    @Test
    fun `a launch checks even when one just happened`() = runBlocking {
        // As a manual check leaves things: the clock says "checked a moment ago".
        settings.noteUpdateCheck(System.currentTimeMillis())

        // A fresh Updater is a fresh process, which is what a launch is.
        updater().check(automatic = true)

        assertEquals("a launch must look regardless of the clock", 1, looks)
    }

    @Test
    fun `the interval still holds off repeats within one run`() = runBlocking {
        val updater = updater()

        updater.check(automatic = true)
        assertEquals(1, looks)

        // The player screen being composed again is not a launch, and must not spend a request.
        updater.check(automatic = true)
        updater.check(automatic = true)
        assertEquals("repeats inside one run stay throttled", 1, looks)
    }

    @Test
    fun `the setting still wins`() = runBlocking {
        settings.setAutoUpdateCheck(false)

        updater().check(automatic = true)

        assertEquals("auto-check off means no request", 0, looks)
    }

    @Test
    fun `a check the user asked for is never throttled`() = runBlocking {
        settings.setAutoUpdateCheck(false)
        settings.noteUpdateCheck(System.currentTimeMillis())

        val updater = updater()
        updater.check(automatic = false)
        updater.check(automatic = false)

        assertEquals("asking always asks", 2, looks)
    }
}
