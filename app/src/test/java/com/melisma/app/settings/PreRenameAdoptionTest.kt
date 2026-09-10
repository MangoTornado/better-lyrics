package com.melisma.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Taking over the preference files written when the app was called Better Lyrics.
 *
 * The rename changed the application id, so Android treats this as a different app and hands it empty
 * storage. On a phone that had the old build installed the settings are still on disk under the old
 * file names with nothing reading them — which looks exactly like a factory reset to the person who
 * spent an evening pasting tokens in.
 *
 * The dangerous half is the guard: this must never touch settings that are already there. A migration
 * that reverts a live install to a stale copy of itself is worse than no migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreRenameAdoptionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        for (file in listOf(
            SettingsStore.LEGACY_FILE,
            SettingsStore.LEGACY_SECRETS_FILE,
            "melisma",
            SettingsStore.SECRETS_FILE,
        )) {
            context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun legacy(name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun `settings and tokens are adopted from the old files`() {
        legacy(SettingsStore.LEGACY_FILE).edit()
            .putInt("sync_offset_ms", -250)
            .putBoolean("keep_screen_on", false)
            .putStringSet("players_ignored", setOf("com.google.android.youtube"))
            .commit()
        legacy(SettingsStore.LEGACY_SECRETS_FILE).edit()
            .putString("sp_dc", "AQD-not-a-real-cookie")
            .commit()

        val store = SettingsStore(context)

        assertEquals(-250, store.current.syncOffsetMs)
        assertEquals(false, store.current.keepScreenOn)
        assertEquals(setOf("com.google.android.youtube"), store.current.ignoredPlayers)
        assertEquals("AQD-not-a-real-cookie", store.spDcCookie)
    }

    @Test
    fun `settings already here are never overwritten`() {
        // The case that would be a data-loss bug: a live install, and a stale legacy file still on
        // disk because adoption copies rather than moves.
        context.getSharedPreferences("melisma", Context.MODE_PRIVATE).edit()
            .putInt("sync_offset_ms", -900)
            .commit()
        legacy(SettingsStore.LEGACY_FILE).edit()
            .putInt("sync_offset_ms", -250)
            .commit()

        assertEquals(-900, SettingsStore(context).current.syncOffsetMs)
    }

    @Test
    fun `a fresh install with nothing to adopt gets the defaults`() {
        val store = SettingsStore(context)
        assertEquals(Settings().syncOffsetMs, store.current.syncOffsetMs)
        assertEquals(Settings().keepScreenOn, store.current.keepScreenOn)
    }

    @Test
    fun `the originals are left where they are`() {
        legacy(SettingsStore.LEGACY_FILE).edit().putInt("sync_offset_ms", -250).commit()

        SettingsStore(context)

        // Copied, not moved: if adoption turns out to have gone wrong, the only copy of somebody's
        // settings must not have been the casualty.
        assertTrue(legacy(SettingsStore.LEGACY_FILE).contains("sync_offset_ms"))
    }
}
