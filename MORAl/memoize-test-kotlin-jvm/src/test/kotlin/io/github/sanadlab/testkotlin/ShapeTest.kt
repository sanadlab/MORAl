package io.github.sanadlab.testkotlin

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for @Memoize and @CacheInvalidate on Kotlin interface default methods.
 * The expected behavior is the same as for a class member: one cache for each
 * instance, and an invalidator clears the caches of its own instance only.
 */
class ShapeTest {

    @Test
    fun interfaceLoads() {
        // The JVM rejects an interface with non-public or non-static fields.
        assertTrue(Shape::class.java.isInterface)
        assertTrue(Shape::class.java.declaredFields.none { it.name.startsWith("__memo") })
    }

    @Test
    fun defaultMethodIsCached() {
        val square = Square(3)
        assertEquals(9, square.area())
        assertEquals(9, square.area())
        assertEquals(1, square.areaCalls)
    }

    @Test
    fun defaultMethodWithArgumentsIsCached() {
        val square = Square(3)
        assertEquals(30, square.scaled(10))
        assertEquals(30, square.scaled(10))
        assertEquals(60, square.scaled(20))
        assertEquals(2, square.scaledCalls)
    }

    @Test
    fun instancesHaveSeparateCaches() {
        val small = Square(2)
        val large = Square(5)
        assertEquals(4, small.area())
        assertEquals(25, large.area())
        assertEquals(4, small.area())
        assertEquals(1, small.areaCalls)
        assertEquals(1, large.areaCalls)
    }

    @Test
    fun defaultInvalidatorClearsTheCache() {
        val square = Square(3)
        square.area()
        square.side = 4
        square.resetShape()
        assertEquals(16, square.area())
        assertEquals(2, square.areaCalls)
    }

    @Test
    fun invalidationIsPerInstance() {
        val a = Square(2)
        val b = Square(3)
        a.area()
        b.area()

        a.resetShape()

        b.area()   // must still be a hit
        assertEquals(1, b.areaCalls)
    }

    @Test
    fun overrideWithoutMemoizeIsNotCached() {
        val tile = Tile(3)
        assertEquals(18, tile.area())
        assertEquals(18, tile.area())
        assertEquals(2, tile.areaCalls)
    }

    @Test
    fun namedDefaultInvalidatorClearsOnlyItsTarget() {
        val square = Square(3)
        square.area()
        square.scaled(10)
        square.side = 4

        square.resetArea()

        assertEquals(16, square.area())    // miss
        assertEquals(2, square.areaCalls)
        square.scaled(10)                  // hit: not named
        assertEquals(1, square.scaledCalls)
    }

    @Test
    fun namedInvalidatorWithOverriddenTargetClearsNothing() {
        val tile = Tile(3)
        tile.scaled(10)

        tile.resetArea()   // area() is not memoized in Tile

        tile.scaled(10)    // still a hit: no fallback to clearing all caches
        assertEquals(1, tile.scaledCalls)
    }

    @Test
    fun inheritedDefaultInOverridingClassIsCached() {
        val tile = Tile(3)
        assertEquals(30, tile.scaled(10))
        assertEquals(30, tile.scaled(10))
        assertEquals(1, tile.scaledCalls)
    }
}
