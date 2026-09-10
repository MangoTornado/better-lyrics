package com.melisma.app.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Settings in and out of a file the user keeps.
 *
 * The plain half is deliberately plain: a readable JSON document of every preference, so a new phone
 * is a restore rather than an evening of tapping. Nothing in there is sensitive — font sizes, which
 * sources are on, how long to wait before standing down.
 *
 * The credentials are a different question and get a different answer. They are live keys to the
 * user's own Spotify, Apple and Musixmatch accounts, and the app's promise is that they stay on the
 * device — which is why they live in a preferences file excluded from Android's backup. Writing them
 * into a plain file in Downloads would undo that quietly, for the convenience of one restore. So they
 * are opt-in, and when opted into they are encrypted under a passphrase the user chooses: AES-256-GCM
 * with a key stretched from that passphrase by PBKDF2, which is what stops a four-word passphrase
 * being brute-forced out of a file somebody found.
 *
 * The format is versioned and typed. Typed because `SharedPreferences` distinguishes an `Int` from a
 * `Long` from a `Float` and JSON does not: restoring a float as an integer would silently reset a
 * font scale, and restoring an int where a long belongs throws. Versioned because a settings file
 * outlives the release that wrote it.
 */
object SettingsBackup {

    const val FORMAT = "melisma-settings"
    const val VERSION = 1

    /** What reading a file produced. */
    sealed interface Restore {
        /** Settings, and credentials if the file had them and the passphrase opened them. */
        data class Ready(
            val settings: Map<String, Any?>,
            val credentials: Map<String, Any?>,
        ) : Restore

        /** The file holds credentials and no passphrase was given. */
        data object NeedsPassphrase : Restore

        /** It holds credentials and the passphrase did not open them. */
        data object WrongPassphrase : Restore

        /** Not one of ours, or damaged beyond reading. */
        data object NotABackup : Restore
    }

