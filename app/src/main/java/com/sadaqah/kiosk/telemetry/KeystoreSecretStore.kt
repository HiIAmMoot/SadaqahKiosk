package com.sadaqah.kiosk.telemetry

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM under a hardware-backed AndroidKeyStore key, ciphertext in
 * SharedPreferences.
 *
 * This defeats `adb pull`, cloud backup extraction and offline attack on a stolen
 * device. It does NOT defeat root on a running kiosk — any key the app can decrypt
 * in order to use it, an attacker in that position can also read. The real
 * boundary is the insert-only RLS policy on the server, which is why this class is
 * allowed to be this simple.
 */
class KeystoreSecretStore(
    context: Context,
    prefsName: String = "telemetry_secrets"
) : SecretStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Guarded like [get]: `secretKey()` reaches the Keystore, which can throw on a
     *  device that is full, provisioning-broken, or in a bad secure-hardware state.
     *  The failure is reported rather than swallowed so the operator standing at the
     *  kiosk learns the credentials did not save. */
    override fun put(key: String, value: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // get() reads the IV back at a fixed offset. If a provider ever returned a
        // different length, every write would keep succeeding while every read
        // returned null forever — a silent, permanent outage. Fail loudly instead.
        check(cipher.iv.size == IV_BYTES) { "unexpected GCM IV length: ${cipher.iv.size}" }
        val packed = cipher.iv + ciphertext
        prefs.edit().putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        true
    } catch (_: Throwable) {
        false
    }

    /** Returns null on any failure. The key is invalidated by a factory reset, an
     *  uninstall, or clearing app data — not by a lock-screen change, which only
     *  invalidates keys built with setUserAuthenticationRequired, and this one is
     *  deliberately not. Once invalidated, the stored ciphertext decrypts to
     *  nothing forever; reporting that as "absent" makes the kiosk ask to be
     *  reconfigured instead of crashing on every flush.
     *
     *  The catch is deliberately total, which means it also hides a construction
     *  bug — a wrong transformation or a packing error would present exactly like
     *  "nothing was ever stored". The device check for this class must therefore
     *  assert a put/get round trip returns the original value, not merely that it
     *  did not crash. */
    override fun get(key: String): String? = try {
        val packed = Base64.decode(prefs.getString(key, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES)
        )
        String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }

    /** commit rather than apply: this is the credential-clearing path, and apply
     *  returns before the write lands. A "clear credentials" followed by a process
     *  kill must not leave the ciphertext on disk. */
    override fun remove(key: String) {
        try {
            prefs.edit().remove(key).commit()
        } catch (_: Throwable) {
            // Nothing useful remains to do, and the interface promises not to throw.
        }
    }

    /** Synchronized because get-or-create is not atomic: two threads racing here
     *  would both generate, and the second generation replaces the alias — orphaning
     *  any ciphertext the first thread had just written. A flush thread reading while
     *  the settings screen writes is exactly that shape. */
    @Synchronized
    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sadaqah_telemetry_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
