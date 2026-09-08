package com.betterlyrics.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Credentials must not be in the file Android backs up.
 *
 * The app tells the user their cookies and tokens stay on this device, and backup rules
 * work per preferences file — so the promise is only kept if the credentials are in a file
 * of their own that the rules exclude. A key written to the wrong file ends up in a cloud
 * backup, which is a live signed-in session restored onto hardware the user may not own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialStorageTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings().edit().clear().commit()
        secrets().edit().clear().commit()
    }

    private fun settings() =
        context.getSharedPreferences("better-lyrics", Context.MODE_PRIVATE)

    private fun secrets() =
        context.getSharedPreferences(SettingsStore.SECRETS_FILE, Context.MODE_PRIVATE)

    @Test
    fun `a cookie is written only to the excluded file`() {
        val store = SettingsStore(context)
        store.updateSpDcCookie("AQD-secret")

        assertEquals("AQD-secret", secrets().getString(SettingsStore.KEY_SP_DC, null))
        assertNull(settings().getString(SettingsStore.KEY_SP_DC, null))
        // And it is still readable, or the split would have broken the providers.
        assertEquals("AQD-secret", store.current.spDcCookie)
    }

    @Test
    fun `every credential lands in the excluded file`() {
        val store = SettingsStore(context)
        store.updateSpDcCookie("cookie")
        store.updateMusixmatchUserToken("mxm")
        store.updateNeteaseCookie("ne")
        store.updateAppleDeveloperToken("jwt")
        store.updateAppleMusicUserToken("user")
        store.updateCacheServerKey("bearer-key")
        store.musixmatchGuestToken = "guest"
        store.cachedSpotifyToken = "access"
        store.cachedSpotifyTokenExpiresAt = 123L

        // Nothing from the credential list may appear in the backed-up file.
        val leaked = SettingsStore.SECRET_KEYS.filter { settings().contains(it) }
        assertTrue("leaked into the backed-up file: $leaked", leaked.isEmpty())
        assertTrue(SettingsStore.SECRET_KEYS.any { secrets().contains(it) })
    }

    @Test
    fun `an older install's credentials are moved out on first read`() {
        // What a version before the split left behind.
        settings().edit()
            .putString(SettingsStore.KEY_SP_DC, "old-cookie")
            .putString(SettingsStore.KEY_APPLE_DEV_TOKEN, "old-jwt")
            .putLong(SettingsStore.KEY_SP_TOKEN_EXPIRY, 999L)
            .putFloat(SettingsStore.KEY_FONT_SCALE, 1.4f)
            .commit()

        val store = SettingsStore(context)

        assertEquals("old-cookie", secrets().getString(SettingsStore.KEY_SP_DC, null))
        assertEquals("old-jwt", secrets().getString(SettingsStore.KEY_APPLE_DEV_TOKEN, null))
        assertEquals(999L, secrets().getLong(SettingsStore.KEY_SP_TOKEN_EXPIRY, 0L))
        assertFalse(settings().contains(SettingsStore.KEY_SP_DC))
        assertFalse(settings().contains(SettingsStore.KEY_APPLE_DEV_TOKEN))
        assertFalse(settings().contains(SettingsStore.KEY_SP_TOKEN_EXPIRY))

        // Nothing was lost: the credentials still read back, and ordinary settings are
        // untouched — they are the part that is supposed to survive a restore.
        assertEquals("old-cookie", store.current.spDcCookie)
        assertEquals("old-jwt", store.current.appleDeveloperToken)
        assertEquals(1.4f, store.current.fontScale, 0.001f)
    }
}