    /**
     * @param passphrase required when [credentials] is non-empty, ignored otherwise.
     * @throws IllegalArgumentException if credentials are given with no passphrase — a caller that
     *   let that through would be writing live tokens to a plain file, which is the one outcome this
     *   whole design exists to prevent.
     */
    fun write(
        settings: Map<String, Any?>,
        credentials: Map<String, Any?> = emptyMap(),
        passphrase: String? = null,
    ): String {
        require(credentials.isEmpty() || !passphrase.isNullOrBlank()) {
            "credentials cannot be written without a passphrase"
        }

        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("format", JsonPrimitive(FORMAT))
                put("version", JsonPrimitive(VERSION))
                put("exportedAt", JsonPrimitive(System.currentTimeMillis()))
                put("settings", encodeValues(settings))
                if (credentials.isNotEmpty()) {
                    put("credentials", encrypt(encodeValues(credentials), passphrase!!))
                }
            },
        )
    }

    fun read(text: String, passphrase: String? = null): Restore {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return Restore.NotABackup

        if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) return Restore.NotABackup
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: return Restore.NotABackup
        // A file from a later version may use fields this build does not know about. Reading it
        // would restore some settings and silently drop others, which is worse than refusing.
        if (version > VERSION) return Restore.NotABackup

        val settings = root["settings"]?.let { runCatching { decodeValues(it.jsonObject) }.getOrNull() }
            ?: return Restore.NotABackup

        val sealed = root["credentials"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return Restore.Ready(settings, emptyMap())

        if (passphrase.isNullOrBlank()) return Restore.NeedsPassphrase
        val opened = decrypt(sealed, passphrase) ?: return Restore.WrongPassphrase
        val credentials = runCatching { decodeValues(opened) }.getOrNull()
            ?: return Restore.WrongPassphrase
        return Restore.Ready(settings, credentials)
    }

    /** Whether a file will want a passphrase, for asking before the work starts. */
    fun holdsCredentials(text: String): Boolean = runCatching {
        json.parseToJsonElement(text).jsonObject["credentials"] != null
    }.getOrDefault(false)

    // ---- values ------------------------------------------------------------

    private fun encodeValues(values: Map<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in values.toSortedMap()) {
            encodeValue(value)?.let { put(key, it) }
        }
    }

    private fun encodeValue(value: Any?): JsonObject? = when (value) {
        is Boolean -> typed("boolean", JsonPrimitive(value))
        is Int -> typed("int", JsonPrimitive(value))
        is Long -> typed("long", JsonPrimitive(value))
        is Float -> typed("float", JsonPrimitive(value))
        is String -> typed("string", JsonPrimitive(value))
        is Set<*> -> typed(
            "stringSet",
            JsonArray(value.filterIsInstance<String>().sorted().map { JsonPrimitive(it) }),
        )
        // Anything else is dropped rather than guessed at. Nothing stored here is another type
        // today, and inventing a conversion for one added later would corrupt it on restore.
        else -> null
    }

    private fun typed(type: String, value: kotlinx.serialization.json.JsonElement) = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("value", value)
    }

    private fun decodeValues(values: JsonObject): Map<String, Any?> = buildMap {
        for ((key, element) in values) {
            val entry = runCatching { element.jsonObject }.getOrNull() ?: continue
            val type = entry["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val raw = entry["value"] ?: continue
            val decoded: Any? = runCatching {
                when (type) {
                    "boolean" -> raw.jsonPrimitive.booleanOrNull
                    "int" -> raw.jsonPrimitive.intOrNull
                    "long" -> raw.jsonPrimitive.longOrNull
                    "float" -> raw.jsonPrimitive.floatOrNull
                    "string" -> raw.jsonPrimitive.contentOrNull
                    "stringSet" -> raw.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                    else -> null
                }
            }.getOrNull()
            if (decoded != null) put(key, decoded)
        }
    }

    // ---- the sealed half ---------------------------------------------------

    private fun encrypt(payload: JsonObject, passphrase: String): JsonObject {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyFrom(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(
            json.encodeToString(JsonObject.serializer(), payload).toByteArray(Charsets.UTF_8),
        )

        return buildJsonObject {
            put("cipher", JsonPrimitive(TRANSFORMATION))
            put("kdf", JsonPrimitive(KDF))
            put("iterations", JsonPrimitive(ITERATIONS))
            put("salt", JsonPrimitive(encode64(salt)))
            put("iv", JsonPrimitive(encode64(iv)))
            put("data", JsonPrimitive(encode64(body)))
        }
    }

    /** Null for any failure at all — a wrong passphrase and a damaged file are the same answer. */
    private fun decrypt(sealed: JsonObject, passphrase: String): JsonObject? = runCatching {
        val iterations = sealed["iterations"]?.jsonPrimitive?.intOrNull ?: return null
        // Read from the file so the cost can be raised later, but bounded: a file claiming a
        // hundred million rounds would otherwise hang the app on a key nobody can use anyway.
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null

        val salt = decode64(sealed["salt"]?.jsonPrimitive?.contentOrNull ?: return null)
        val iv = decode64(sealed["iv"]?.jsonPrimitive?.contentOrNull ?: return null)
        val body = decode64(sealed["data"]?.jsonPrimitive?.contentOrNull ?: return null)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyFrom(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        // GCM authenticates as it decrypts, so a wrong passphrase throws here rather than handing
        // back plausible rubbish. That is the whole reason for choosing it.
        json.parseToJsonElement(String(cipher.doFinal(body), Charsets.UTF_8)).jsonObject
    }.getOrElse { failure ->
        if (failure is GeneralSecurityException || failure is IllegalArgumentException) null else null
    }

    private fun keyFrom(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KDF)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun encode64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    /**
     * Deliberately expensive. This is guarding live account tokens with whatever passphrase somebody
     * types in a hurry, so the only defence against guessing is making each guess cost something.
     * Under a second on a phone, once, for an export nobody does twice.
     */
    private const val ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 4_000_000
}
