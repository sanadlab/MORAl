package io.github.sanadlab.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a cache invalidator. When this method is called,
 * the specified memoization caches are cleared.
 *
 * <p>Two styles are supported:
 *
 * <h2>Coarse-grained (legacy) &mdash; full-flush by method name</h2>
 * Use {@link #value()} to list the {@code @Memoize} methods whose caches
 * should be wiped completely.
 * <pre>
 * // Invalidate ALL caches on the instance
 * &#64;CacheInvalidate
 * public void clearAll() { ... }
 *
 * // Invalidate only "search" and "length", but NOT "describe"
 * &#64;CacheInvalidate({"search", "length"})
 * public void insert(int data) { ... }
 * </pre>
 *
 * <h2>Fine-grained &mdash; structured per-target directives</h2>
 * Use {@link #targets()} to mix full-flush and single-entry eviction on the
 * same mutating method, and to derive keys from helper methods or arbitrary
 * parameter subsets. See {@link Invalidation} for the full semantics.
 * <pre>
 * &#64;CacheInvalidate(targets = {
 *     &#64;Invalidation(method = "getDocument",      keyBuilder = "docKey"),
 *     &#64;Invalidation(method = "getDocumentCount", allEntries = true)
 * })
 * public void addDocument(Document doc) {
 *     db.insert(doc);
 * }
 *
 * private Object docKey(Document doc) { return doc.getId(); }
 * </pre>
 *
 * <p>{@link #value()} and {@link #targets()} may be combined on the same
 * method &mdash; both sets of directives run in order (legacy full-flush
 * first, then structured targets).
 *
 * <p>An instance method can invalidate static and instance caches. A static
 * method can invalidate only static caches; the build fails if it names an
 * instance cache.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface CacheInvalidate {

    /**
     * Names of the {@code @Memoize}-annotated methods whose caches should be
     * fully flushed. A name matches every overload with that name. An empty
     * array (default) and no {@link #targets()} entries means "invalidate ALL
     * caches on the instance". On a static method, it means "invalidate ALL
     * static caches of the class".
     *
     * <p>A name that matches no {@code @Memoize} method of the class fails the
     * build. Only a bare {@code @CacheInvalidate} (no value and no targets)
     * invalidates all caches.
     */
    String[] value() default {};

    /**
     * Structured per-cache invalidation directives. See {@link Invalidation}.
     */
    Invalidation[] targets() default {};
}
