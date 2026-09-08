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

        val age = System.currentTimeMillis() - entry.savedAtMs
        val ttl = if (entry.document == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
        if (age > ttl) {
            file.delete()
            return@withContext null
        }

        if (entry.document == null) Result.NotFound else Result.Hit(entry.document)
    }

    suspend fun put(key: String, document: LyricsDocument?) = withContext(Dispatchers.IO) {
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
        data object NotFound : Result
    }

    private companion object {
        val POSITIVE_TTL_MS = TimeUnit.DAYS.toMillis(30)
        val NEGATIVE_TTL_MS = TimeUnit.DAYS.toMillis(2)
        const val MAX_ENTRIES = 500
    }
}
