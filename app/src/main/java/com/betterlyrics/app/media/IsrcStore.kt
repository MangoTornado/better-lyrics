package com.betterlyrics.app.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * ISRCs the app has learned, kept so they only have to be learned once.
 *
 * An ISRC identifies a recording globally, which makes it the one piece of information that turns
 * a fuzzy title match into an exact lookup — AMLL indexes on it, Apple filters on it. Android
 * publishes no such thing, so it can only come from somewhere that does: a Spotify token, or a
 * cache server that collected one.
 *
 * Which is exactly why it is worth writing down. The Spotify token that produced it expires within
 * the hour; the ISRC does not expire at all. A track played once with a token in hand is matched
 * exactly for as long as the app stays installed.
 *
 * Small on purpose — a dozen characters per track — and written as one JSON file rather than a
 * database, because that is all this is: a map that outlives the process.
 */
class IsrcStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** Loaded once, then answered from memory: this is consulted on every track change. */
    private val known = LinkedHashMap<String, String>()
    private var loaded = false

    suspend fun get(trackKey: String): String? = withContext(Dispatchers.IO) {
        load()
        synchronized(known) { known[trackKey] }
    }

    /**
     * Remember an ISRC for a track. The first answer wins.
     *
     * Never overwritten, because two sources reporting different ISRCs for one track means one of
     * them matched the wrong recording, and there is no way to tell which from here — so the
     * earlier answer, which has already been used, is kept rather than replaced by a coin flip.
     */
    suspend fun put(trackKey: String, isrc: String) = withContext(Dispatchers.IO) {
        val value = isrc.trim().uppercase()
        if (value.length !in MIN_LENGTH..MAX_LENGTH) return@withContext
        load()

        val changed = synchronized(known) {
            if (known.containsKey(trackKey)) {
                false
            } else {
                // Oldest out first. A listener who plays thousands of tracks keeps the recent ones,
                // and the rest cost a lookup they would have needed anyway.
                if (known.size >= CAPACITY) known.keys.firstOrNull()?.let(known::remove)
                known[trackKey] = value
                true
            }
        }
        if (changed) save()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            synchronized(known) {
                for ((key, value) in root) {
                    value.jsonPrimitive.contentOrNull?.let { known[key] = it }
                }
            }
        }
    }

    private fun save() {
        val snapshot = synchronized(known) { known.toMap() }
        runCatching {
            val json = JsonObject(snapshot.mapValues { (_, value) -> JsonPrimitive(value) })
            file.writeText(json.toString())
        }
    }

    private companion object {
        const val FILE_NAME = "isrc-index.json"

        /** ISRCs are twelve characters. A range, because a source may pad or hyphenate one. */
        const val MIN_LENGTH = 12
        const val MAX_LENGTH = 15

        const val CAPACITY = 2_000
    }
}
