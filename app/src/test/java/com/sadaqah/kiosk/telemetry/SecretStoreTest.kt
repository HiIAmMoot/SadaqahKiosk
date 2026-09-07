package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStoreTest {
    @Test
    fun storesAndReturnsAValue() {
        val store = InMemorySecretStore()
        store.put("url", "https://x.supabase.co")
        assertEquals("https://x.supabase.co", store.get("url"))
    }

    @Test
    fun anAbsentKeyIsNull() {
        assertNull(InMemorySecretStore().get("nothing"))
    }

    @Test
    fun removeDeletesOnlyItsOwnKey() {
        val store = InMemorySecretStore()
        store.put("url", "https://x.supabase.co")
        store.put("key", "anon")
        store.remove("url")
        assertNull(store.get("url"))
        assertEquals("anon", store.get("key"))
    }

    /** Pins the fake's own overwrite semantics. It says nothing about the Keystore
     *  adapter, which cannot run here — that both stores overwrite is on the device
     *  check, not proven by this test. */
    @Test
    fun putOverwritesRatherThanAccumulating() {
        val store = InMemorySecretStore()
        store.put("key", "first")
        store.put("key", "second")
        assertEquals("second", store.get("key"))
    }

    @Test
    fun aStoredValueReportsThatItWasStored() {
        assertTrue(InMemorySecretStore().put("key", "value"))
    }

    /**
     * The state the real adapter reaches when the Keystore is unusable, and the one
     * InMemorySecretStore cannot reach: writes fail and every read comes back empty.
     * The layer above must read this as "not configured" rather than believing a
     * save that never happened.
     */
    @Test
    fun anUnwritableStoreReportsFailureAndStaysEmpty() {
        val store = UnwritableSecretStore()
        assertFalse(store.put("key", "value"))
        assertNull(store.get("key"))
        store.remove("key")
    }
}
