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
