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
