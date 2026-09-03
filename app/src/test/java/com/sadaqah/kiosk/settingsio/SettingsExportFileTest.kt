package com.sadaqah.kiosk.settingsio

import com.sadaqah.kiosk.model.Settings
import org.junit.Assert.*
import org.junit.Test

class SettingsExportFileTest {

    private val fast = 1000
    private val settings = Settings(kioskName = "Test Masjid", currency = "GBP", language = "en")

    private fun success(result: ImportResult): ImportResult.Success {
        assertTrue("expected Success but was $result", result is ImportResult.Success)
        return result as ImportResult.Success
    }

    // ── Export with no secrets ───────────────────────────────────────────────

    @Test
    fun buildWithoutSecrets_needsNoPasswordToImport() {
        val json = SettingsExportFile.build(settings, emptyMap(), password = null)
        val result = success(SettingsExportFile.parse(json, password = null))
        assertEquals("Test Masjid", result.settings.kioskName)
        assertTrue(result.secrets.isEmpty())
    }

    @Test
    fun buildWithoutSecrets_containsNoSecretsBlock() {
        val json = SettingsExportFile.build(settings, emptyMap(), password = null)
        assertFalse(json.contains("ciphertext"))
    }

    // ── Export with secrets ──────────────────────────────────────────────────

    @Test
    fun buildWithSecrets_roundTripsWithCorrectPassword() {
        val secrets = mapOf(SettingsExportFile.KEY_AFFILIATE to "aff-key-123")
        val json = SettingsExportFile.build(settings, secrets, "hunter2", fast)

        val result = success(SettingsExportFile.parse(json, "hunter2"))
        assertEquals("aff-key-123", result.secrets[SettingsExportFile.KEY_AFFILIATE])
        assertEquals("Test Masjid", result.settings.kioskName)
    }

    @Test
    fun buildWithSecrets_neverLeaksSecretInPlaintext() {
        val secrets = mapOf(SettingsExportFile.KEY_AFFILIATE to "aff-key-123")
        val json = SettingsExportFile.build(settings, secrets, "hunter2", fast)
        assertFalse(json.contains("aff-key-123"))
    }

    @Test
    fun parseEncrypted_withoutPassword_returnsPasswordRequired() {
        val json = SettingsExportFile.build(settings, mapOf("k" to "v"), "pw", fast)
        assertEquals(ImportResult.PasswordRequired, SettingsExportFile.parse(json, password = null))
    }

    @Test
    fun parseEncrypted_wrongPassword_returnsWrongPassword() {
        val json = SettingsExportFile.build(settings, mapOf("k" to "v"), "pw", fast)
        assertEquals(ImportResult.WrongPassword, SettingsExportFile.parse(json, "nope"))
    }

    /** Settings must still be readable without the password, as an explicit choice. */
    @Test
    fun parseSettingsOnly_ignoresEncryptedSecrets() {
        val json = SettingsExportFile.build(settings, mapOf("k" to "v"), "pw", fast)
        val result = success(SettingsExportFile.parseSettingsOnly(json))
        assertEquals("Test Masjid", result.settings.kioskName)
        assertTrue(result.secrets.isEmpty())
    }

    // ── Legacy compatibility ─────────────────────────────────────────────────

    @Test
    fun legacyExportWithPlaintextAffiliateKey_stillImports() {
        val legacy = """
            {"settings":{"kioskName":"Old Masjid","currency":"EUR","language":"nl"},
             "affiliateKey":"legacy-key"}
        """.trimIndent()
        val result = success(SettingsExportFile.parse(legacy, password = null))
        assertEquals("Old Masjid", result.settings.kioskName)
        assertEquals("legacy-key", result.secrets[SettingsExportFile.KEY_AFFILIATE])
    }

