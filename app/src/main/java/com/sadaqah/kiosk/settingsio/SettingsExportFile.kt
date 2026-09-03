package com.sadaqah.kiosk.settingsio

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sadaqah.kiosk.model.Settings

sealed class ImportResult {
    data class Success(val settings: Settings, val secrets: Map<String, String>) : ImportResult()
    /** The file carries encrypted secrets and no password was supplied. */
    data object PasswordRequired : ImportResult()
    data object WrongPassword : ImportResult()
    data object Malformed : ImportResult()
}

/**
 * Reads and writes the settings export file.
 *
 * Non-secret settings stay in plaintext so the file remains inspectable; secrets
 * go into a single password-encrypted envelope. Exports written before this
 * format existed carried the affiliate key as a plaintext top-level field, and
 * are still accepted so operator backups keep working.
 */
object SettingsExportFile {
    const val KEY_AFFILIATE = "affiliateKey"

    private val gson = Gson()

    /**
     * Builds an export JSON with settings and optionally encrypted secrets.
     * Secrets are omitted entirely when no password is supplied, preventing plaintext leakage.
     */
    fun build(
        settings: Settings,
        secrets: Map<String, String>,
        password: String?,
        iterations: Int = SecretsCrypto.ITERATIONS
    ): String {
        val root = JsonObject()
        root.add("settings", gson.toJsonTree(settings))

        val usable = secrets.filterValues { it.isNotBlank() }
        if (usable.isNotEmpty() && password != null) {
            val envelope = SecretsCrypto.encrypt(gson.toJson(usable), password, iterations)
            root.add("secrets", gson.toJsonTree(envelope))
        }
        return gson.toJson(root)
    }

    fun parse(json: String, password: String?): ImportResult {
        val root = readRoot(json) ?: return ImportResult.Malformed
        val settings = readSettings(root) ?: return ImportResult.Malformed

        val secretsElement = root.get("secrets")
        if (secretsElement == null) {
            // No envelope: either a clean export, or the legacy plaintext shape.
            return ImportResult.Success(settings, legacySecrets(root))
        }
        if (!secretsElement.isJsonObject) {
            // "secrets" present but not an object — the block was corrupted, not absent.
            // Falling through to the legacy path here would silently report Success
            // with an empty secrets map.
            return ImportResult.Malformed
        }
        if (password == null) return ImportResult.PasswordRequired

        val envelope = try {
            gson.fromJson(secretsElement, SecretsEnvelope::class.java)
        } catch (e: Exception) {
            return ImportResult.Malformed
        } ?: return ImportResult.Malformed

        // Validate envelope before attempting decryption to distinguish malformed from wrong password.
        @Suppress("SENSELESS_COMPARISON")
        if (envelope.kdf == null || envelope.salt == null || envelope.iv == null || envelope.ciphertext == null ||
            envelope.kdf.isBlank() || envelope.salt.isBlank() ||
            envelope.iv.isBlank() || envelope.ciphertext.isBlank() ||
            envelope.v == 0 || envelope.iterations !in 1..2_000_000) {
            return ImportResult.Malformed
        }

        // A version or KDF we don't understand is not the same failure as a bad
        // password — SecretsCrypto.decrypt would return null for both, which
        // would tell the operator to retype a password that was never wrong.
        if (envelope.v != SecretsCrypto.FORMAT_VERSION || envelope.kdf != SecretsCrypto.KDF) {
            return ImportResult.Malformed
        }

        val plaintext = SecretsCrypto.decrypt(envelope, password) ?: return ImportResult.WrongPassword
        val secretsObject = try {
            JsonParser.parseString(plaintext) as? JsonObject
        } catch (e: Exception) {
            return ImportResult.Malformed
        } ?: return ImportResult.Malformed

        // Verify all values are strings before returning Success.
        val secrets = mutableMapOf<String, String>()
        for ((key, value) in secretsObject.entrySet()) {
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                return ImportResult.Malformed
            }
            secrets[key] = value.asString
        }
        return ImportResult.Success(settings, secrets)
    }

    /** Imports configuration while deliberately leaving encrypted secrets behind. */
    fun parseSettingsOnly(json: String): ImportResult {
        val root = readRoot(json) ?: return ImportResult.Malformed
        val settings = readSettings(root) ?: return ImportResult.Malformed
        return ImportResult.Success(settings, emptyMap())
    }

    private fun readRoot(json: String): JsonObject? = try {
        JsonParser.parseString(json) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun readSettings(root: JsonObject): Settings? = try {
        val element = root.get("settings") ?: return null
        gson.fromJson(element, Settings::class.java)
    } catch (e: Exception) {
        null
    }

    private fun legacySecrets(root: JsonObject): Map<String, String> {
        val legacyKey = try {
            root.get(KEY_AFFILIATE)?.asString
        } catch (e: Exception) {
            null
        }
        return if (legacyKey.isNullOrBlank()) emptyMap() else mapOf(KEY_AFFILIATE to legacyKey)
    }
}
