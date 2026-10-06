package io.github.sanadlab.testkotlin

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Tests for @Memoize on Kotlin top-level functions (static methods of the
 * file class).
 */
class TopLevelFunctionsTest {

    @Before
    fun setUp() {
        resetSlugs()
    }

    @Test
    fun staticInitializerCanCallMemoizedFunction() {
        // If the static dispatchers were not ready, the file class would fail to initialize.
        assertEquals("home-page", HOME_SLUG)
    }

    @Test
    fun topLevelFunctionIsCached() {
        assertEquals("about-us", slugify("About Us"))
        assertEquals("about-us", slugify("About Us"))
        assertEquals(1, slugifyCalls)
    }

    @Test
    fun namedInvalidationFlushesCache() {
        slugify("A B")
        setSlugSeparator("_")
        assertEquals("a_b", slugify("A B"))
        assertEquals(2, slugifyCalls)
    }

    @Test
    fun bareInvalidationFlushesCache() {
        slugify("X Y")
        resetSlugs()   // also sets the counter to 0
        slugify("X Y")
        assertEquals(1, slugifyCalls)
    }

    @Test
    fun fileClassGetsStaticManager() {
        val fileClass = Class.forName("io.github.sanadlab.testkotlin.TopLevelFunctionsKt")
        val manager = fileClass.getDeclaredField("__memoStaticCacheManager")
        assertTrue(Modifier.isStatic(manager.modifiers))
    }
}
