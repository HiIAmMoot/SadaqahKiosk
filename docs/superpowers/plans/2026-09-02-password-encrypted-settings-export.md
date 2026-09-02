# Password-Encrypted Settings Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encrypt the secrets inside an exported settings JSON under an operator-supplied password, so a leaked export file is no longer immediately usable.

**Architecture:** A pure crypto helper (PBKDF2-HMAC-SHA256 → AES-256-GCM) and a pure file-format helper sit under a new `settingsio` package with no Android dependencies, so both are fully unit-testable on the JVM. `MainActivity` and `SettingsScreen` are thin wiring on top: the export dialog gains a password field, the import dialog prompts when the file carries an encrypted envelope. Exports created before this change still import.

**Tech Stack:** Kotlin, JDK `javax.crypto` (no new dependencies), Gson, Jetpack Compose / Material3, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-02-kiosk-telemetry-design.md` (section "Fleet provisioning: password-encrypted secrets in the export file")

## Global Constraints

- **No new Gradle dependencies.** Everything needed is in the JDK and already-present Gson.
- **Use `java.util.Base64`, never `android.util.Base64`.** The latter is stubbed in JVM unit tests and returns garbage. `java.util.Base64` requires API 26; `minSdk` is 30.
- **KDF parameters:** `PBKDF2WithHmacSHA256`, 600,000 iterations, 16-byte random salt, AES-256-GCM with a 12-byte random IV and a 128-bit tag.
- **Key derivation must not run on the UI thread.** It takes seconds on a Lenovo M9 and will read as a frozen kiosk.
- **Format version field (`v`) is mandatory** so KDF parameters can be raised later without breaking old files.
- **Backward compatibility is required.** A pre-existing export with a top-level plaintext `affiliateKey` must still import.
- **A failed import must leave the device untouched.** Validate and decrypt fully before applying anything.
- **New user-facing strings must be added to all 8 languages** in `Translations.kt`: Dutch, English, German, French, Spanish, Italian, Turkish, Arabic.
- **`versionCode` stays 15** — this targets the `1.3.x` track.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/sadaqah/kiosk/settingsio/SecretsCrypto.kt` | Password-based encrypt/decrypt. Knows nothing about settings. |
| `app/src/main/java/com/sadaqah/kiosk/settingsio/SettingsExportFile.kt` | Export/import file format, legacy detection. Knows nothing about crypto internals. |
| `app/src/test/java/com/sadaqah/kiosk/settingsio/SecretsCryptoTest.kt` | Round-trip, wrong password, tampering. |
| `app/src/test/java/com/sadaqah/kiosk/settingsio/SettingsExportFileTest.kt` | Format, legacy import, atomicity of failure. |
| `app/src/main/java/com/sadaqah/kiosk/Translations.kt` | 5 new strings × 8 languages. |
| `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt` | Wiring: `exportSettings` / `importSettings` signatures. |
| `app/src/main/java/com/sadaqah/kiosk/screens/SettingsScreen.kt` | Password fields in the export/import dialogs. |
| `README.md` | Export/import documentation. |

The `settingsio` package name is a deliberate deviation from the spec, which sketched `telemetry/SecretsEnvelope.kt`. This code serves settings export/import generally — it protects the SumUp affiliate key whether or not telemetry is ever configured — so it does not belong under `telemetry/`.

---

### Task 1: Password-based encryption primitives

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/settingsio/SecretsCrypto.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/settingsio/SecretsCryptoTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class SecretsEnvelope(val v: Int, val kdf: String, val iterations: Int, val salt: String, val iv: String, val ciphertext: String)`
  - `SecretsCrypto.encrypt(plaintext: String, password: String, iterations: Int = SecretsCrypto.ITERATIONS): SecretsEnvelope`
  - `SecretsCrypto.decrypt(envelope: SecretsEnvelope, password: String): String?` — `null` on wrong password, tampering, or unsupported format
  - `SecretsCrypto.ITERATIONS: Int` = 600000, `SecretsCrypto.FORMAT_VERSION: Int` = 1

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/sadaqah/kiosk/settingsio/SecretsCryptoTest.kt`:

