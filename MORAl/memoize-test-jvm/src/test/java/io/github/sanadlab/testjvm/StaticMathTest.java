package io.github.sanadlab.testjvm;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntUnaryOperator;

import static org.junit.Assert.*;

/**
 * End-to-end tests for static {@code @Memoize} methods and static invalidators.
 * A static cache lives as long as its class, so each test starts with
 * {@link StaticMath#reset()}, which flushes all static caches of StaticMath.
 */
public class StaticMathTest {

    @Before
    public void setUp() {
        StaticMath.reset();
    }

    @Test
    public void staticMethodIsCached() {
        assertEquals(25, StaticMath.square(5));
        assertEquals(25, StaticMath.square(5));
        assertEquals(1, StaticMath.squareCalls);
    }

    @Test
    public void differentArgsMiss() {
        assertEquals(4, StaticMath.square(2));
        assertEquals(9, StaticMath.square(3));
        assertEquals(2, StaticMath.squareCalls);
    }

    @Test
    public void multiArgKey() {
        assertEquals("a#1", StaticMath.label("a", 1));
        assertEquals("a#2", StaticMath.label("a", 2));
        assertEquals("b#1", StaticMath.label("b", 1));
        assertEquals("a#1", StaticMath.label("a", 1));   // hit
        assertEquals(3, StaticMath.labelCalls);
    }

    @Test
    public void recursiveCallsUseTheCache() {
        // With the cache, the body runs one time for each n in 0..50.
        assertEquals(12586269025L, StaticMath.fib(50));
        assertEquals(51, StaticMath.fibCalls);
    }

    @Test
    public void methodReferenceAndReflectionUseTheCache() throws Exception {
        StaticMath.square(7);
        IntUnaryOperator ref = StaticMath::square;
        Method method = StaticMath.class.getMethod("square", int.class);

        assertEquals(49, ref.applyAsInt(7));
        assertEquals(49, method.invoke(null, 7));
        assertEquals(1, StaticMath.squareCalls);
    }

    @Test
    public void legacyStaticInvalidationFlushesOnlyTheNamedCache() {
        StaticMath.square(3);
        StaticMath.fib(10);
        int fibCallsBefore = StaticMath.fibCalls;

        StaticMath.setOffset(1);

        assertEquals(10, StaticMath.square(3));   // miss: 3 * 3 + 1
        assertEquals(2, StaticMath.squareCalls);
        assertEquals(55, StaticMath.fib(10));     // hit: fib is not named
        assertEquals(fibCallsBefore, StaticMath.fibCalls);
    }

    @Test
    public void staticInvalidateCacheEntryEvictsOneEntry() {
        StaticMath.label("a", 1);
        StaticMath.label("b", 2);

        StaticMath.relabel("a", 1);

        StaticMath.label("a", 1);   // miss
        StaticMath.label("b", 2);   // hit
        assertEquals(3, StaticMath.labelCalls);
    }

    @Test
    public void staticStructuredInvalidation() {
        StaticMath.square(4);
        StaticMath.square(5);
        StaticMath.fib(10);
        int fibCallsBefore = StaticMath.fibCalls;

        StaticMath.touchSquareAndFib(4);

        StaticMath.square(4);   // miss: evicted through the static keyBuilder
        StaticMath.square(5);   // hit
        assertEquals(3, StaticMath.squareCalls);
        StaticMath.fib(10);     // miss: the full fib cache was flushed, so fib(10)..fib(0) run again
        assertEquals(fibCallsBefore + 11, StaticMath.fibCalls);
    }

    @Test
    public void bareStaticInvalidationFlushesAllStaticCachesOfTheClass() {
        StaticMath.square(2);
        StaticMath.label("x", 1);
        StaticMath.Nested.cube(3);
        int cubeCallsBefore = StaticMath.Nested.cubeCalls;

        StaticMath.reset();   // also sets the counters to 0

        StaticMath.square(2);
        StaticMath.label("x", 1);
        assertEquals(1, StaticMath.squareCalls);
        assertEquals(1, StaticMath.labelCalls);

        // Nested is a different class with its own static manager. reset() does not touch it.
        StaticMath.Nested.cube(3);
        assertEquals(cubeCallsBefore, StaticMath.Nested.cubeCalls);
    }

    @Test
    public void staticNestedClassIsCached() {
        int before = StaticMath.Nested.cubeCalls;
        assertEquals(125L, StaticMath.Nested.cube(5));
        assertEquals(125L, StaticMath.Nested.cube(5));
        assertEquals(before + 1, StaticMath.Nested.cubeCalls);
    }

    @Test
    public void concurrentCallersGetCorrectValues() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(pool.submit(() -> {
                    for (int i = 0; i < 2000; i++) {
                        int x = i % 50;
                        if (StaticMath.square(x) != x * x) return false;
                    }
                    return true;
                }));
            }
            for (Future<Boolean> result : results) {
                assertTrue(result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void transformAddsOnlyStaticFields() throws Exception {
        Field manager = StaticMath.class.getDeclaredField("__memoStaticCacheManager");
        assertTrue(Modifier.isStatic(manager.getModifiers()));
        assertTrue(manager.isSynthetic());

        int dispatchers = 0;
        for (Field field : StaticMath.class.getDeclaredFields()) {
            if (field.getName().startsWith("__memoDispatcher_")) {
                assertTrue(field.getName(), Modifier.isStatic(field.getModifiers()));
                dispatchers++;
            }
        }
        assertEquals(3, dispatchers);   // square, fib, label

        // No instance caches, so no instance manager.
        assertThrows(NoSuchFieldException.class,
            () -> StaticMath.class.getDeclaredField("__memoCacheManager"));
    }
}
