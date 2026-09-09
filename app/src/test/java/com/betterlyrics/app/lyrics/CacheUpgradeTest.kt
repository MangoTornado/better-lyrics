package com.betterlyrics.app.lyrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricLine
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.lyrics.provider.LyricsProvider
import com.betterlyrics.app.lyrics.provider.LyricsRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * A cached answer should get better when a source that never answered finally can.
 *
 * The cache stored only the winning document, which says nothing about who else was asked — so a
 * track cached while a source was off, unconfigured, or simply down kept the poorer answer for the
 * full thirty days, and nothing knew a better one had ever been missed. Recording what each source
 * said is what makes the difference decidable later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheUpgradeTest {

    private lateinit var context: Context
    private lateinit var cache: LyricsCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cache = LyricsCache(context)
        runBlocking { cache.clear() }
    }

    private fun document(kind: LyricsKind) = LyricsDocument(
        kind = kind,
        lines = listOf(LyricLine(role = LineRole.LEAD, startMs = 0, endMs = 1_000, text = "a line")),
        providerName = "test",
        providerId = "test",
    )

    @Test
    fun `what each source said survives a round trip`() = runBlocking {
        cache.put(
            "key",
            document(LyricsKind.LINE),
            mapOf(
                "apple" to LyricsCache.Outcome.LYRICS,
                "netease" to LyricsCache.Outcome.NONE,
                "amll" to LyricsCache.Outcome.FAILED,
            ),
        )

        val hit = cache.get("key") as LyricsCache.Result.Hit
        assertEquals(LyricsCache.Outcome.LYRICS, hit.outcomes["apple"])
        assertEquals(LyricsCache.Outcome.NONE, hit.outcomes["netease"])
        assertEquals(LyricsCache.Outcome.FAILED, hit.outcomes["amll"])
        assertTrue(hit.outcomesAtMs > 0)
    }

    @Test
    fun `an entry written before outcomes existed still loads`() = runBlocking {
        // Only the fields the old version wrote. Reading these back must not fail, or the first run
        // after an update would throw away everybody's cache.
        val file = java.io.File(context.cacheDir, "lyrics-cache").apply { mkdirs() }
            .resolve("${sha1("legacy")}.json")
        file.writeText(
            """{"savedAtMs":${System.currentTimeMillis()},"document":${
                kotlinx.serialization.json.Json.encodeToString(
                    LyricsDocument.serializer(),
                    document(LyricsKind.LINE),
                )
            }}""",
        )

        val hit = cache.get("legacy") as? LyricsCache.Result.Hit
        assertNotNull("a legacy entry must still be readable", hit)
        // And with no outcomes, every source counts as never asked — which is correct, because
        // there is no evidence any of them were.
        assertTrue(hit!!.outcomes.isEmpty())
    }

    @Test
    fun `an upgrade does not extend how long the words live`() = runBlocking {
        val found = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(20)
        cache.put("key", document(LyricsKind.LINE), emptyMap(), savedAtMs = found)

        // Re-asking a source is not a new find. If the upgrade reset the age, a track played once a
        // fortnight would never expire and could never be re-fetched from scratch.
        cache.put("key", document(LyricsKind.SYLLABLE), mapOf("amll" to LyricsCache.Outcome.LYRICS), savedAtMs = found)

        val hit = cache.get("key") as LyricsCache.Result.Hit
        assertEquals(found, hit.savedAtMs)
        assertEquals(LyricsKind.SYLLABLE, hit.document.kind)
        // The outcomes, though, were recorded just now.
        assertTrue(hit.outcomesAtMs > found)
    }

    // ---- which sources are worth re-asking ----------------------------------

    private fun provider(id: String) = object : LyricsProvider {
        override val id = id
        override val displayName = id
        override val isConfigured = true
        override suspend fun fetch(request: LyricsRequest): LyricsDocument? = null
    }

    private val candidates = listOf(provider("apple"), provider("netease"), provider("amll"))

    private fun stale(
        outcomes: Map<String, LyricsCache.Outcome>,
        ageMs: Long = 0L,
    ): Set<String> = staleProviders(candidates, outcomes, ageMs).map { it.id }.toSet()

    @Test
    fun `a source that was never asked is worth asking`() {
        // AMLL absent: it was off, unconfigured, or did not exist in the version that cached this.
        val outcomes = mapOf(
            "apple" to LyricsCache.Outcome.LYRICS,
            "netease" to LyricsCache.Outcome.NONE,
        )
        assertEquals(setOf("amll"), stale(outcomes))
    }

    @Test
    fun `a source that answered nothing is left alone`() {
        // The case that would otherwise cost a request per source on every single play, forever,
        // for an answer that will not have changed.
        val outcomes = candidates.associate { it.id to LyricsCache.Outcome.NONE }
        assertEquals(emptySet<String>(), stale(outcomes))
    }

    @Test
    fun `a source that failed is retried, but not straight away`() {
        val outcomes = mapOf(
            "apple" to LyricsCache.Outcome.LYRICS,
            "netease" to LyricsCache.Outcome.LYRICS,
            "amll" to LyricsCache.Outcome.FAILED,
        )

        // A service having a bad day must not be asked once per play.
        assertEquals(emptySet<String>(), stale(outcomes, ageMs = TimeUnit.MINUTES.toMillis(5)))
        // But a token fixed this morning should be used this afternoon.
        assertEquals(setOf("amll"), stale(outcomes, ageMs = TimeUnit.HOURS.toMillis(7)))
    }

    @Test
    fun `an entry with no outcomes treats every source as unasked`() {
        assertEquals(setOf("apple", "netease", "amll"), stale(emptyMap()))
    }

    private fun sha1(value: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
