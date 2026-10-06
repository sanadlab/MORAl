# Annotations API

All annotations are in the `io.github.sanadlab.annotations` package. They use `RetentionPolicy.CLASS` -- readable by ASM at build time but stripped from the final APK.

Source: [`memoize-annotations/src/main/java/io/github/sanadlab/annotations/`](https://github.com/sanadlab/MORAl/tree/main/MORAl/memoize-annotations/src/main/java/io/github/sanadlab/annotations)

## @Memoize

Marks a method for automatic memoization. The method must have a non-void return type.

```java
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Memoize {
    int maxSize() default 128;
    long expireAfterWrite() default -1;
    CacheScope scope() default CacheScope.INSTANCE;
    EvictionPolicy eviction() default EvictionPolicy.LRU;
    ThreadSafety threadSafety() default ThreadSafety.CONCURRENT;
    boolean recordStats() default false;
    boolean autoMonitor() default false;
    double minHitRate() default 0.3;
    int monitorWindow() default 100;
}
```

### @Memoize Parameters

`maxSize`
: Maximum number of entries in the cache. When exceeded, the eviction policy determines which entries are removed.
: **Default:** 128
: **Example:** `@Memoize(maxSize = 64)`

`expireAfterWrite`
: Time-to-live in milliseconds after a cache entry is written. `-1` disables TTL.
: **Default:** -1 (no expiry)
: **Example:** `@Memoize(expireAfterWrite = 30000)` (30 seconds)

`scope`
: Cache lifetime scope.
: - `CacheScope.INSTANCE` -- Per-object cache. Each instance has its own cache.
: - `CacheScope.CLASS` -- Static cache shared across all instances.
: **Default:** `INSTANCE`
: **Note:** `CLASS` scope is defined but not yet implemented in the ASM transformation. The plugin ignores `scope`. To get one cache for the whole class, memoize a static method. See [Static Methods](#static-methods).

`eviction`
: Eviction policy when the cache reaches `maxSize`. Pick the policy that matches the access pattern of the method you're caching -- see [Runtime Library](runtime.md) for the implementation details and hot-path cost of each.
: - `EvictionPolicy.LRU` -- Least Recently Used. Good general-purpose default when the workload has temporal locality. Hits relink the accessed entry.
: - `EvictionPolicy.FIFO` -- First-In-First-Out. Cheaper hit path than LRU (no relink on access), but ignores locality. Use when every argument is roughly equally likely or you need the tightest possible hot path.
: - `EvictionPolicy.LFU` -- Least Frequently Used. Best hit rate on skewed / Zipfian workloads where a small set of arguments dominates. Higher bookkeeping cost than LRU/FIFO; avoid on scan-heavy workloads.
: - `EvictionPolicy.NONE` -- No eviction (unbounded growth). Use with caution.
: **Default:** `LRU`

`threadSafety`
: Thread safety strategy for cache operations. It selects the cache implementation for `LRU` eviction only. It also selects the cache that holds the TTL timestamps. The bullets below describe `LRU` eviction.
: - `ThreadSafety.CONCURRENT` -- `LruMemoCache` with `synchronized` methods. Safe for multi-threaded access. Recommended default.
: - `ThreadSafety.SYNCHRONIZED` -- Same as `CONCURRENT` (synchronized `LruMemoCache`).
: - `ThreadSafety.NONE` -- `UnsynchronizedLruMemoCache` with zero synchronization overhead. Use only when the method is called from a single thread (e.g., Android main thread). Offers the best performance for single-threaded code paths.
: **Default:** `CONCURRENT`

The cache implementation selected for each combination:

| ThreadSafety | `LRU` | `FIFO` | `LFU` | `NONE` |
|-------------|-------|--------|-------|--------|
| `NONE` | `UnsynchronizedLruMemoCache` | `FifoMemoCache` | `LfuMemoCache` | `ConcurrentMemoCache` |
| `SYNCHRONIZED` | `LruMemoCache` | `FifoMemoCache` | `LfuMemoCache` | `ConcurrentMemoCache` |
| `CONCURRENT` | `LruMemoCache` | `FifoMemoCache` | `LfuMemoCache` | `ConcurrentMemoCache` |

:::{note}
`FIFO` and `LFU` are always `synchronized`; they do not ship unsynchronized variants. Use `LRU` with `ThreadSafety.NONE` if you need the fastest possible single-threaded hot path.
:::

`recordStats`
: When `true`, tracks hit/miss/eviction counts via `CacheStats`. Adds minor overhead. Automatically enabled when `autoMonitor` is `true`.
: **Default:** `false`
: **Example:** `@Memoize(recordStats = true)`

`autoMonitor`
: When `true`, enables auto-monitoring mode. The cache tracks its hit rate and **automatically disables itself** (bypasses caching entirely) if the hit rate falls below `minHitRate` after `monitorWindow` calls. This prevents memoization from adding overhead to methods where caching is not beneficial (e.g., high argument cardinality, low temporal locality).
: When disabled by auto-monitor, `getIfCached()` always returns `null` and `putInCache()` is a no-op. The cache contents are freed to reclaim memory.
: **Default:** `false`
: **Example:** `@Memoize(autoMonitor = true, minHitRate = 0.4, monitorWindow = 200)`

`minHitRate`
: Minimum hit rate threshold (0.0 to 1.0) for auto-monitoring. If the hit rate falls below this value after `monitorWindow` calls, the cache is disabled. Only used when `autoMonitor = true`.
: **Default:** 0.3 (30%)
: **Example:** `@Memoize(autoMonitor = true, minHitRate = 0.5)`

`monitorWindow`
: Number of calls after which the auto-monitor evaluates the hit rate. Only used when `autoMonitor = true`. Set higher for methods with warm-up patterns where early misses are expected.
: **Default:** 100
: **Example:** `@Memoize(autoMonitor = true, monitorWindow = 500)`

### Usage Rules

- The method **must return a value** (non-void, non-Unit). Void methods cause a compile-time error via KSP.
- The method **must not be abstract**. KSP reports a compile-time error. Without KSP, the plugin skips an abstract method. See [Interface Default Methods](#interface-default-methods).
- The method **must not be a Kotlin `inline` function**. KSP reports a compile-time error. Kotlin copies the body of an inline function into each caller, so the callers never reach the cache.
- `maxSize` **must be positive**. KSP reports a compile-time error for a value of 0 or less.
- The method can be an instance method or a static method. See [Static Methods](#static-methods).
- Method arguments are used as cache keys via `Arrays.deepHashCode`/`Arrays.deepEquals`. Arguments should have correct `hashCode()`/`equals()` implementations.
- KSP prints a warning for a parameter whose type may be mutable. It does not warn for primitive types, boxed types, `String`, `BigDecimal`, `BigInteger`, `UUID`, unsigned Kotlin types, data classes, or a parameter with `@CacheKey`.
- Null return values are cached correctly (via a sentinel pattern).

### Static Methods

`@Memoize` works on static methods. A static method gets one cache for its class. All callers and all threads share this cache. The cache lives as long as the class is loaded, which is usually the full process.

```java
public final class MathUtils {
    @Memoize(maxSize = 256)
    public static long fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }
}
```

```kotlin
@Memoize
fun slugify(title: String): String = title.lowercase().replace(" ", "-")
```

The plugin supports these source forms:

| Source | Bytecode | Where the cache is |
|--------|----------|--------------------|
| Java `static` method in a class, a nested class or an enum | Static method | Static fields of the class |
| Kotlin top-level function | Static method of the file class (`FileNameKt`) | Static fields of the file class |
| Kotlin `@JvmStatic` function in an `object` | Static method of the object class | Static fields of the object class |
| Kotlin `@JvmStatic` function in a `companion object` | Instance method of the companion, plus a static bridge in the outer class | The companion instance |
| Kotlin function in an `object` or `companion object`, without `@JvmStatic` | Instance method of the singleton | The singleton instance |

For a `@JvmStatic` companion function, Kotlin copies all annotations to the static bridge in the outer class. The plugin finds this bridge and skips it. So Kotlin callers and Java callers use the same cache, and the invalidators in the companion clear it.

Rules for static methods:

- The plugin sets up the static cache at the start of the static initializer (`<clinit>`). So a static field initializer, a `static {}` block or an enum constructor can call a memoized static method.
- Static methods in interfaces are not supported. Interface fields must be `public static final`, so the plugin cannot add its private cache fields. The build fails with an error.
- With `ThreadSafety.NONE`, the plugin prints a warning for a static method. All threads share a static cache, so use `NONE` only if one thread calls the method.
- A static method can invalidate static caches only. See [Static and Instance Caches](#static-and-instance-caches).

### Interface Default Methods

The plugin never adds fields to an interface. An interface cannot hold private cache fields.

- **Abstract methods.** The plugin skips an abstract method that has `@Memoize`, `@CacheInvalidate` or `@InvalidateCacheEntry`. For a Java class, it prints a warning. Put the annotation on the implementations.
- **Kotlin default methods.** Kotlin puts a copy of the default method, with its annotations, in each implementing class. The plugin memoizes these copies. It skips the `$DefaultImpls` classes. Each instance gets its own cache. A default `@CacheInvalidate` method clears the caches of its own instance only.
- **Overrides.** A class that overrides the method without `@Memoize` is not cached.
- **Invalidators in Kotlin copies.** A copied invalidator can name a method that the class overrides without `@Memoize`. The class then has no cache for that method, so the plugin skips this target without an error. If no target is left, the plugin drops the invalidator. The class then gets no manager and has no runtime cost.
- **Names are checked on the interface.** Every target that an interface names must be a `@Memoize` method of that interface. If not, the build fails. So a typo still fails the build.

```kotlin
interface Shape {
    val side: Int

    @Memoize
    fun area(): Int = side * side

    @CacheInvalidate
    fun resetShape() { }
}

class Square(override var side: Int) : Shape   // area() is cached for each Square
```

The result depends on the compiler and its flags:

| Source | Result |
|--------|--------|
| Kotlin 2.0 or 2.1 with `-Xjvm-default=disable` (the default) | Cached. Tested with Kotlin 2.0.21. |
| Kotlin 2.2 or later with `-jvm-default=enable` (the default) | Cached. Tested with kotlinc 2.4.10. |
| Kotlin 2.0 or 2.1 with `-Xjvm-default=all-compatibility` | Not cached, and no error. Kotlin makes no copies. |
| Java class that implements a Kotlin interface | Not cached, and no error. |
| Java interface default method | Build error (`MemoizeConfigurationException`) |
| Kotlin `-Xjvm-default=all` or `-jvm-default=no-compatibility` | Build error (`MemoizeConfigurationException`) |

## @CacheInvalidate

Marks a method as a cache invalidator. When this method executes successfully, the specified memoization caches are cleared. Supports both **full invalidation** (all caches) and **selective invalidation** (specific methods only). With `targets`, it also supports a different action for each cache. See [Structured Targets](#structured-targets).

```java
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface CacheInvalidate {
    String[] value() default {};          // Empty, with empty targets = clear ALL caches
    Invalidation[] targets() default {};  // Structured per-cache directives
}
```

### @CacheInvalidate Parameters

`value`
: Names of `@Memoize`-annotated methods whose caches should be cleared. A name matches every `@Memoize` overload with that name. A name that matches no `@Memoize` method of the class fails the build. An empty array clears all caches only when `targets` is also empty. On an instance method, it then clears the instance caches of this object. On a static method, it then clears all static caches of the class.
: **Default:** `{}` (all caches)

`targets`
: Structured directives. Each `@Invalidation` names one `@Memoize` method. It clears the whole cache of that method, or it evicts one entry. See [Structured Targets](#structured-targets).
: **Default:** `{}`

### Full Invalidation (Default)

When no method names and no `targets` are specified, ALL memoization caches on the instance are cleared:

```java
public class DataStore {
    @Memoize
    public List<Item> getItems() { /* reads from internal list */ }

    @Memoize
    public int getCount() { /* returns size of internal list */ }

    @CacheInvalidate  // Clears BOTH getItems and getCount caches
    public void reset() { /* clears everything */ }
}
```

### Selective Invalidation

When specific method names are provided, only those methods' caches are invalidated. If several `@Memoize` overloads have the same name, the plugin clears all of them. Other caches remain intact:

```java
public class DataStore {
    @Memoize
    public List<Item> getItems() { /* reads from internal list */ }

    @Memoize
    public int getCount() { /* returns size of internal list */ }

    @Memoize
    public String getStatus() { /* reads status flag, NOT affected by item changes */ }

    // Only invalidates getItems and getCount; getStatus cache is preserved
    @CacheInvalidate({"getItems", "getCount"})
    public void addItem(Item item) { /* modifies internal list */ }

    // Only invalidates getStatus; getItems and getCount caches are preserved
    @CacheInvalidate("getStatus")
    public void setStatus(String status) { /* modifies status flag */ }
}
```

This is a performance optimization: if you know a mutation only affects specific cached queries, you can avoid clearing unrelated caches. This is particularly valuable when a class has many memoized methods and mutations are frequent.

### Structured Targets

Use `targets` when one mutating method must change several caches in different ways. Each `@Invalidation` names one `@Memoize` method. It clears the whole cache of that method, or it evicts one entry. You can use `@Invalidation` only inside `targets`.

```java
@Retention(RetentionPolicy.CLASS)
@Target({})
public @interface Invalidation {
    String method();
    boolean allEntries() default false;
    int[] keys() default {};
    String keyBuilder() default "";
    int[] keyBuilderArgs() default {};
}
```

`method`
: Simple name of the target `@Memoize` method. If several overloads have this name, the plugin uses the first one.

`allEntries`
: When `true`, clears every entry of the target cache. The plugin then ignores `keys` and `keyBuilder`.
: **Default:** `false`

`keys`
: Indices of parameters of the enclosing method. The plugin boxes these values, in this order, and uses them as the cache key of the target.
: **Default:** `{}`

`keyBuilder`
: Name of a helper method in the same class. The helper returns the cache key of the target. If it returns `Object[]`, the plugin uses the array as the argument list. If it returns another type, the plugin puts the value in a one-element `Object[]`. The helper can be an instance method or a static method. It must be static when the enclosing method is static.
: **Default:** `""` (no helper)

`keyBuilderArgs`
: Indices of parameters of the enclosing method that the plugin passes to the helper, in this order.
: **Default:** `{}`. The plugin then passes the first N parameters, where N is the number of parameters of the helper.

The plugin selects one mode for each directive:

1. `allEntries = true`: clear the whole target cache.
2. `keyBuilder` is set: call the helper and evict the entry with the returned key.
3. `keys` is set: evict the entry with the key from those parameters.
4. Nothing is set: clear the whole target cache.

```java
@CacheInvalidate(targets = {
    @Invalidation(method = "getDocument",      keyBuilder = "docKey"),
    @Invalidation(method = "getDocumentCount", allEntries = true)
})
public void addDocument(Document doc) {
    db.insert(doc);
}

private Object docKey(Document doc) { return doc.getId(); }
```

Rules:

- You can use `value` and `targets` on the same method. The plugin clears the `value` caches first. Then it runs the `targets` directives in the order you declare them.
- The build fails with `MemoizeConfigurationException` in these cases:
  - `method` is empty, or it names no `@Memoize` method of the class.
  - Both `keys` and `keyBuilder` are set.
  - The class has no method with the `keyBuilder` name.
- Only a bare `@CacheInvalidate`, with no `value` and no `targets`, clears all caches. A method with `value` or `targets` never clears all caches.
- The plugin does not check the indices in `keys` against the target method. A wrong index gives a silent miss at runtime.

### When to Use Which

| Scenario | Recommendation |
|----------|---------------|
| Mutation affects all cached state | `@CacheInvalidate` (no args) |
| Mutation affects specific methods only | `@CacheInvalidate({"method1", "method2"})` |
| Mutation affects one entry in one cache and all entries in another | `@CacheInvalidate(targets = {...})` |
| Not sure which caches are affected | `@CacheInvalidate` (no args) -- safer |

### Static and Instance Caches

A class can have static caches and instance caches. The plugin sends each invalidation to the correct cache:

| Invalidator | Bare `@CacheInvalidate` | Named static cache | Named instance cache |
|-------------|-------------------------|--------------------|----------------------|
| Instance method | Clears the instance caches of `this` only | Clears the static cache | Clears the cache of `this` |
| Static method | Clears all static caches of the class | Clears the static cache | Build error |

A static method has no instance, so it cannot clear a per-instance cache. The build fails with an error that names the method. The same rules apply to the structured `targets` form and to `@InvalidateCacheEntry`. A `keyBuilder` helper must be static when the enclosing method is static.

```java
public class PriceList {
    private static double eurRate = 1.0;

    @Memoize
    public static long convert(int cents) { return Math.round(cents * eurRate); }

    @Memoize
    public int price(String item) { /* reads this instance's prices */ }

    // Instance method that clears a static cache.
    @CacheInvalidate("convert")
    public void applyRate(double newRate) { eurRate = newRate; }

    // Bare, on an instance method: clears price() for this instance only.
    // The convert() cache keeps its entries.
    @CacheInvalidate
    public void setPrice(String item, int cents) { /* ... */ }

    // Bare, on a static method: clears convert() only.
    // The instance caches keep their entries.
    @CacheInvalidate
    public static void resetRate() { eurRate = 1.0; }
}
```

### Behavior

- Invalidation happens **after** the method body executes (before the return instruction).
- If the method **throws an exception**, caches are NOT invalidated. This is intentional: if the mutation failed, the state may not have changed.
- Full invalidation calls `MemoCacheManager.invalidateAll()`.
- Selective invalidation calls `MemoCacheManager.invalidate(String[])`, which only clears the named dispatchers.
- A name that matches no `@Memoize` method of the class fails the build with `MemoizeConfigurationException`. The plugin never replaces a named invalidation with `invalidateAll()`. Only a bare `@CacheInvalidate` clears all caches of its kind.
- You can put `@InvalidateCacheEntry` on the same method. The plugin adds it as one more `keys` directive, after the `targets` directives. Both annotations take effect.
- Static caches have their own manager (`__memoStaticCacheManager`). If a name list contains static and instance targets, the plugin calls `invalidate(...)` one time on each manager.

## @InvalidateCacheEntry

Evicts **a single row** from a specific `@Memoize` cache instead of clearing the whole cache. Use this when a mutating method only affects one keyed entry and you want every other cached value to stay hot.

```java
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface InvalidateCacheEntry {
    String method();            // target @Memoize method (simple name)
    int[] keys() default {};    // indices of the enclosing method's parameters
                                // used to rebuild the target cache key
}
```

### @InvalidateCacheEntry Parameters

`method`
: Simple name of the `@Memoize` method whose cache holds the entry to evict. Must match an `@Memoize` method on the same class. If no `@Memoize` method has this name, the build fails with `MemoizeConfigurationException`.

`keys`
: Parameter indices of the **enclosing** method whose runtime values should be boxed and passed, in order, as the cache key of the target `@Memoize` method. The selected parameters must match the target method's argument list in count and in boxing type. An empty array means the target takes no arguments.
: **Default:** `{}` (zero-arg target)

### @InvalidateCacheEntry Example

```java
public class TagStore {

    @Memoize(maxSize = 1024)
    public List<Tag> getTags(int itemId) {
        // expensive lookup
    }

    // Updating tags for ONE item evicts only that row from the getTags
    // cache; every other cached itemId stays valid.
    @InvalidateCacheEntry(method = "getTags", keys = {0})
    public void updateTags(int itemId, List<Tag> newTags) {
        // persist newTags
    }

    // Compare: @CacheInvalidate({"getTags"}) here would flush ALL of
    // getTags's entries, losing 1023 perfectly-good rows for no reason.
}
```

### Behaviour

- Eviction happens **after** the method body executes (before the return instruction), exactly like `@CacheInvalidate`.
- If the method **throws an exception**, no eviction occurs -- the failed mutation may not have changed anything.
- The generated bytecode boxes the selected parameters into an `Object[]`, calls `MemoCacheManager.invalidateEntry(targetMethodKey, args)`, which in turn calls `MemoDispatcher.invalidateEntry(key)`. Both the value cache and the parallel TTL timestamp cache are cleaned up.
- The target can be a static `@Memoize` method. A static enclosing method can evict entries from static caches only. If it names an instance cache, the build fails.
- **Typed mismatch is a silent no-op.** If you accidentally hand over a parameter whose boxed shape doesn't match the target's key, the `CacheKeyWrapper.equals` check fails, no row is found, and the call quietly does nothing. There is no runtime exception.

### Limitations

- **No overload disambiguation.** If the class contains several `@Memoize` methods with the same simple name, the plugin resolves `method = "…"` to the first overload it finds. Use `@CacheInvalidate` instead when the target is overloaded.
- **Not repeatable.** Each method can carry at most one `@InvalidateCacheEntry`. To evict single rows from several caches in the same mutating method, use `@CacheInvalidate(targets = {@Invalidation(method = "a", keys = {0}), @Invalidation(method = "b", keys = {1})})`. See [Structured Targets](#structured-targets). You can also put `@InvalidateCacheEntry` and `@CacheInvalidate` on the same method. Both take effect.
- **Indices are trusted, not checked against the target's signature.** A `keys` list that picks the wrong parameters compiles fine -- you'll see a silent miss at runtime instead of an eviction.

## @CacheKey

Specifies which field of a parameter to use for cache key construction. Applied to method parameters.

```java
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface CacheKey {
    String value() default "";
}
```

### @CacheKey Example

```java
@Memoize
public Route findRoute(@CacheKey("id") Location start, @CacheKey("id") Location end) {
    // Only start.id and end.id are used as cache keys,
    // not the entire Location objects
}
```

:::{note}
`@CacheKey` field extraction is defined in the annotation API but **not yet implemented** in the ASM transformation. Currently, entire parameter objects are used as keys. See [Limitations](limitations.md).
:::

## Enums

### CacheScope

```java
public enum CacheScope {
    INSTANCE,  // Per-object cache (default)
    CLASS      // Static cache shared across instances
}
```

### EvictionPolicy

```java
public enum EvictionPolicy {
    LRU,   // Least Recently Used (default). Recency-aware.
    FIFO,  // First-In-First-Out. Cheapest hits; ignores locality.
    LFU,   // Least Frequently Used. Best for skewed workloads.
    NONE   // No eviction -- unbounded growth.
}
```

### ThreadSafety

```java
public enum ThreadSafety {
    NONE,          // No synchronization (UnsynchronizedLruMemoCache for LRU)
    SYNCHRONIZED,  // synchronized methods (LruMemoCache for LRU)
    CONCURRENT     // Same as SYNCHRONIZED (default)
}
```

`SYNCHRONIZED` and `CONCURRENT` give the same cache. Only `EvictionPolicy.NONE` uses a `ConcurrentHashMap`, for all `threadSafety` values.
