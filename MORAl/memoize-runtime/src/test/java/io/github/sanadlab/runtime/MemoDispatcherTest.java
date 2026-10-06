package io.github.sanadlab.runtime;

import io.github.sanadlab.annotations.EvictionPolicy;
import io.github.sanadlab.annotations.ThreadSafety;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class MemoDispatcherTest {

    @Test
    public void cachesResultOnSecondCall() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128);
        AtomicInteger computeCount = new AtomicInteger(0);

        Object result1 = dispatcher.invoke(new Object[]{10}, () -> {
            computeCount.incrementAndGet();
            return true;
        });
        Object result2 = dispatcher.invoke(new Object[]{10}, () -> {
            computeCount.incrementAndGet();
            return true;
        });

        assertEquals(true, result1);
        assertEquals(true, result2);
        assertEquals(1, computeCount.get());
    }

    @Test
    public void differentArgsMissCache() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128);
        AtomicInteger computeCount = new AtomicInteger(0);

        dispatcher.invoke(new Object[]{10}, () -> {
            computeCount.incrementAndGet();
            return "ten";
        });
        dispatcher.invoke(new Object[]{20}, () -> {
            computeCount.incrementAndGet();
            return "twenty";
        });

        assertEquals(2, computeCount.get());
    }

    @Test
    public void zeroArgMethodCached() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("length", 128);
        AtomicInteger computeCount = new AtomicInteger(0);

        Object r1 = dispatcher.invoke(new Object[]{}, () -> {
            computeCount.incrementAndGet();
            return 42;
        });
        Object r2 = dispatcher.invoke(new Object[]{}, () -> {
            computeCount.incrementAndGet();
            return 42;
        });

        assertEquals(42, r1);
        assertEquals(42, r2);
        assertEquals(1, computeCount.get());
    }

    @Test
    public void nullReturnValueCached() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128);
        AtomicInteger computeCount = new AtomicInteger(0);

        Object r1 = dispatcher.invoke(new Object[]{"key"}, () -> {
            computeCount.incrementAndGet();
            return null;
        });
        Object r2 = dispatcher.invoke(new Object[]{"key"}, () -> {
            computeCount.incrementAndGet();
            return null;
        });

        assertNull(r1);
        assertNull(r2);
        assertEquals(1, computeCount.get());
    }

    @Test
    public void fifoPolicyEvictsOldestInsertion() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 3, -1,
                EvictionPolicy.FIFO, ThreadSafety.CONCURRENT, false);
        AtomicInteger computeCount = new AtomicInteger(0);

        // Fill cache
        for (int i = 1; i <= 3; i++) {
            final int n = i;
            dispatcher.invoke(new Object[]{n}, () -> {
                computeCount.incrementAndGet();
                return "v" + n;
            });
        }
        assertEquals(3, computeCount.get());

        // Access key 1 repeatedly -- LRU would rescue it, FIFO should not.
        dispatcher.invoke(new Object[]{1}, () -> { computeCount.incrementAndGet(); return "rescued"; });
        dispatcher.invoke(new Object[]{1}, () -> { computeCount.incrementAndGet(); return "rescued"; });
        // Both of the above should have been hits (no new compute).
        assertEquals(3, computeCount.get());

        // Insert a 4th key -- FIFO evicts key 1 (oldest insertion).
        dispatcher.invoke(new Object[]{4}, () -> { computeCount.incrementAndGet(); return "v4"; });
        assertEquals(4, computeCount.get());

        // Re-requesting key 1 must miss and recompute.
        dispatcher.invoke(new Object[]{1}, () -> { computeCount.incrementAndGet(); return "v1*"; });
        assertEquals(5, computeCount.get());
    }

    @Test
    public void lfuPolicyEvictsLeastFrequent() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 3, -1,
                EvictionPolicy.LFU, ThreadSafety.CONCURRENT, false);
        AtomicInteger computeCount = new AtomicInteger(0);

        for (int i = 1; i <= 3; i++) {
            final int n = i;
            dispatcher.invoke(new Object[]{n}, () -> {
                computeCount.incrementAndGet();
                return "v" + n;
            });
        }
        // Hit keys 1 and 2 multiple times; leave key 3 cold.
        for (int i = 0; i < 4; i++) dispatcher.invoke(new Object[]{1}, () -> { computeCount.incrementAndGet(); return "x"; });
        for (int i = 0; i < 2; i++) dispatcher.invoke(new Object[]{2}, () -> { computeCount.incrementAndGet(); return "x"; });
        assertEquals("hits should not trigger compute", 3, computeCount.get());

        // Insert key 4 -- LFU evicts key 3 (min frequency).
        dispatcher.invoke(new Object[]{4}, () -> { computeCount.incrementAndGet(); return "v4"; });
        assertEquals(4, computeCount.get());

        // Key 3 now misses; keys 1 and 2 still hit.
        dispatcher.invoke(new Object[]{3}, () -> { computeCount.incrementAndGet(); return "v3*"; });
        assertEquals(5, computeCount.get());
        dispatcher.invoke(new Object[]{1}, () -> { computeCount.incrementAndGet(); return "x"; });
        dispatcher.invoke(new Object[]{2}, () -> { computeCount.incrementAndGet(); return "x"; });
        assertEquals("key 1 and key 2 should still be cached", 5, computeCount.get());
    }

    @Test
    public void invalidateEntryDropsSingleKey() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128);
        AtomicInteger computeCount = new AtomicInteger(0);

        dispatcher.invoke(new Object[]{1}, () -> { computeCount.incrementAndGet(); return "v1"; });
        dispatcher.invoke(new Object[]{2}, () -> { computeCount.incrementAndGet(); return "v2"; });
        dispatcher.invoke(new Object[]{3}, () -> { computeCount.incrementAndGet(); return "v3"; });
        assertEquals(3, computeCount.get());

        // Drop ONLY key 2.
        dispatcher.invalidateEntry(new Object[]{2});

        // Keys 1 and 3 still cached; key 2 must recompute.
        dispatcher.invoke(new Object[]{1}, () -> { computeCount.incrementAndGet(); return "X"; });
        dispatcher.invoke(new Object[]{3}, () -> { computeCount.incrementAndGet(); return "X"; });
        assertEquals("untouched entries must still hit", 3, computeCount.get());

        dispatcher.invoke(new Object[]{2}, () -> { computeCount.incrementAndGet(); return "v2*"; });
        assertEquals(4, computeCount.get());
    }

    @Test
    public void invalidateEntryUnknownKeyIsNoOp() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128);
        dispatcher.invoke(new Object[]{1}, () -> "v1");
        dispatcher.invalidateEntry(new Object[]{999}); // nothing to drop
        assertEquals(1, dispatcher.size());
    }

    @Test
    public void invalidateClearsCache() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128);
        AtomicInteger computeCount = new AtomicInteger(0);

        dispatcher.invoke(new Object[]{1}, () -> {
            computeCount.incrementAndGet();
            return "first";
        });

        dispatcher.invalidate();

        dispatcher.invoke(new Object[]{1}, () -> {
            computeCount.incrementAndGet();
            return "second";
        });

        assertEquals(2, computeCount.get());
    }

    @Test
    public void statsRecordHitsAndMisses() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128, -1,
                EvictionPolicy.LRU, ThreadSafety.CONCURRENT, true);

        dispatcher.invoke(new Object[]{1}, () -> "a"); // miss
        dispatcher.invoke(new Object[]{1}, () -> "a"); // hit
        dispatcher.invoke(new Object[]{2}, () -> "b"); // miss
        dispatcher.invoke(new Object[]{1}, () -> "a"); // hit

        CacheStats stats = dispatcher.getStats();
        assertNotNull(stats);
        assertEquals(2, stats.getHitCount());
        assertEquals(2, stats.getMissCount());
        assertEquals(0.5, stats.getHitRate(), 0.001);
    }

    // --- Auto-monitor tests ---

    @Test
    public void autoMonitorDisablesCacheOnLowHitRate() throws Exception {
        // monitorWindow=10, minHitRate=0.5 → needs 50% hits to stay enabled
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128, -1,
                EvictionPolicy.LRU, ThreadSafety.CONCURRENT, false,
                true, 0.5, 10);

        assertFalse(dispatcher.isDisabled());

        // 10 unique calls → 10 misses, 0 hits → hitRate=0.0 < 0.5 → disabled
        for (int i = 0; i < 10; i++) {
            CacheKeyWrapper key = dispatcher.buildKey(new Object[]{i});
            Object cached = dispatcher.getIfCached(key);
            assertNull("Should be a miss for unique args", cached);
            dispatcher.putInCache(key, "result" + i);
        }

        assertTrue("Cache should be disabled after 10 misses", dispatcher.isDisabled());
    }

    @Test
    public void autoMonitorKeepsCacheOnHighHitRate() throws Exception {
        // monitorWindow=10, minHitRate=0.3 → needs 30% hits
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128, -1,
                EvictionPolicy.LRU, ThreadSafety.CONCURRENT, false,
                true, 0.3, 10);

        // 2 unique calls (2 misses), then 8 repeated calls (8 hits) = 80% hit rate
        CacheKeyWrapper key0 = dispatcher.buildKey(new Object[]{0});
        dispatcher.getIfCached(key0); // miss
        dispatcher.putInCache(key0, "zero");

        CacheKeyWrapper key1 = dispatcher.buildKey(new Object[]{1});
        dispatcher.getIfCached(key1); // miss
        dispatcher.putInCache(key1, "one");

        for (int i = 0; i < 8; i++) {
            CacheKeyWrapper key = dispatcher.buildKey(new Object[]{0});
            Object cached = dispatcher.getIfCached(key); // hit
            assertNotNull("Should be cached", cached);
        }

        assertFalse("Cache should remain enabled with 80% hit rate", dispatcher.isDisabled());
    }

    @Test
    public void autoMonitorReenableResetsStats() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128, -1,
                EvictionPolicy.LRU, ThreadSafety.CONCURRENT, false,
                true, 0.5, 5);

        // 5 unique calls → all misses → disabled
        for (int i = 0; i < 5; i++) {
            CacheKeyWrapper key = dispatcher.buildKey(new Object[]{i});
            dispatcher.getIfCached(key);
            dispatcher.putInCache(key, "val" + i);
        }
        assertTrue(dispatcher.isDisabled());

        // Reenable
        dispatcher.reenable();
        assertFalse(dispatcher.isDisabled());

        // Stats should be reset
        CacheStats stats = dispatcher.getStats();
        assertEquals(0, stats.getRequestCount());
    }

    @Test
    public void autoMonitorBypassesCacheWhenDisabled() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128, -1,
                EvictionPolicy.LRU, ThreadSafety.CONCURRENT, false,
                true, 0.5, 5);

        // Force disable by making all misses
        for (int i = 0; i < 5; i++) {
            CacheKeyWrapper key = dispatcher.buildKey(new Object[]{i});
            dispatcher.getIfCached(key);
            dispatcher.putInCache(key, "val" + i);
        }
        assertTrue(dispatcher.isDisabled());

        // Now getIfCached should always return null (bypass)
        CacheKeyWrapper key = dispatcher.buildKey(new Object[]{0});
        assertNull("Disabled cache should return null", dispatcher.getIfCached(key));

        // putInCache should not store
        dispatcher.putInCache(key, "should-not-store");
        assertNull("Disabled cache should still return null", dispatcher.getIfCached(key));
    }

    @Test
    public void multipleArgsCompositeKey() throws Exception {
        MemoDispatcher dispatcher = new MemoDispatcher("test", 128);
        AtomicInteger computeCount = new AtomicInteger(0);

        dispatcher.invoke(new Object[]{1, "a", 3.14}, () -> {
            computeCount.incrementAndGet();
            return "result";
        });
        dispatcher.invoke(new Object[]{1, "a", 3.14}, () -> {
            computeCount.incrementAndGet();
            return "result";
        });
        dispatcher.invoke(new Object[]{1, "b", 3.14}, () -> {
            computeCount.incrementAndGet();
            return "different";
        });

        assertEquals(2, computeCount.get());
    }

    // --- Compute timing for ASM-injected code (computeStart + putInCache) ---

    @Test
    public void computeStartIsZeroWhenLoggingIsOff() {
        LogLevel old = MemoLogger.getLevel();
        MemoLogger.setLevel(LogLevel.OFF);
        try {
            MemoDispatcher dispatcher = new MemoDispatcher("test", 128);
            CacheKeyWrapper key = dispatcher.buildKey(new Object[]{1});
            assertEquals(0L, dispatcher.computeStart());

            dispatcher.putInCache(key, "one", 0L);

            assertEquals(0, dispatcher.getMetrics().getComputeSamples());
            assertEquals("one", MemoDispatcher.unwrap(dispatcher.getIfCached(key)));
        } finally {
            MemoLogger.setLevel(old);
        }
    }

    @Test
    public void putInCacheRecordsComputeTimeWhenTimingIsOn() {
        LogLevel old = MemoLogger.getLevel();
        MemoLogger.setLevel(LogLevel.INFO);
        try {
            MemoDispatcher dispatcher = new MemoDispatcher("test", 128);
            CacheKeyWrapper key = dispatcher.buildKey(new Object[]{1});
            long start = dispatcher.computeStart();
            assertNotEquals(0L, start);

            dispatcher.putInCache(key, "one", start);

            assertEquals(1, dispatcher.getMetrics().getComputeSamples());
            assertEquals("one", MemoDispatcher.unwrap(dispatcher.getIfCached(key)));
        } finally {
            MemoLogger.setLevel(old);
        }
    }
}
