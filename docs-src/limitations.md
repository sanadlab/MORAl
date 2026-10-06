# Limitations

## Functional Limitations

### `CacheScope.CLASS` Not Implemented

`CacheScope.CLASS` is defined in the annotation API but **not implemented** in the ASM transformation. The plugin ignores the `scope` attribute. An instance method always gets a per-instance cache. Each object has its own independent cache for each memoized instance method.

**Impact:** Two `LinkedList` instances do not share cache entries, even for the same arguments. This is correct for methods that read instance state but wasteful for pure functions.

**Workaround:** Make the pure function a static method, a Kotlin top-level function or a `@JvmStatic` function. A static `@Memoize` method gets one cache for its class. See [Static Methods](annotations.md#static-methods). Another option is a singleton pattern or dependency injection to share a single instance.

### Static Method Limits

Static `@Memoize` methods are supported, with these limits:

- **No static methods in interfaces.** Interface fields must be `public static final`, so the plugin cannot add its private cache fields. The build fails with an error. Move the method to a class.
- **Static invalidators reach static caches only.** A static method has no instance. If a static `@CacheInvalidate`, `@Invalidation` or `@InvalidateCacheEntry` names an instance cache, the build fails. The build also fails if a static method uses an instance `keyBuilder`.
- **Invalidation in the same class only.** An invalidator clears caches of its own class only. Static methods often read static state of other classes. When that state changes, call an invalidator of the class that holds the cache.
- **Long-lived entries.** A static cache lives as long as its class is loaded, which is usually the full process. A stale entry stays until an invalidator clears it or the eviction policy removes it. On Android, do not cache values that hold a `Context`, because they leak for the life of the process.
- **Shared by all threads.** `ThreadSafety.NONE` on a static method is safe only if one thread calls the method. The plugin prints a warning for this case.

### No Kotlin `inline` Functions

Kotlin copies the body of an `inline` function into each caller. The Kotlin callers therefore never reach the instrumented method or its cache. The KSP processor reports an error for `@Memoize` on an `inline` function. Without KSP, the plugin cannot detect this case, and Kotlin callers silently skip the cache.

### Interface Default Methods

The plugin never adds fields to an interface. So an interface default method gets a cache only through a copy of the method in each implementing class.

- **Kotlin copies work.** Kotlin puts an annotated copy of the default method in each implementing class. The plugin memoizes that copy. This works with `-Xjvm-default=disable` (the default in Kotlin 2.0 and 2.1, tested with 2.0.21). It also works with `-jvm-default=enable` (the default in Kotlin 2.2+, tested with kotlinc 2.4.10). Each instance gets its own cache. A default `@CacheInvalidate` method clears the caches of its own instance only.
- **Overrides are not cached.** A class that overrides the method without `@Memoize` gets no cache for it. The copied invalidators in that class skip this target with no error. If an invalidator copy has no target left, the plugin does not instrument it. The plugin checks the target names on the interface itself, so a typo there still fails the build.
- **Not cached, with no error.** Kotlin 2.0 and 2.1 with `-Xjvm-default=all-compatibility` make no copies. A Java class that implements a Kotlin interface gets no copies either.
- **Build error.** Java interface default methods fail the build with `MemoizeConfigurationException`. Kotlin `-Xjvm-default=all` and `-jvm-default=no-compatibility` also fail the build.
- **Abstract methods are skipped.** An abstract method has no body to instrument. For a class that is not a Kotlin class, the plugin prints a warning. Put the annotation on the implementations.

See [Interface Default Methods](annotations.md#interface-default-methods).

### @CacheKey Field Extraction Not Implemented

The `@CacheKey("field")` annotation is defined in the API but the ASM transformation does not extract fields from parameters. All parameters are used in their entirety via `Arrays.deepHashCode`/`Arrays.deepEquals`.

**Impact:** For complex parameter objects, the entire object is used as the cache key. If two objects have different fields that aren't relevant to the computation, they'll be treated as different cache keys.

**Workaround:** Ensure parameter types have correct `hashCode()`/`equals()` implementations that consider only relevant fields. Use Kotlin `data class` types.

### No onTrimMemory Integration

The `MemoCacheManager` does not register for Android `ComponentCallbacks2.onTrimMemory()` callbacks. Caches are not automatically trimmed when the system is under memory pressure.

**Impact:** Cached data persists until explicitly invalidated or the process is killed.

**Workaround:** Call invalidation manually in your `Application.onTrimMemory()` or `Activity.onTrimMemory()`.

### No ProGuard/R8 Keep Rules Shipped

Minified release builds may strip or rename runtime classes and synthetic fields, breaking the instrumentation.

**Workaround:** Add to your `proguard-rules.pro`:

```text
-keep class io.github.sanadlab.runtime.** { *; }
-keepclassmembers class * {
    private io.github.sanadlab.runtime.MemoCacheManager __memoCacheManager;
    private static io.github.sanadlab.runtime.MemoCacheManager __memoStaticCacheManager;
    private io.github.sanadlab.runtime.MemoDispatcher __memoDispatcher_*;
}
```

## Technical Limitations

### Boxing Overhead

Every memoized call boxes primitive arguments into `Object[]` and the return value into `Object`. For `boolean search(int key)`:
- **Entry:** `Integer.valueOf(key)` + `new Object[1]` + `CacheKeyWrapper` allocation
- **Exit (miss):** `Boolean.valueOf(result)` (no allocation)

The plugin boxes with `Integer.valueOf(...)` and the other `valueOf` methods. The JDK caches all `Boolean` and `Byte` values. It also caches `Short`, `Integer` and `Long` values from -128 to 127, and `Character` values from 0 to 127. Boxing these values does not allocate. Larger values, and all `float` and `double` values, allocate a new object.

This is negligible for expensive computations but measurable for very cheap methods (< 100ns). Estimated per-call overhead: ~50-80ns for cache hit, ~80-120ns for cache miss.

### No Suspend Function Support

Kotlin `suspend` functions compile to state machines at bytecode level. The ASM injection does not handle the complex bytecode patterns generated by the Kotlin coroutine compiler. Annotating a `suspend fun` with `@Memoize` will likely produce incorrect bytecode.

### Debugging Complexity

Breakpoints in memoized methods will first hit the injected cache-check bytecode. The source code does not match the executed bytecode. In a debugger:
- The method appears to start with unfamiliar variable assignments and comparisons.
- The actual method body starts after the cache-miss branch.
- Before each return, there's injected cache-store code.

### Unit Test Coverage

With AGP 8.10.1, Android unit tests (`testDebugUnitTest`) run against the **transformed** classes. AGP runs `transformDebugClassesWithAsm` and puts its output on the unit-test runtime classpath. So unit tests check the memoization behavior, not only the source logic. `TransformAppliedTest` in `memoize-test-android` checks this by reflection. It looks for the `__memoCacheManager` and `__memoDispatcher_*` fields.

The runtime library tests (`MemoDispatcherTest`, etc.) cover the caching logic independently.

### Exception in Original Body After Cache Miss

If the original method body throws an exception after the cache check passes (cache miss), the exception propagates normally. The cache is NOT populated (no partial/error caching). The `onMethodExit(ATHROW)` handler is explicitly skipped. This is correct behavior but means transient exceptions cause repeated recomputation.

### TTL Races and LRU Drift

TTL is implemented as a parallel `timestamps` cache next to the value cache (see [Runtime Library: TTL (Time-To-Live) Support](runtime.md) for the full walkthrough). Two subtleties:

- **Non-atomic dual-map state.** `cache.put` and `timestamps.put` are two separate calls. Under `ThreadSafety.CONCURRENT`, a reader can briefly observe a value with no timestamp (treated as "never expires") or vice versa. This only matters at the exact moment of expiry.
- **Independent LRU orders.** Both maps have their own access order under churn, so in rare cases the timestamp may be evicted while the value still sits in the value cache (or vice versa). The value then looks "never expires" until it itself is evicted.

Fixing both cleanly would require a single `CacheEntry{value, writeTime}` map, at the cost of re-introducing per-entry allocation on the TTL-disabled hot path. The current trade-off favours the TTL-off case, which is the default.

### No Scan-Resistance in LRU / LFU

A single full iteration over many keys pollutes both caches. LRU loses the hot set; LFU inflates every key's frequency to 1 and then behaves like FIFO until the hot set rebuilds its frequency lead. If your workload has warmup scans, either use `@Memoize(autoMonitor = true)` to detect the degradation or manually invalidate after the scan.

## Performance Characteristics

| Metric | Value |
|--------|-------|
| Cache hit overhead, `LRU` (single `int` arg) | ~50-80 ns |
| Cache hit overhead, `FIFO` (single `int` arg) | ~35-60 ns (no access-order relink) |
| Cache hit overhead, `LFU` (single `int` arg) | ~70-110 ns (frequency bookkeeping) |
| Cache miss overhead (compute + store) | ~80-120 ns (plus computation) |
| Single-entry invalidation (`@InvalidateCacheEntry`) | ~30-50 ns (one key build + one `remove()`) |
| Cache invalidation (per dispatcher) | ~5-10 ns (`HashMap.clear()`) |
| Memory per `MemoDispatcher` | ~200 bytes base (+ `MemoMetrics` atomic counters ~64 bytes) |
| Memory per cached entry | ~80-120 bytes (key wrapper + boxed value + map node) |
| Build time impact | < 1 second for typical projects |
| DEX method count impact | No new methods per memoized method. The plugin inlines the cache code and adds one field per method. It adds a `<clinit>` only to a class that has static caches and no `<clinit>`. |
| APK size impact | ~15 KB for runtime library |
| Logging overhead at `LogLevel.OFF` (default) | 1 volatile read + 1 int compare per guarded call site; no allocation |
| Logging overhead at `LogLevel.INFO` | Adds 2 `System.nanoTime()` calls + 2 `AtomicLong` updates on every hit, and 3 `System.nanoTime()` calls + 2 `AtomicLong` updates on every miss |
| Logging overhead at `LogLevel.DEBUG` / `TRACE` | Adds a string concat + sink write per miss (DEBUG) or per hit (TRACE); not suitable for production |

## Observability-Related Limitations

### Global Log Level

`MemoLogger.setLevel(...)` flips a single process-wide `LogLevel`. You cannot
enable verbose logging for one `@Memoize` method while the rest stays silent.
If you need per-method filtering, install a custom `LogSink` and pattern-match
on the method name embedded in every log message.

### Metric Heuristic

At `INFO` level, the generated code records the lookup time of each cache hit
and the compute time of each cache miss. The compute time runs from
`computeStart()`, just after the miss, to `putInCache()`, just before the
return. If the method body throws, the generated code records no compute time
and does not log the exception.

`MemoMetrics.getEstimatedSavedNanos()` assumes the mean compute cost is
representative of what each hit would have cost. For methods whose cost varies
wildly with input size (e.g. recursive Fibonacci), treat the figure as a
directional signal, not an exact measurement. `totalComputeNanos`,
`computeSamples`, and the raw `CacheStats` counters are always available if
you want to do your own analysis.

### Reflection-Based Logcat Sink

The Android sink is bound via `Class.forName("android.util.Log")` so the
runtime JAR doesn't depend on the Android SDK. In aggressively obfuscated
builds (R8 full mode) this may break; the fix is to install a custom
`LogSink` explicitly or add a keep rule for `android.util.Log`.

### Timing Is Gated on Logging

`MemoMetrics` is only populated when `MemoLogger.isLoggable(INFO)` is true at
the moment a dispatcher is invoked. This keeps the default (logging off) path
allocation- and syscall-free, but means you cannot collect timing data *without*
also enabling at least `INFO`-level log output. Both are typically wanted
together during benchmarking, so this is deliberate rather than accidental.

## Planned Future Enhancements

- Implement `CacheScope.CLASS` (one shared cache for an instance method)
- Support static `@Memoize` methods in interfaces
- Implement `@CacheKey` field extraction
- Bundle ProGuard/R8 keep rules with the library
- `onTrimMemory` integration for memory-pressure-aware eviction
- Maven plugin support (currently Gradle-only)
- Per-dispatcher log filtering (currently global)
- Decouple `MemoMetrics` from `MemoLogger.isLoggable(INFO)` so timing can be
  collected without enabling log output