    @Test
    fun legacyExportWithoutAffiliateKey_stillImports() {
        val legacy = """{"settings":{"kioskName":"Old Masjid","language":"nl"}}"""
        val result = success(SettingsExportFile.parse(legacy, password = null))
        assertEquals("Old Masjid", result.settings.kioskName)
        assertTrue(result.secrets.isEmpty())
    }

    @Test
    fun legacyExportWithBlankAffiliateKey_yieldsNoSecret() {
        val legacy = """{"settings":{"language":"nl"},"affiliateKey":""}"""
        val result = success(SettingsExportFile.parse(legacy, password = null))
        assertTrue(result.secrets.isEmpty())
    }

    // ── Malformed input ──────────────────────────────────────────────────────

    @Test
    fun notJson_returnsMalformed() {
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse("this is not json", null))
    }

    @Test
    fun jsonWithoutSettings_returnsMalformed() {
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse("""{"nope":1}""", null))
    }

    @Test
    fun emptyString_returnsMalformed() {
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse("", null))
    }

    // ── Fix verification tests ───────────────────────────────────────────────

    @Test
    fun malformedEnvelope_missingRequiredField_returnsMalformed() {
        val malformed = """
            {"settings":{"kioskName":"Test","currency":"GBP","language":"en"},
             "secrets":{"v":1,"kdf":"pbkdf2-sha256","iterations":1000}}
        """.trimIndent()
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse(malformed, "anypassword"))
    }

    @Test
    fun decryptedPayloadWithNonStringValues_returnsMalformed() {
        val secrets = mapOf("string_key" to "string_value")
        val envelope = SecretsCrypto.encrypt("""{"nested":{"inner":1}}""", "pw", fast)
        val json = """{"settings":{"kioskName":"Test","currency":"GBP","language":"en"},"secrets":${com.google.gson.Gson().toJson(envelope)}}"""
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse(json, "pw"))
    }

    @Test
    fun buildWithNullPassword_producesNoSecretsBlock() {
        val json = SettingsExportFile.build(settings, mapOf("k" to "v"), password = null)
        assertFalse(json.contains("secrets"))
        val result = success(SettingsExportFile.parse(json, password = null))
        assertTrue(result.secrets.isEmpty())
    }

    // ── Review fix-wave tests ────────────────────────────────────────────────

    private fun jsonWithMutatedEnvelope(mutate: (com.google.gson.JsonObject) -> Unit): String {
        val envelope = SecretsCrypto.encrypt("""{"k":"v"}""", "pw", fast)
        val envelopeJson = com.google.gson.Gson().toJsonTree(envelope).asJsonObject
        mutate(envelopeJson)
        return """{"settings":{"kioskName":"Test","currency":"GBP","language":"en"},"secrets":$envelopeJson}"""
    }

    @Test
    fun envelopeWithUnsupportedVersion_returnsMalformedNotWrongPassword() {
        val json = jsonWithMutatedEnvelope { it.addProperty("v", 99) }
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse(json, "pw"))
    }

    @Test
    fun envelopeWithUnrecognisedKdf_returnsMalformedNotWrongPassword() {
        val json = jsonWithMutatedEnvelope { it.addProperty("kdf", "MD5") }
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse(json, "pw"))
    }

    /** Must be rejected by envelope validation, before any key derivation is attempted. */
    @Test
    fun envelopeWithIterationsAboveCap_returnsMalformedWithoutDerivingKey() {
        val json = jsonWithMutatedEnvelope { it.addProperty("iterations", 2_000_001) }
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse(json, "pw"))
    }

    @Test
    fun envelopeWithNegativeIterations_returnsMalformed() {
        val json = jsonWithMutatedEnvelope { it.addProperty("iterations", -5) }
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse(json, "pw"))
    }

    @Test
    fun secretsPresentButNotJsonObject_returnsMalformedNotSuccess() {
        val json = """{"settings":{"kioskName":"Test","currency":"GBP","language":"en"},"secrets":"corrupted"}"""
        assertEquals(ImportResult.Malformed, SettingsExportFile.parse(json, null))
    }
}
