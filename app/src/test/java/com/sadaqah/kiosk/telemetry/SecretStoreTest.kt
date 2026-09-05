package com.sadaqah.kiosk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** The fake must not be quietly more forgiving than the real store, or every
     *  test above it is testing a store the device never uses. */
    @Test
    fun putOverwritesRatherThanAccumulating() {
        val store = InMemorySecretStore()
        store.put("key", "first")
        store.put("key", "second")
        assertEquals("second", store.get("key"))
    }
}
