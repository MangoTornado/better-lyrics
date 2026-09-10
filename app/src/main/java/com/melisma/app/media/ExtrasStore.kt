package com.melisma.app.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The tempo and artwork addresses the app has learned, kept so they only have to be learned once.
 *
 * The same argument as [IsrcStore], and for the same reason: what produced these expires and what
 * they are does not. A tempo comes from Spotify's audio analysis, which needs a live access token
 * good for under an hour — and which Spotify has since closed to new applications, so a tempo once
 * lost may not be obtainable again at all. A cover address, once known, is true for as long as the
 * release exists.
 *
 * Without this the whole lot was re-fetched on every single play, and on any play without a working
 * token the beat simply did not happen — for a track whose tempo the app had already been told.
 *
 * **Addresses, not images.** The URLs are on public CDNs and need no credential, so keeping them
 * means artwork still appears months later with no token at all; the download is cheap and the
 * platform's own HTTP cache is better placed to avoid it than a second cache here would be. Storing
 * the bitmaps would mean an eviction policy and megabytes on disk for a saving the network already
 * makes.
 */
class ExtrasStore(context: Context) {

    @Serializable
    data class Known(
        val tempo: Float? = null,
        val coverUrl: String? = null,
        val artistImageUrl: String? = null,
    ) {
        val isEmpty: Boolean get() = tempo == null && coverUrl == null && artistImageUrl == null
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Loaded once, then answered from memory: this is consulted on every track change. */
    private val known = LinkedHashMap<String, Known>()
    private var loaded = false

    suspend fun get(trackKey: String): Known? = withContext(Dispatchers.IO) {
        load()
        synchronized(known) { known[trackKey] }
    }

    /**
     * Remember what was learned about a track, field by field.
     *
     * Merged rather than replaced, and only ever filling gaps. Three sources report these — Spotify,
     * Apple and the cache server — and they know different things: Spotify is the only one with a
     * tempo, Apple has the most durable artwork. A later source with no tempo must not erase the one
     * an earlier source found, which is what assigning the whole record would do.
     */
    suspend fun put(
        trackKey: String,
        tempo: Float? = null,
        coverUrl: String? = null,
        artistImageUrl: String? = null,
    ) = withContext(Dispatchers.IO) {
        load()

        val changed = synchronized(known) {
            val before = known[trackKey]
            val after = Known(
                tempo = tempo ?: before?.tempo,
                coverUrl = coverUrl?.takeIf { it.isNotBlank() } ?: before?.coverUrl,
                artistImageUrl = artistImageUrl?.takeIf { it.isNotBlank() } ?: before?.artistImageUrl,
            )
            when {
                after.isEmpty -> false
                after == before -> false
                else -> {
                    // Oldest out first, as the ISRC index does: a listener who plays thousands of
                    // tracks keeps the recent ones and the rest cost a lookup they needed anyway.
                    if (before == null && known.size >= CAPACITY) {
                        known.keys.firstOrNull()?.let(known::remove)
                    }
                    known[trackKey] = after
                    true
                }
            }
        }
        if (changed) save()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(known) { known.clear() }
        file.delete()
        Unit
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        if (file.exists()) file.length() else 0L
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        runCatching {
            val parsed = json.decodeFromString<Map<String, Known>>(text)
            synchronized(known) { known.putAll(parsed) }
        }
    }

    private fun save() {
        val snapshot = synchronized(known) { known.toMap() }
        runCatching { file.writeText(json.encodeToString(snapshot)) }
    }

    private companion object {
        const val FILE_NAME = "extras-index.json"
        const val CAPACITY = 2_000
    }
}
