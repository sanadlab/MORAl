package io.github.sanadlab.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

public class LfuMemoCacheTest {

    @Test
    public void basicGetPut() {
        LfuMemoCache<String, Integer> cache = new LfuMemoCache<>(10);
        assertNull(cache.get("missing"));
        cache.put("key", 42);
        assertEquals(Integer.valueOf(42), cache.get("key"));
    }

    @Test
    public void evictsLeastFrequentFirst() {
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");

        // Heavily access keys 1 and 2, leave key 3 cold.
        for (int i = 0; i < 5; i++) cache.get(1);
        for (int i = 0; i < 3; i++) cache.get(2);

        cache.put(4, "d"); // should evict key 3 (frequency = 1, min)

        assertEquals("a", cache.get(1));
        assertEquals("b", cache.get(2));
        assertNull("least-frequent key must be evicted", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    public void tiesBrokenByInsertionOrder() {
        // Three keys, none accessed after insertion -> all have frequency 1.
        // LFU must pick the OLDEST-inserted one as the victim (LRU tiebreak).
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");

        cache.put(4, "d"); // key 1 is the oldest freq-1 entry
        assertNull(cache.get(1));
        assertEquals("b", cache.get(2));
        assertEquals("c", cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    public void frequencyPromotesOnHit() {
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(2);
        cache.put(1, "a");
        cache.put(2, "b");

        cache.get(1); // key 1 freq = 2
        cache.get(1); // key 1 freq = 3

        cache.put(3, "c"); // should evict key 2 (freq = 1)

        assertEquals("a", cache.get(1));
        assertNull(cache.get(2));
        assertEquals("c", cache.get(3));
    }

    @Test
    public void minFreqResetsAfterEviction() {
        // After evicting the last key at minFreq, newly admitted keys should
        // become the new minFreq (=1) -- if minFreq is not reset, the next
        // eviction picks the wrong bucket.
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(2);
        cache.put(1, "a");
        cache.get(1); // key 1 freq = 2
        cache.put(2, "b"); // key 2 freq = 1, now minFreq
        cache.put(3, "c"); // should evict key 2 (freq = 1)

        assertEquals("a", cache.get(1));
        assertNull(cache.get(2));
        assertEquals("c", cache.get(3));

        // Now key 1 freq = 3, key 3 freq = 1.
        cache.put(4, "d"); // should evict key 3, NOT key 1
        assertEquals("a", cache.get(1));
        assertNull(cache.get(3));
        assertEquals("d", cache.get(4));
    }

    @Test
    public void updatingExistingKeyPromotesFrequency() {
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(2);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(1, "a*"); // overwrite should count as a touch (freq bumps)

        cache.put(3, "c"); // should evict key 2 (still freq = 1)
        assertEquals("a*", cache.get(1));
        assertNull(cache.get(2));
    }

    @Test
    public void removeDropsEntryAndFrequency() {
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.get(1); // freq = 2
        cache.remove(1);

        assertEquals(1, cache.size());
        assertNull(cache.get(1));
        assertEquals("b", cache.get(2));
    }

    @Test
    public void putNullActsAsRemove() {
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(3);
        cache.put(1, "a");
        cache.put(1, null);
        assertEquals(0, cache.size());
        assertNull(cache.get(1));
    }

    @Test
    public void clearResetsMinFreq() {
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(3);
        cache.put(1, "a");
        cache.get(1); // freq = 2
        cache.clear();

        // After clear, admitting a new key should not crash and should
        // evict correctly.
        cache.put(2, "b");
        cache.put(3, "c");
        cache.put(4, "d");
        cache.put(5, "e"); // evicts one freq-1 key
        assertEquals(3, cache.size());
    }

    @Test
    public void statsRecordsEvictions() {
        CacheStats stats = new CacheStats();
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(2, stats);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c"); // evict
        cache.put(4, "d"); // evict
        assertEquals(2, stats.getEvictionCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroMaxSize() {
        new LfuMemoCache<>(0);
    }

    @Test
    public void staysWithinMaxSizeAfterRemovingTheLastMinFrequencyEntry() {
        // Removing the only entry at the lowest frequency leaves minFreq on an
        // empty bucket. The cache is then not full, so the next new key is added
        // without an eviction, and that put resets minFreq to 1.
        LfuMemoCache<Integer, String> cache = new LfuMemoCache<>(2);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.get(1);
        cache.get(2);       // both at frequency 2
        cache.get(1);       // key 1 at frequency 3
        cache.remove(2);    // the frequency-2 bucket is now empty
        cache.put(3, "c");  // not full: no eviction
        cache.put(4, "d");  // full: evicts key 3, the only key at frequency 1
        assertEquals(2, cache.size());
        assertNull(cache.get(3));
        assertEquals("a", cache.get(1));
        assertEquals("d", cache.get(4));
    }
}
