package com.betterlyrics.app.lyrics

import android.content.Context
import com.betterlyrics.app.lyrics.model.LyricsDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * On-disk lyrics cache.
 *
 * Skipping back one track should not cost a network round trip, and — more to the
 * point — the providers here are community-run or undocumented, so the polite thing
 * is to ask each of them once per track and then leave them alone.
 *
 * "Nothing found" is cached too, with a shorter life: without it, a track with no
 * lyrics re-queries every provider every time it comes round on shuffle.
 */
class LyricsCache(context: Context) {

    @Serializable
    private data class Entry(
        val savedAtMs: Long,
        val document: LyricsDocument? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val directory: File = File(context.cacheDir, "lyrics-cache")

    suspend fun get(key: String): Result? = withContext(Dispatchers.IO) {
        val file = fileFor(key)
        if (!file.exists()) return@withContext null

        val entry = runCatching {
            json.decodeFromString<Entry>(file.readText())
        }.getOrNull() ?: run {
            file.delete()
            return@withContext null
        }

        // A remembered miss is dropped on sight rather than honoured. Written by an older
        // version, which cached "no lyrics" for two days; a track nobody had transcribed
        // then may well be in the community database now, and the only moment the user
        // cares is the moment they play it again.
        if (entry.document == null) {
            file.delete()
            return@withContext null
        }

        if (System.currentTimeMillis() - entry.savedAtMs > POSITIVE_TTL_MS) {
            file.delete()
            return@withContext null
        }

        Result.Hit(entry.document)
    }

    /**
     * Remember a document. Nulls are deliberately not stored.
     *
     * Caching a miss saves a lookup and costs a song: the sources grow, and a track that
     * had nothing last week is worth asking about again the next time it plays. Asking once
     * per play of an untranscribed song is a cost worth paying — and it is bounded, because
     * a track only resolves once while it is playing.
     */
    suspend fun put(key: String, document: LyricsDocument?) = withContext(Dispatchers.IO) {
        if (document == null) return@withContext
        runCatching {
            directory.mkdirs()
            fileFor(key).writeText(
                json.encodeToString(Entry(System.currentTimeMillis(), document)),
            )
            prune()
        }
        Unit
    }

    suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        fileFor(key).delete()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { it.delete() }
        Unit
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        directory.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun prune() {
        val files = directory.listFiles() ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { it.delete() }
    }

    private fun fileFor(key: String): File = File(directory, "${sha1(key)}.json")

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    sealed interface Result {
        data class Hit(val document: LyricsDocument) : Result
    }

    private companion object {
        val POSITIVE_TTL_MS = TimeUnit.DAYS.toMillis(30)
        const val MAX_ENTRIES = 500
    }
}
