package io.github.sanadlab.testjvm;

import io.github.sanadlab.annotations.CacheInvalidate;
import io.github.sanadlab.annotations.InvalidateCacheEntry;
import io.github.sanadlab.annotations.Invalidation;
import io.github.sanadlab.annotations.Memoize;

import java.util.HashMap;
import java.util.Map;

/**
 * JVM test subject with static and instance {@code @Memoize} methods in one
 * class. All instances share the static caches. Each instance has its own
 * instance caches. Instance methods can invalidate static caches.
 */
public class PriceList {

    private static double eurRate = 1.0;
    public static int eurRateCalls;
    public static int convertCalls;

    private final Map<String, Integer> prices = new HashMap<>();
    public int priceCalls;

    // Zero-arg static method.
    @Memoize
    public static double eurRate() {
        eurRateCalls++;
        return eurRate;
    }

    @Memoize
    public static long convert(int cents) {
        convertCalls++;
        return Math.round(cents * eurRate);
    }

    @Memoize
    public int price(String item) {
        priceCalls++;
        return prices.getOrDefault(item, 0);
    }

    // Instance method that changes shared state: flushes static caches by name.
    @CacheInvalidate({"eurRate", "convert"})
    public void applyRate(double newRate) {
        eurRate = newRate;
    }

    // Instance method that evicts one entry of a static cache.
    @InvalidateCacheEntry(method = "convert", keys = {0})
    public void forgetConversion(int cents) { }

    // Instance method with an instance keyBuilder that evicts one entry of a static cache.
    @CacheInvalidate(targets = {
        @Invalidation(method = "convert", keyBuilder = "centsKey")
    })
    public void recheck(int cents) { }

    private Object centsKey(int cents) {
        return cents;
    }

    // Bare @CacheInvalidate on an instance method: flushes the caches of this
    // instance only. The static caches stay.
    @CacheInvalidate
    public void setPrice(String item, int cents) {
        prices.put(item, cents);
    }

    // Bare @CacheInvalidate on a static method: flushes the static caches only.
    // The instance caches stay.
    @CacheInvalidate
    public static void resetRate() {
        eurRate = 1.0;
        eurRateCalls = 0;
        convertCalls = 0;
    }
}
