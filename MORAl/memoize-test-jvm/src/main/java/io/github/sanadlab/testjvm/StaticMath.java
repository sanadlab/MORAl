package io.github.sanadlab.testjvm;

import io.github.sanadlab.annotations.CacheInvalidate;
import io.github.sanadlab.annotations.InvalidateCacheEntry;
import io.github.sanadlab.annotations.Invalidation;
import io.github.sanadlab.annotations.Memoize;

/**
 * JVM test subject for static {@code @Memoize} methods. The class has no
 * instances: every cache and every invalidator is static.
 *
 * The {@code *Calls} counters let tests tell a cache hit from a real call.
 */
public final class StaticMath {

    public static int squareCalls;
    public static int fibCalls;
    public static int labelCalls;

    private static int offset;

    private StaticMath() { }

    @Memoize
    public static int square(int x) {
        squareCalls++;
        return x * x + offset;
    }

    // Recursive: each inner call also goes through the cache.
    @Memoize(maxSize = 256)
    public static long fib(int n) {
        fibCalls++;
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    @Memoize
    public static String label(String name, int n) {
        labelCalls++;
        return name + "#" + n;
    }

    // Legacy name list on a static method: flushes square only.
    @CacheInvalidate("square")
    public static void setOffset(int newOffset) {
        offset = newOffset;
    }

    // Single-entry eviction from a static cache.
    @InvalidateCacheEntry(method = "label", keys = {0, 1})
    public static void relabel(String name, int n) { }

    // Structured targets on a static method: KEY_BUILDER with a static helper, and FLUSH.
    @CacheInvalidate(targets = {
        @Invalidation(method = "square", keyBuilder = "squareKey"),
        @Invalidation(method = "fib", allEntries = true)
    })
    public static void touchSquareAndFib(int x) { }

    private static Object squareKey(int x) {
        return x;
    }

    // Bare @CacheInvalidate on a static method: flushes every static cache of this class.
    @CacheInvalidate
    public static void reset() {
        offset = 0;
        squareCalls = 0;
        fibCalls = 0;
        labelCalls = 0;
    }

    /** Static memoized method in a static nested class. It has its own static manager. */
    public static final class Nested {
        public static int cubeCalls;

        private Nested() { }

        @Memoize
        public static long cube(int x) {
            cubeCalls++;
            return (long) x * x * x;
        }
    }
}