```kotlin
package com.sadaqah.kiosk.settingsio

import org.junit.Assert.*
import org.junit.Test

class SecretsCryptoTest {

    // Real iteration count is deliberately slow. Most tests use a low count for
    // speed; defaultIterations_roundTrips covers the shipped parameter.
    private val fast = 1000

    @Test
    fun roundTrip_returnsOriginalPlaintext() {
        val plaintext = """{"affiliateKey":"abc-123"}"""
        val envelope = SecretsCrypto.encrypt(plaintext, "correct horse", fast)
        assertEquals(plaintext, SecretsCrypto.decrypt(envelope, "correct horse"))
    }

    @Test
    fun defaultIterations_roundTrips() {
        val envelope = SecretsCrypto.encrypt("secret", "pw")
        assertEquals(SecretsCrypto.ITERATIONS, envelope.iterations)
        assertEquals("secret", SecretsCrypto.decrypt(envelope, "pw"))
    }

    @Test
    fun wrongPassword_returnsNull() {
        val envelope = SecretsCrypto.encrypt("secret", "right", fast)
        assertNull(SecretsCrypto.decrypt(envelope, "wrong"))
    }

    @Test
    fun emptyPasswordIsUsableButDistinct() {
        val envelope = SecretsCrypto.encrypt("secret", "", fast)
        assertEquals("secret", SecretsCrypto.decrypt(envelope, ""))
        assertNull(SecretsCrypto.decrypt(envelope, "x"))
    }

    @Test
    fun tamperedCiphertext_returnsNull() {
        val envelope = SecretsCrypto.encrypt("secret", "pw", fast)
        val flipped = envelope.ciphertext.let {
            val chars = it.toCharArray()
            chars[0] = if (chars[0] == 'A') 'B' else 'A'
            String(chars)
        }
        assertNull(SecretsCrypto.decrypt(envelope.copy(ciphertext = flipped), "pw"))
    }

    @Test
    fun eachEncryptionUsesFreshSaltAndIv() {
        val a = SecretsCrypto.encrypt("secret", "pw", fast)
        val b = SecretsCrypto.encrypt("secret", "pw", fast)
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.iv, b.iv)
        assertNotEquals(a.ciphertext, b.ciphertext)
    }

    @Test
    fun unsupportedFormatVersion_returnsNull() {
        val envelope = SecretsCrypto.encrypt("secret", "pw", fast)
        assertNull(SecretsCrypto.decrypt(envelope.copy(v = 99), "pw"))
    }

    @Test
    fun unsupportedKdf_returnsNull() {
        val envelope = SecretsCrypto.encrypt("secret", "pw", fast)
        assertNull(SecretsCrypto.decrypt(envelope.copy(kdf = "MD5"), "pw"))
    }

    @Test
    fun malformedBase64_returnsNull() {
        val envelope = SecretsCrypto.encrypt("secret", "pw", fast)
        assertNull(SecretsCrypto.decrypt(envelope.copy(salt = "not base64!!!"), "pw"))
    }

    @Test
    fun unicodePlaintext_roundTrips() {
        val plaintext = """{"name":"مسجد الرحمة","key":"ключ"}"""
        val envelope = SecretsCrypto.encrypt(plaintext, "pw", fast)
        assertEquals(plaintext, SecretsCrypto.decrypt(envelope, "pw"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.settingsio.SecretsCryptoTest"`
Expected: FAIL — `Unresolved reference 'SecretsCrypto'`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/sadaqah/kiosk/settingsio/SecretsCrypto.kt`:

```kotlin
package com.sadaqah.kiosk.settingsio

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted secrets block inside an exported settings file. Field names are
 * short because they are serialised verbatim into the export JSON.
 */
data class SecretsEnvelope(
    val v: Int,
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String
)

/**
 * Password-based encryption for the settings export.
 *
 * Deliberately knows nothing about settings: it takes a string in and gives a
 * string back, so the file format can change without touching the crypto.
 *
 * Key derivation is slow by design — callers must keep it off the UI thread.
 */
