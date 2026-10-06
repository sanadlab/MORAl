package io.github.sanadlab.testkotlin

import io.github.sanadlab.annotations.CacheInvalidate
import io.github.sanadlab.annotations.Memoize

/**
 * Kotlin test subject: an interface with default methods that carry @Memoize
 * and @CacheInvalidate. How Kotlin compiles them depends on -Xjvm-default.
 */
interface Shape {
    val side: Int
    var areaCalls: Int
    var scaledCalls: Int

    @Memoize
    fun area(): Int {
        areaCalls++
        return side * side
    }

    @Memoize
    fun scaled(factor: Int): Int {
        scaledCalls++
        return side * factor
    }

    // Bare @CacheInvalidate on a default method.
    @CacheInvalidate
    fun resetShape() { }

    // Named target. Tile overrides area() without @Memoize, so Kotlin's copy of
    // this method in Tile has nothing to clear and must not clear other caches.
    @CacheInvalidate("area")
    fun resetArea() { }
}

/** Uses the default methods as they are. */
class Square(override var side: Int) : Shape {
    override var areaCalls = 0
    override var scaledCalls = 0
}

/** Overrides area() without @Memoize, so area() is not cached here. */
class Tile(override var side: Int) : Shape {
    override var areaCalls = 0
    override var scaledCalls = 0

    override fun area(): Int {
        areaCalls++
        return side * side * 2
    }
}
