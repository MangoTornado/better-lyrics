package com.melisma.app.update

import com.melisma.app.lyrics.provider.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A published release, and the APK to install it from. */
data class AvailableRelease(
    /** `0.2.0` — the tag with its `v` removed, which is what the app's own version is. */
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val pageUrl: String,
)

/**
 * Asks GitHub what the newest release is.
 *
 * The app is distributed as an APK from the releases page rather than through a store, so
 * nothing tells the user an update exists — they have to remember to go and look. That is
 * a poor deal for a hobby app people install once, so it asks on their behalf.
 *
 * Read-only and unauthenticated: one small GET against the public releases API. That is
 * rate-limited to 60 an hour per address, which a check every few hours is comfortably
 * inside.
 */
object UpdateChecker {

    /**
     * The repository releases are published from.
     *
     * Deliberately a constant rather than a setting: an app that will download and install
     * code should not take the address of that code from anywhere a mistake or a malicious
     * link could reach.
     */
    const val REPOSITORY = "MangoTornado/melisma"

    const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"

    private const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    suspend fun latest(): AvailableRelease? = withContext(Dispatchers.IO) {
        Http.get(API, HEADERS) { body ->
            runCatching {
                val root = Json.parseToJsonElement(body).jsonObject
                if (root["draft"]?.jsonPrimitive?.contentOrNull == "true") {
                    return@runCatching null
                }

                val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
                    ?: return@runCatching null

                // The release workflow attaches exactly one APK, named for its version.
                val asset = root["assets"]?.jsonArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull {
                        it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true
                    }
                    ?: return@runCatching null

                AvailableRelease(
                    versionName = tag,
                    notes = root["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    apkUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                        ?: return@runCatching null,
                    apkSizeBytes = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    pageUrl = root["html_url"]?.jsonPrimitive?.contentOrNull ?: RELEASES_URL,
                )
            }.getOrNull()
        }
    }

    /**
     * Whether [candidate] is a later version than [current].
     *
     * Compares the dot-separated numbers and nothing else, so `0.10.0` beats `0.9.0` — which
     * a string comparison gets backwards — and a suffix like `0.2.0-beta1` is read as
     * `0.2.0`. A version that cannot be parsed at all is never treated as newer: offering an
     * update the user cannot make sense of is worse than staying quiet.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate) ?: return false
        val b = numbers(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun numbers(version: String): List<Int>? {
        val parts = version.trim().removePrefix("v")
            .split('.', '-', '+')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private val HEADERS = mapOf(
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )
}