object SecretsCrypto {
    const val FORMAT_VERSION = 1
    const val KDF = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 600_000

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(plaintext: String, password: String, iterations: Int = ITERATIONS): SecretsEnvelope {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val encoder = Base64.getEncoder()
        return SecretsEnvelope(
            v = FORMAT_VERSION,
            kdf = KDF,
            iterations = iterations,
            salt = encoder.encodeToString(salt),
            iv = encoder.encodeToString(iv),
            ciphertext = encoder.encodeToString(ciphertext)
        )
    }

    /** Returns null for a wrong password, tampered data, or a format we don't understand. */
    fun decrypt(envelope: SecretsEnvelope, password: String): String? {
        if (envelope.v != FORMAT_VERSION) return null
        if (envelope.kdf != KDF) return null
        if (envelope.iterations <= 0) return null
        return try {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(envelope.salt)
            val iv = decoder.decode(envelope.iv)
            val ciphertext = decoder.decode(envelope.ciphertext)

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, deriveKey(password, salt, envelope.iterations), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // AEADBadTagException for a wrong password or tampering, and
            // IllegalArgumentException for malformed base64. Both mean the same
            // thing to the caller: this file will not open with this password.
            null
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.settingsio.SecretsCryptoTest"`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/settingsio/SecretsCrypto.kt app/src/test/java/com/sadaqah/kiosk/settingsio/SecretsCryptoTest.kt
git commit -m "Add password-based encryption for settings secrets"
```

---

### Task 2: Export file format with legacy import

**Files:**
- Create: `app/src/main/java/com/sadaqah/kiosk/settingsio/SettingsExportFile.kt`
- Test: `app/src/test/java/com/sadaqah/kiosk/settingsio/SettingsExportFileTest.kt`

**Interfaces:**
- Consumes: `SecretsCrypto.encrypt`, `SecretsCrypto.decrypt`, `SecretsEnvelope` from Task 1; `com.sadaqah.kiosk.model.Settings`.
- Produces:
  - `SettingsExportFile.build(settings: Settings, secrets: Map<String, String>, password: String?, iterations: Int = SecretsCrypto.ITERATIONS): String`
  - `SettingsExportFile.parse(json: String, password: String?): ImportResult`
  - `sealed class ImportResult` with `Success(settings, secrets)`, `PasswordRequired`, `WrongPassword`, `Malformed`
  - `SettingsExportFile.KEY_AFFILIATE = "affiliateKey"`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/sadaqah/kiosk/settingsio/SettingsExportFileTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.settingsio.SettingsExportFileTest"`
Expected: FAIL — `Unresolved reference 'SettingsExportFile'`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/sadaqah/kiosk/settingsio/SettingsExportFile.kt`:

```kotlin
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
        if (secretsElement == null || !secretsElement.isJsonObject) {
            // No envelope: either a clean export, or the legacy plaintext shape.
            return ImportResult.Success(settings, legacySecrets(root))
        }
        if (password == null) return ImportResult.PasswordRequired

        val envelope = try {
            gson.fromJson(secretsElement, SecretsEnvelope::class.java)
        } catch (e: Exception) {
            return ImportResult.Malformed
        } ?: return ImportResult.Malformed

        val plaintext = SecretsCrypto.decrypt(envelope, password) ?: return ImportResult.WrongPassword
        val secrets = try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(plaintext, Map::class.java) as Map<String, String>
        } catch (e: Exception) {
            return ImportResult.Malformed
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.sadaqah.kiosk.settingsio.SettingsExportFileTest"`
Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/settingsio/SettingsExportFile.kt app/src/test/java/com/sadaqah/kiosk/settingsio/SettingsExportFileTest.kt
git commit -m "Add settings export file format with legacy import support"
```

---

### Task 3: Translations for the new dialog copy

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/Translations.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: five new `Strings` members — `exportPassword`, `exportPasswordHint`, `importPassword`, `importWrongPassword`, `importSettingsOnly`.

**Context:** `Translations.kt` declares `interface Strings` at line 58 and eight `object …Strings : Strings` implementations (Dutch ~293, English ~510, German ~643, French ~860, Spanish ~1077, Italian ~1294, Turkish ~1511, Arabic ~1728). Every member must be added to the interface and to all eight objects or the build fails — which is the desired safety net.

- [ ] **Step 1: Add the five members to the `Strings` interface**

In `interface Strings`, next to the existing `includeAffiliateKey` / `export` declarations, add:

```kotlin
    val exportPassword: String
    val exportPasswordHint: String
    val importPassword: String
    val importWrongPassword: String
    val importSettingsOnly: String
```

- [ ] **Step 2: Add the English implementation**

In `object EnglishStrings`, next to `override val export`:

```kotlin
    override val exportPassword = "Password"
    override val exportPasswordHint = "Needed to import this file. It cannot be recovered if lost."
    override val importPassword = "Import password"
    override val importWrongPassword = "Wrong password"
    override val importSettingsOnly = "Import settings only"
```

- [ ] **Step 3: Add the remaining seven implementations**

Dutch (`object DutchStrings`):

```kotlin
    override val exportPassword = "Wachtwoord"
    override val exportPasswordHint = "Nodig om dit bestand te importeren. Kan niet worden hersteld."
    override val importPassword = "Importwachtwoord"
    override val importWrongPassword = "Onjuist wachtwoord"
    override val importSettingsOnly = "Alleen instellingen importeren"
```

German (`object GermanStrings`):

```kotlin
    override val exportPassword = "Passwort"
    override val exportPasswordHint = "Zum Importieren dieser Datei erforderlich. Kann nicht wiederhergestellt werden."
    override val importPassword = "Import-Passwort"
    override val importWrongPassword = "Falsches Passwort"
    override val importSettingsOnly = "Nur Einstellungen importieren"
```

French (`object FrenchStrings`):

```kotlin
    override val exportPassword = "Mot de passe"
    override val exportPasswordHint = "Requis pour importer ce fichier. Irrécupérable en cas de perte."
    override val importPassword = "Mot de passe d'importation"
    override val importWrongPassword = "Mot de passe incorrect"
    override val importSettingsOnly = "Importer uniquement les paramètres"
```

Spanish (`object SpanishStrings`):

```kotlin
    override val exportPassword = "Contraseña"
    override val exportPasswordHint = "Necesaria para importar este archivo. No se puede recuperar."
    override val importPassword = "Contraseña de importación"
    override val importWrongPassword = "Contraseña incorrecta"
    override val importSettingsOnly = "Importar solo ajustes"
```

Italian (`object ItalianStrings`):

```kotlin
    override val exportPassword = "Password"
    override val exportPasswordHint = "Necessaria per importare questo file. Non è recuperabile."
    override val importPassword = "Password di importazione"
    override val importWrongPassword = "Password errata"
    override val importSettingsOnly = "Importa solo impostazioni"
```

Turkish (`object TurkishStrings`):

```kotlin
    override val exportPassword = "Parola"
    override val exportPasswordHint = "Bu dosyayı içe aktarmak için gerekli. Kaybedilirse kurtarılamaz."
    override val importPassword = "İçe aktarma parolası"
    override val importWrongPassword = "Yanlış parola"
    override val importSettingsOnly = "Yalnızca ayarları içe aktar"
```

Arabic (`object ArabicStrings`):

```kotlin
    override val exportPassword = "كلمة المرور"
    override val exportPasswordHint = "مطلوبة لاستيراد هذا الملف. لا يمكن استعادتها عند فقدانها."
    override val importPassword = "كلمة مرور الاستيراد"
    override val importWrongPassword = "كلمة المرور غير صحيحة"
    override val importSettingsOnly = "استيراد الإعدادات فقط"
```

- [ ] **Step 4: Verify the build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. A missing language produces "Object 'XStrings' is not abstract and does not implement abstract member".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/Translations.kt
git commit -m "Add export/import password strings in all 8 languages"
```

---

### Task 4: Wire the password through export and import

**Files:**
- Modify: `app/src/main/java/com/sadaqah/kiosk/MainActivity.kt` (`exportSettings`, `importSettings`, and the two `AppUI` parameters)
- Modify: `app/src/main/java/com/sadaqah/kiosk/screens/SettingsScreen.kt` (`ExportDialog`, `ImportDialog`, and their call sites around lines 643-677)

**Interfaces:**
- Consumes: `SettingsExportFile.build`, `SettingsExportFile.parse`, `SettingsExportFile.parseSettingsOnly`, `ImportResult`, `SettingsExportFile.KEY_AFFILIATE` from Task 2; the five strings from Task 3.
- Produces:
  - `MainActivity.exportSettings(includeSecrets: Boolean, password: String): String`
  - `MainActivity.importSettings(jsonString: String, password: String?): ImportResult`
  - `AppUI` parameters change to `onExportSettings: (Boolean, String) -> String` and `onImportSettings: (String, String?) -> ImportResult`

**Note on threading:** key derivation at 600,000 iterations takes seconds. Both dialogs must show a progress state and run the call in `lifecycleScope.launch(Dispatchers.Default)`. Doing this on the UI thread will look like a frozen kiosk.

- [ ] **Step 1: Replace `exportSettings` and `importSettings` in `MainActivity`**

Replace the existing `exportSettings` and `importSettings` functions (currently around lines 1325-1361) with:

```kotlin
    /**
     * Serialises settings, encrypting secrets under [password] when
     * [includeSecrets] is set. Runs key derivation, so call it off the UI thread.
     */
    fun exportSettings(includeSecrets: Boolean, password: String): String {
        val secrets = if (includeSecrets && affiliateKey.isNotBlank()) {
            mapOf(SettingsExportFile.KEY_AFFILIATE to affiliateKey)
        } else {
            emptyMap()
        }
        return SettingsExportFile.build(settings, secrets, password.ifBlank { null })
    }

    /**
     * Applies an exported file. Nothing is written until the whole file has been
     * parsed and decrypted, so a wrong password leaves the device untouched.
     * Runs key derivation, so call it off the UI thread.
     */
    fun importSettings(jsonString: String, password: String?): ImportResult {
        val result = SettingsExportFile.parse(jsonString, password)
        if (result !is ImportResult.Success) return result

        settings = result.settings.copy(logoUri = null)
        saveSettings(settings)
        TranslationManager.setLanguage(TranslationManager.fromCode(settings.language))

        result.secrets[SettingsExportFile.KEY_AFFILIATE]?.takeIf { it.isNotBlank() }?.let { key ->
            affiliateKey = key
            prefs.edit { putString("affiliate_key", key) }
        }
        return result
    }

    /** Applies configuration from an export while leaving its encrypted secrets behind. */
    fun importSettingsOnly(jsonString: String): ImportResult {
        val result = SettingsExportFile.parseSettingsOnly(jsonString)
        if (result !is ImportResult.Success) return result
        settings = result.settings.copy(logoUri = null)
        saveSettings(settings)
        TranslationManager.setLanguage(TranslationManager.fromCode(settings.language))
        return result
    }
```

Add the imports at the top of `MainActivity.kt`:

```kotlin
import com.sadaqah.kiosk.settingsio.ImportResult
import com.sadaqah.kiosk.settingsio.SettingsExportFile
```

(`Dispatchers` is not needed here — the off-thread dispatch happens in `SettingsScreen`, at the call site.)

- [ ] **Step 2: Update the `AppUI` parameter types and call site**

In the `AppUI` composable signature, change:

```kotlin
    onExportSettings: (Boolean) -> String,
    onImportSettings: (String) -> Boolean,
```

to:

```kotlin
    onExportSettings: (Boolean, String) -> String,
    onImportSettings: (String, String?) -> ImportResult,
    onImportSettingsOnly: (String) -> ImportResult,
```

In `setContent`, change the arguments passed to `AppUI`:

```kotlin
                    onExportSettings = { include, password -> exportSettings(include, password) },
                    onImportSettings = { json, password -> importSettings(json, password) },
                    onImportSettingsOnly = { json -> importSettingsOnly(json) },
```

Pass all three through to `SettingsScreen` in the `else -> SettingsScreen(...)` branch, adding `onImportSettingsOnly = onImportSettingsOnly,` alongside the existing two.

- [ ] **Step 3: Update `SettingsScreen` signature and dialogs**

In `SettingsScreen`'s parameter list, change:

```kotlin
    onExportSettings: (Boolean) -> String,
    onImportSettings: (String) -> Boolean,
```

to:

```kotlin
    onExportSettings: (Boolean, String) -> String,
    onImportSettings: (String, String?) -> ImportResult,
    onImportSettingsOnly: (String) -> ImportResult,
```

Add near the other dialog state (around line 96):

```kotlin
    var exportPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var isProcessingSecrets by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
```

Replace the `showExportDialog` block (lines 643-659) with:

```kotlin
    if (showExportDialog) {
        ExportDialog(
            strings = strings,
            settings = settings,
            includeAffiliateKey = includeAffiliateKey,
            onIncludeKeyChange = { includeAffiliateKey = it },
            password = exportPassword,
            onPasswordChange = { exportPassword = it },
            isBusy = isProcessingSecrets,
            onConfirm = {
                isProcessingSecrets = true
                scope.launch {
                    // Key derivation is deliberately slow — keep it off the UI thread.
                    val jsonData = withContext(Dispatchers.Default) {
                        onExportSettings(includeAffiliateKey, exportPassword)
                    }
                    isProcessingSecrets = false
                    pendingExportJson = jsonData
                    exportFileLauncher.launch("kiosk_settings.json")
                }
            },
            onDismiss = {
                showExportDialog = false
                includeAffiliateKey = false
                exportPassword = ""
            }
        )
    }
```

Replace the `showImportDialog` block (lines 661-677) with:

```kotlin
    if (showImportDialog) {
        ImportDialog(
            strings = strings,
            settings = settings,
            password = importPassword,
            onPasswordChange = { importPassword = it },
            isBusy = isProcessingSecrets,
            onImport = { jsonInput ->
                isProcessingSecrets = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        onImportSettings(jsonInput, importPassword.ifBlank { null })
                    }
                    isProcessingSecrets = false
                    when (result) {
                        is ImportResult.Success -> {
                            Toast.makeText(context, strings.settingsImportedSuccessfully, Toast.LENGTH_SHORT).show()
                            importPassword = ""
                            onRefresh()
                            showImportDialog = false
                        }
                        ImportResult.PasswordRequired ->
                            Toast.makeText(context, strings.importPassword, Toast.LENGTH_LONG).show()
                        ImportResult.WrongPassword ->
                            Toast.makeText(context, strings.importWrongPassword, Toast.LENGTH_LONG).show()
                        ImportResult.Malformed ->
                            Toast.makeText(context, strings.failedToImportSettings, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onImportSettingsOnly = { jsonInput ->
                val result = onImportSettingsOnly(jsonInput)
                if (result is ImportResult.Success) {
                    Toast.makeText(context, strings.settingsImportedSuccessfully, Toast.LENGTH_SHORT).show()
                    onRefresh()
                    showImportDialog = false
                } else {
                    Toast.makeText(context, strings.failedToImportSettings, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = {
                showImportDialog = false
                importPassword = ""
            }
        )
    }
```

Add the imports to `SettingsScreen.kt`:

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import com.sadaqah.kiosk.settingsio.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

- [ ] **Step 4: Add the password fields to the dialog composables**

In `ExportDialog` (around line 831), add the parameters `password: String`, `onPasswordChange: (String) -> Unit`, `isBusy: Boolean`, and render a masked field below the existing checkbox, shown only when the checkbox is ticked:

```kotlin
                if (includeAffiliateKey) {
                    Spacer(modifier = Modifier.height(responsiveDp(12.dp)))
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text(strings.exportPassword) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(responsiveDp(8.dp)))
                    Text(
                        strings.exportPasswordHint,
                        color = Color(settings.buttonBorderColor),
                        fontSize = responsiveSp(14.0)
                    )
                }
```

Gate the confirm button so a secret-bearing export cannot be written without a password, and show progress while deriving:

```kotlin
            enabled = !isBusy && (!includeAffiliateKey || password.isNotBlank()),
```

In `ImportDialog`, add the parameters `password: String`, `onPasswordChange: (String) -> Unit`, `isBusy: Boolean`, `onImportSettingsOnly: (String) -> Unit`. The password field is always shown here, because the file's shape isn't known until it is parsed:

```kotlin
                Spacer(modifier = Modifier.height(responsiveDp(12.dp)))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(strings.importPassword) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
```

Add a secondary action beside the confirm button, so an operator without the password can still take the configuration — an explicit choice, never a silent downgrade:

```kotlin
                TextButton(
                    onClick = { onImportSettingsOnly(jsonInput) },
                    enabled = !isBusy
                ) {
                    Text(
                        strings.importSettingsOnly,
                        color = Color(settings.buttonBorderColor)
                    )
                }
```

Gate the confirm button on `enabled = !isBusy` so a slow derivation can't be triggered twice.

Add the imports:

```kotlin
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
```

- [ ] **Step 5: Verify the build and full test suite**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 6: Manual verification on a device**

This is the part no unit test covers. On a real kiosk:

1. Export with the affiliate-key checkbox ticked and password `test1234`. Confirm the dialog shows progress rather than freezing.
2. Open the resulting JSON. Confirm the affiliate key does **not** appear in plaintext and a `secrets` block with `ciphertext` does.
3. Import that file on a second device with the wrong password. Confirm the error appears and the kiosk name and colours are **unchanged**.
4. Import with the correct password. Confirm settings and the affiliate key both arrive.
5. Import an export produced by v1.3.5 or earlier. Confirm it still works with no password.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sadaqah/kiosk/MainActivity.kt app/src/main/java/com/sadaqah/kiosk/screens/SettingsScreen.kt
git commit -m "Prompt for a password when exporting and importing secrets"
```

---

### Task 5: Document the new export format

**Files:**
- Modify: `README.md` (the "Export / Import Settings" row in the Settings Reference table, and the Features list)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

- [ ] **Step 1: Update the Settings Reference table row**

Replace the existing row:

```markdown
| Export / Import Settings                    | Back up or copy settings between devices as JSON             |
```

with:

```markdown
| Export / Import Settings                    | Back up or copy settings between devices as JSON. Secrets are encrypted under a password you choose at export time |
```

- [ ] **Step 2: Update the Features list entry**

Replace:

```markdown
- **Export / import settings** as JSON (including affiliate key, with permission)
```

with:

```markdown
- **Export / import settings** as JSON — configuration in the clear, secrets encrypted under a password you set at export time
```

- [ ] **Step 3: Add an Export format subsection**

Add after the Settings Reference table:

```markdown
### Export format

An exported file keeps configuration in plaintext so it can be inspected and
diffed, and puts secrets — currently the SumUp affiliate key — into a single
encrypted block:

```json
{
  "settings": { "kioskName": "...", "currency": "EUR" },
  "secrets": { "v": 1, "kdf": "PBKDF2WithHmacSHA256", "iterations": 600000,
               "salt": "...", "iv": "...", "ciphertext": "..." }
}
```

Encryption is AES-256-GCM with a key derived by PBKDF2-HMAC-SHA256. Deriving the
key takes a few seconds on kiosk hardware — that is intentional, and it is why
the dialog shows a progress indicator.

**The password cannot be recovered.** If it is lost, the settings in the file are
still importable via **Import settings only**, but the secrets are gone and the
affiliate key must be re-entered by hand.

Exports created by version 1.3.5 and earlier stored the affiliate key in
plaintext. Those files still import, with no password.
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "Document the password-encrypted settings export format"
```

---

## Verification

After all tasks:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Expected: BUILD SUCCESSFUL, with 23 new tests (10 in `SecretsCryptoTest`, 13 in `SettingsExportFileTest`) on top of the existing 84.

The manual device checks in Task 4 Step 6 are **required** before release. The crypto is unit-tested, but the dialog threading, the file-picker round trip, and the real Lenovo M9 derivation time are not.
