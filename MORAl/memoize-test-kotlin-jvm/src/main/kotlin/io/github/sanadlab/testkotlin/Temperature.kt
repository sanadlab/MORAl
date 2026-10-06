package io.github.sanadlab.testkotlin

import io.github.sanadlab.annotations.CacheInvalidate
import io.github.sanadlab.annotations.Memoize

/**
 * Kotlin test subject: a companion object with @JvmStatic functions. Kotlin
 * puts the real function in Temperature.Companion, and a static bridge with
 * the same annotations in Temperature. The transform must instrument only the
 * companion copy.
 */
class Temperature {
    companion object {
        var toFahrenheitCalls = 0

        private var offset = 32.0

        @JvmStatic
        @Memoize
        fun toFahrenheit(celsius: Int): Double {
            toFahrenheitCalls++
            return celsius * 9.0 / 5.0 + offset
        }

        // Not @JvmStatic, so it has no bridge. It flushes the companion cache only.
        @CacheInvalidate("toFahrenheit")
        fun setOffset(value: Double) {
            offset = value
        }

        // @JvmStatic invalidator: Kotlin also copies this annotation to a static bridge.
        @JvmStatic
        @CacheInvalidate
        fun resetTemperature() {
            offset = 32.0
            toFahrenheitCalls = 0
        }
    }
}
