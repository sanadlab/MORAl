package io.github.sanadlab.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic memoization. A build-time bytecode transformation
 * will inject cache-check logic at method entry and cache-store logic before return.
 *
 * <p>The method must have a non-void return type. Arguments are used as cache keys
 * by default (via {@code Arrays.deepHashCode/deepEquals}).
 *
 * <p>Static methods (including Kotlin top-level functions and {@code @JvmStatic}
 * functions) get one cache for the class. All threads share it, and it lives as
 * long as the class. Static methods in interfaces and Kotlin {@code inline}
 * functions are not supported.
 *
 * <p>An interface cannot hold a per-instance cache. A Kotlin interface default
 * method is memoized through the annotated copy that Kotlin puts in each
 * implementing class. Java interface default methods are not supported.
 * The plugin skips abstract methods, because they have no body.
 *
 * <p>Example usage:
 * <pre>
 * &#64;Memoize(maxSize = 64)
 * public boolean search(int key) {
 *     // expensive computation
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Memoize {

    /**
     * Maximum number of entries in the cache. When exceeded, the eviction policy
     * determines which entries to remove. Default is 128.
     */
    int maxSize() default 128;

    /**
     * Time-to-live in milliseconds after a cache entry is written.
     * A value of -1 (default) means no expiry.
     */
    long expireAfterWrite() default -1;

    /**
     * Cache scope: per-instance or per-class (static).
     */
    CacheScope scope() default CacheScope.INSTANCE;

    /**
     * Eviction policy when the cache reaches maxSize.
     */
    EvictionPolicy eviction() default EvictionPolicy.LRU;

    /**
     * Thread safety strategy for cache access.
     */
    ThreadSafety threadSafety() default ThreadSafety.CONCURRENT;

    /**
     * Whether to record cache statistics (hits, misses, evictions).
     * Adds minor overhead; useful for profiling and research.
     * Automatically enabled when {@code autoMonitor} is true.
     */
    boolean recordStats() default false;

    /**
     * Enable auto-monitoring mode. When enabled, the cache tracks its hit rate
     * and automatically disables itself (bypasses caching) if the hit rate falls
     * below {@code minHitRate} after {@code monitorWindow} calls.
     *
     * <p>This prevents memoization from adding overhead to methods where caching
     * is not beneficial (e.g., low hit rates due to high argument cardinality).
     *
     * <p>Off by default. When enabled, stats are tracked automatically regardless
     * of the {@code recordStats} setting.
     */
    boolean autoMonitor() default false;

    /**
     * Minimum hit rate threshold (0.0 to 1.0) for auto-monitoring.
     * If the hit rate falls below this value after {@code monitorWindow} calls,
     * the cache is disabled. Only used when {@code autoMonitor = true}.
     *
     * <p>Default: 0.3 (30%). A hit rate below 30% means the cache overhead
     * likely exceeds the computation savings.
     */
    double minHitRate() default 0.3;

    /**
     * Number of calls after which the auto-monitor evaluates the hit rate.
     * Only used when {@code autoMonitor = true}.
     *
     * <p>Default: 100 calls. Set higher for methods with warm-up patterns.
     */
    int monitorWindow() default 100;
}
