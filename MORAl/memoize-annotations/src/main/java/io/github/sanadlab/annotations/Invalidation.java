package io.github.sanadlab.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Structured invalidation directive used inside {@link CacheInvalidate#targets()}.
 * Each {@code Invalidation} names one {@code @Memoize} method and describes
 * <em>how</em> to invalidate it, combining on a single mutating method:
 * <ul>
 *   <li><b>full flush</b> of one named cache (via {@link #allEntries()}),</li>
 *   <li><b>single-entry eviction</b> by direct param indices (via {@link #keys()}),</li>
 *   <li><b>single-entry eviction</b> via a user-written key-builder method
 *       (via {@link #keyBuilder()} + {@link #keyBuilderArgs()}).</li>
 * </ul>
 *
 * <h2>Mode selection</h2>
 * The three modes are mutually exclusive. The transform picks one as follows:
 * <ol>
 *   <li>{@code allEntries = true} &rarr; flush the whole cache for {@link #method()}.</li>
 *   <li>{@code keyBuilder} set &rarr; call the named helper method, use its
 *       return value as the target's argument tuple.</li>
 *   <li>{@code keys} set &rarr; box those parameter values of the enclosing
 *       method and forward as the target's argument tuple.</li>
 *   <li>Otherwise (nothing set) &rarr; equivalent to {@code allEntries = true}.</li>
 * </ol>
 * Specifying both {@code keys} and {@code keyBuilder} fails the build. So does
 * a {@link #method()} that names no {@code @Memoize} method of the class, and a
 * {@link #keyBuilder()} that names no method of the class.
 *
 * <h2>Key-builder contract</h2>
 * The helper named by {@link #keyBuilder()} must exist in the enclosing class.
 * It may be instance or static (it must be static when the enclosing method
 * is static), and return either:
 * <ul>
 *   <li>{@code Object[]} &mdash; interpreted as the target method's argument
 *       list verbatim (boxed already), OR</li>
 *   <li>any single value (object or primitive) &mdash; wrapped as a
 *       one-element {@code Object[]} for you.</li>
 * </ul>
 * The helper may read static fields, instance fields, thread-locals, or any
 * ambient state &mdash; its body is opaque to the transform. Only the
 * arguments listed in {@link #keyBuilderArgs()} are passed from the enclosing
 * method's parameter list (in order).
 *
 * <h2>Example</h2>
 * <pre>
 * &#64;CacheInvalidate(targets = {
 *     &#64;Invalidation(method = "getDocument",      keyBuilder = "docKey"),
 *     &#64;Invalidation(method = "getDocumentCount", allEntries = true)
 * })
 * public void addDocument(Document doc) {
 *     db.insert(doc);
 * }
 *
 * private Object docKey(Document doc) {
 *     return doc.getId();    // scalar return auto-wrapped into Object[]{doc.getId()}
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({})
public @interface Invalidation {

    /**
     * Simple name of the target {@code @Memoize} method whose cache entries
     * are affected. Overloads: resolution picks the first declared method with
     * this simple name &mdash; same rule as {@link InvalidateCacheEntry}.
     */
    String method();

    /**
     * When {@code true}, evict every entry of the target cache and ignore
     * {@link #keys()} / {@link #keyBuilder()}.
     */
    boolean allEntries() default false;

    /**
     * Parameter indices of the enclosing (mutating) method whose runtime
     * values should rebuild the target cache key, in target-signature order.
     * Mutually exclusive with {@link #keyBuilder()}.
     */
    int[] keys() default {};

    /**
     * Name of a helper method in the enclosing class that produces the target
     * method's argument tuple at eviction time. See the class Javadoc for the
     * return-type contract. Mutually exclusive with {@link #keys()}.
     */
    String keyBuilder() default "";

    /**
     * Parameter indices of the enclosing method to forward to the
     * {@link #keyBuilder()} helper, in the helper's declared order.
     *
     * <p>Default is {@code {}}, which auto-forwards the first N parameters of
     * the enclosing method where N is the helper's declared arity. Specify
     * explicit indices to forward a different subset or a different order
     * (for example {@code {2, 0}} to pass the third then the first arg).
     */
    int[] keyBuilderArgs() default {};
}
