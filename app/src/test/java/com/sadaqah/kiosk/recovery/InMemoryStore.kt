package com.sadaqah.kiosk.recovery

class InMemoryStore : KeyValueStore {
    private val ints = mutableMapOf<String, Int>()
    private val longs = mutableMapOf<String, Long>()

    override fun getInt(key: String, default: Int): Int = ints[key] ?: default
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun getLong(key: String, default: Long): Long = longs[key] ?: default
    override fun putLong(key: String, value: Long) { longs[key] = value }
}
