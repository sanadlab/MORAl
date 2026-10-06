package io.github.sanadlab.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that should evict <b>a single entry</b> from a specific
 * {@code @Memoize} cache, rather than clearing the whole cache the way
 * {@link CacheInvalidate} does. Use it whenever a mutation only affects one
 * keyed row and you want to preserve every other cached result.
 *
 * <p>The {@link #method()} parameter names the target {@code @Memoize} method
 * (by simple name) whose cache should be evicted from. The {@link #keys()}
 * parameter lists the <b>parameter indices of the enclosing method</b> whose
 * runtime values will be boxed and used to rebuild the cache key for the
 * target. The selected parameters must match the target method's argument
 * list, in order and in boxing type.
 *
 * <p>Example:
 * <pre>
 * public class TagStore {
 *
 *     &#64;Memoize
 *     public List&lt;Tag&gt; getTags(int itemId) {
 *         // expensive lookup
 *     }
 *
 *     // When we update tags for a single item, evict just that one cache
 *     // row. Every other getTags(...) entry stays hot.
 *     &#64;InvalidateCacheEntry(method = "getTags", keys = {0})
 *     public void updateTags(int itemId, List&lt;Tag&gt; newTags) {
 *         // write new tags
 *     }
 * }
 * </pre>
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li><b>No overload disambiguation.</b> If the enclosing class contains
 *       multiple {@code @Memoize} methods with the same simple name, the
 *       transform resolves to the first one. Use {@link CacheInvalidate} for
 *       coarse-grained invalidation when overloads are involved.</li>
 *   <li><b>Type compatibility is the caller's responsibility.</b> The plugin
 *       boxes each selected parameter with its declared type; if that doesn't
 *       match the target cache's key shape, the eviction silently no-ops
 *       (the key just isn't found).</li>
 *   <li><b>One target per method.</b> This annotation is not repeatable.
 *       To evict single rows from several caches in one mutating method, use
 *       {@code @CacheInvalidate(targets = {@Invalidation(method = "a", keys = {0}), ...})}.
 *       You can put {@code @CacheInvalidate} and {@code @InvalidateCacheEntry}
 *       on the same method. Both take effect.</li>
 *   <li><b>Unknown targets fail the build.</b> If no {@code @Memoize} method of
 *       the class has the name in {@link #method()}, the build fails.</li>
 * </ul>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface InvalidateCacheEntry {

    /**
     * Simple name of the {@code @Memoize} method whose cache holds the entry
     * to be evicted.
     */
    String method();

    /**
     * Parameter indices of the enclosing method whose runtime values should
     * be used to rebuild the target cache key, in the same order as the
     * target method's arguments. An empty array means "the target takes no
     * arguments" (zero-arg memoized methods).
     */
    int[] keys() default {};
}
