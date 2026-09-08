package com.betterlyrics.app.settings

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Translation used to be one switch covering two unrelated things, and the boolean it was
 * stored as is still on every existing install. These pin both halves of the split: that an
 * upgrade lands somewhere sensible, and that the chip over the lyrics restores the source
 * the user actually picked rather than starting a model download on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationSettingsTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("better-lyrics", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun store() = SettingsStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `a fresh install shows the free translations`() {
        // They cost nothing and were written by a person. Nothing is downloaded.
        assertEquals(TranslationSource.PROVIDER, store().current.translationSource)
    }

    @Test
    fun `someone who had the old switch on wanted the machine translator`() {
        // That is all the old switch did.
        prefs.edit().putBoolean(SettingsStore.KEY_TRANSLATE_LEGACY, true).commit()
        assertEquals(TranslationSource.DEVICE, store().current.translationSource)
    }

    @Test
    fun `someone who had it off was declining the download, not the translations`() {
        // There was no way to ask for provider translations by themselves, so "off" cannot
        // be read as having refused them.
        prefs.edit().putBoolean(SettingsStore.KEY_TRANSLATE_LEGACY, false).commit()
        assertEquals(TranslationSource.PROVIDER, store().current.translationSource)
    }

    @Test
    fun `an explicit choice outranks the old boolean`() {
        prefs.edit()
            .putBoolean(SettingsStore.KEY_TRANSLATE_LEGACY, true)
            .putString(SettingsStore.KEY_TRANSLATE_SOURCE, TranslationSource.OFF.name)
            .commit()
        assertEquals(TranslationSource.OFF, store().current.translationSource)
    }

    @Test
    fun `the toggle puts back the source that was in use`() {
        val store = store()
        store.setTranslationSource(TranslationSource.DEVICE)

        store.toggleTranslation()
        assertEquals(TranslationSource.OFF, store.current.translationSource)

        store.toggleTranslation()
        assertEquals(TranslationSource.DEVICE, store.current.translationSource)
    }

    @Test
    fun `the toggle never starts a download on its own`() {
        val store = store()
        store.setTranslationSource(TranslationSource.OFF)
        // Nothing has ever been chosen, so turning it on must pick the free source rather
        // than a 30 MB model download the user did not ask for.
        store.toggleTranslation()
        assertEquals(TranslationSource.PROVIDER, store.current.translationSource)
    }
}
