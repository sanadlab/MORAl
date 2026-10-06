package io.github.sanadlab.plugin

import org.junit.Assert.*
import org.junit.Test

/**
 * In JVM mode, the plugin transforms every class file in the output directory
 * after each compile. With incremental compilation, some of those classes were
 * already transformed by an earlier build. The transformer must skip them.
 */
class AlreadyTransformedTest {

    private fun assertSecondTransformIsSkipped(className: String, body: String) {
        val first = JvmBytecodeTransformer.transform(JavaSources.compile(className, body), className)
        assertNotNull("first transform must change the class", first)
        assertNull("second transform must skip the class", JvmBytecodeTransformer.transform(first!!, className))
    }

    @Test
    fun instanceClassIsTransformedOnlyOnce() = assertSecondTransformIsSkipped("Counter", """
        public class Counter {
            @Memoize public int twice(int x) { return 2 * x; }
            @CacheInvalidate public void reset() { }
        }
    """)

    @Test
    fun staticClassIsTransformedOnlyOnce() = assertSecondTransformIsSkipped("Statics", """
        public class Statics {
            @Memoize public static int twice(int x) { return 2 * x; }
        }
    """)

    @Test
    fun classWithOnlyInvalidatorsIsTransformedOnlyOnce() = assertSecondTransformIsSkipped("OnlyInvalidator", """
        public class OnlyInvalidator {
            @CacheInvalidate public void reset() { }
        }
    """)
}
