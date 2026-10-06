package io.github.sanadlab.testkotlin

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Tests for @Memoize in a Kotlin `object`: @JvmStatic functions (static caches)
 * next to plain members (instance caches of the singleton).
 */
class RegistryTest {

    @Before
    fun setUp() {
        Registry.reset()
    }

    @Test
    fun jvmStaticFunctionInObjectIsStaticAndCached() {
        val method = Registry::class.java.getMethod("lookup", Int::class.javaPrimitiveType)
        assertTrue(Modifier.isStatic(method.modifiers))

        assertEquals("one", Registry.lookup(1))
        assertEquals("one", Registry.lookup(1))
        assertEquals(1, Registry.lookupCalls)
    }

    @Test
    fun plainObjectMemberIsCached() {
        val before = Registry.describeCalls
        assertEquals("#41", Registry.describe(41))
        assertEquals("#41", Registry.describe(41))
        assertEquals(before + 1, Registry.describeCalls)
    }

    @Test
    fun instanceInvalidatorFlushesStaticCache() {
        Registry.lookup(1)
        Registry.rename(1, "uno")
        assertEquals("uno", Registry.lookup(1))
        assertEquals(2, Registry.lookupCalls)
    }

    @Test
    fun staticInvalidateCacheEntryEvictsOneEntry() {
        Registry.lookup(1)
        Registry.lookup(2)

        Registry.forget(1)

        Registry.lookup(1)   // miss
        Registry.lookup(2)   // hit
        assertEquals(3, Registry.lookupCalls)
    }

    @Test
    fun bareStaticInvalidatorKeepsInstanceCaches() {
        Registry.describe(42)
        val describeCallsBefore = Registry.describeCalls
        Registry.lookup(1)

        Registry.reset()   // also sets lookupCalls to 0

        Registry.lookup(1)   // miss
        assertEquals(1, Registry.lookupCalls)
        Registry.describe(42)   // hit
        assertEquals(describeCallsBefore, Registry.describeCalls)
    }
}
