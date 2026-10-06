package io.github.sanadlab.testkotlin

import io.github.sanadlab.annotations.CacheInvalidate
import io.github.sanadlab.annotations.InvalidateCacheEntry
import io.github.sanadlab.annotations.Memoize

/**
 * Kotlin test subject: an `object`. A @JvmStatic function in an object is a
 * real static method of Registry. A plain member is an instance method of the
 * singleton. So Registry has static and instance caches.
 */
object Registry {
    var lookupCalls = 0
    var describeCalls = 0

    private val names = mutableMapOf(1 to "one", 2 to "two")

    @JvmStatic
    @Memoize
    fun lookup(id: Int): String {
        lookupCalls++
        return names[id] ?: "unknown"
    }

    @Memoize
    fun describe(id: Int): String {
        describeCalls++
        return "#$id"
    }

    // Instance method of the singleton that flushes a static cache.
    @CacheInvalidate("lookup")
    fun rename(id: Int, name: String) {
        names[id] = name
    }

    // Static method that evicts one entry of a static cache.
    @JvmStatic
    @InvalidateCacheEntry(method = "lookup", keys = [0])
    fun forget(id: Int) { }

    // Bare @CacheInvalidate on a static method: flushes the static caches only.
    @JvmStatic
    @CacheInvalidate
    fun reset() {
        names.clear()
        names[1] = "one"
        names[2] = "two"
        lookupCalls = 0
    }
}
