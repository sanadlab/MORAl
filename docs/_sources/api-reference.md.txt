# API Reference

## Annotations

### io.github.sanadlab.annotations

| Annotation | Target | Description |
|-----------|--------|-------------|
| `@Memoize` | Method | Marks a method for automatic memoization |
| `@CacheInvalidate` | Method | Marks a method that clears instance or static caches (all, selective or structured) |
| `@Invalidation` | Only inside `@CacheInvalidate(targets = ...)` | One structured directive: clear one cache or evict one entry |
| `@InvalidateCacheEntry` | Method | Marks a method that evicts one entry from one cache |
| `@CacheKey` | Parameter | Specifies field to extract from parameter for cache key. Not implemented: the plugin ignores it. |

### @Memoize Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `maxSize` | `int` | 128 | Maximum cache entries before eviction. Must be positive. |
| `expireAfterWrite` | `long` | -1 | TTL in milliseconds (-1 = no expiry) |
| `scope` | `CacheScope` | `INSTANCE` | Cache lifetime scope. Not implemented: the plugin ignores it. For one cache per class, memoize a static method. |
| `eviction` | `EvictionPolicy` | `LRU` | Eviction strategy |
| `threadSafety` | `ThreadSafety` | `CONCURRENT` | Synchronization strategy. It changes the cache for `LRU` eviction only. |
| `recordStats` | `boolean` | false | Enable hit/miss/eviction tracking |
| `autoMonitor` | `boolean` | false | Disable the cache when the hit rate stays below `minHitRate`. Also turns on stats. |
| `minHitRate` | `double` | 0.3 | Hit rate threshold for auto-monitor |
| `monitorWindow` | `int` | 100 | Number of calls before auto-monitor checks the hit rate |

### @CacheInvalidate Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | `String[]` | `{}` (empty) | Method names to invalidate. A name matches all overloads. Empty, with empty `targets` = invalidate ALL caches. |
| `targets` | `Invalidation[]` | `{}` (empty) | Structured directives. They run after the `value` names. |

**Examples:**
- `@CacheInvalidate` -- invalidates all caches on the instance (on a static method: all static caches of the class)
- `@CacheInvalidate("search")` -- invalidates only the `search` method's cache
- `@CacheInvalidate({"search", "length"})` -- invalidates `search` and `length` caches
- `@CacheInvalidate(targets = {@Invalidation(method = "search", keys = {0})})` -- evicts one entry of the `search` cache

A name in `value` or in an `@Invalidation` that matches no `@Memoize` method of the class fails the build. Only a bare `@CacheInvalidate` emits `invalidateAll()`. See [Annotations API](annotations.md#structured-targets).

### @Invalidation Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `method` | `String` | (required) | Simple name of the target `@Memoize` method. The first overload is used. |
| `allEntries` | `boolean` | false | Clear every entry of the target cache |
| `keys` | `int[]` | `{}` | Parameter indices of the enclosing method that form the cache key |
| `keyBuilder` | `String` | `""` | Name of a helper method that returns the cache key |
| `keyBuilderArgs` | `int[]` | `{}` | Parameter indices to pass to the helper. Empty = the first N parameters. |

### @InvalidateCacheEntry Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `method` | `String` | (required) | Simple name of the target `@Memoize` method. The first overload is used. |
| `keys` | `int[]` | `{}` | Parameter indices of the enclosing method that form the cache key |

You can put `@InvalidateCacheEntry` and `@CacheInvalidate` on the same method. Both take effect.

### Enums

| Enum | Values |
|------|--------|
| `CacheScope` | `INSTANCE`, `CLASS` |
| `EvictionPolicy` | `LRU`, `FIFO`, `LFU`, `NONE` |
| `ThreadSafety` | `NONE`, `SYNCHRONIZED`, `CONCURRENT` |

## Runtime Classes

### io.github.sanadlab.runtime

| Class | Description |
|-------|-------------|
| `MemoCache<K,V>` | Cache interface: `get(K)`, `put(K,V)`, `remove(K)`, `clear()`, `size()` |
| `LruMemoCache<K,V>` | LRU cache (LinkedHashMap, synchronized) |
| `UnsynchronizedLruMemoCache<K,V>` | LRU cache (LinkedHashMap, NO synchronization) |
| `FifoMemoCache<K,V>` | FIFO cache (LinkedHashMap in insertion order, synchronized) |
| `LfuMemoCache<K,V>` | LFU cache (frequency buckets, synchronized) |
| `ConcurrentMemoCache<K,V>` | Lock-free cache (ConcurrentHashMap, no eviction) |
| `CacheKeyWrapper` | Wraps `Object[]` as cache key with `deepHashCode`/`deepEquals` |
| `MemoDispatcher` | Per-method cache coordinator (called by injected bytecode) |
| `MemoCacheManager` | Per-instance manager (or per-class manager, for static methods): registers dispatchers, bulk invalidation |
| `CacheStats` | Thread-safe hit/miss/eviction counters (AtomicLong) |
| `MemoMetrics` | Per-dispatcher lookup and compute timing |
| `MemoLogger` | Static logging facade |
| `LogLevel` | Log levels: `OFF`, `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE` |
| `LogSink` | Log destination interface: `write(LogLevel, String, String, Throwable)` |

The manager and dispatcher fields are `private synthetic`. User code can reach them only by reflection, for example `getDeclaredField("__memoCacheManager")`.

### MemoDispatcher Methods

| Method | Called By | Description |
|--------|----------|-------------|
| `buildKey(Object[])` | ASM entry | Wraps args in `CacheKeyWrapper` |
| `getIfCached(CacheKeyWrapper)` | ASM entry | Returns cached value or null (miss) |
| `computeStart()` | ASM entry, on a miss | Returns `System.nanoTime()` when `INFO` logging is on, else 0 |
| `putInCache(CacheKeyWrapper, Object, long)` | ASM exit | Records the compute time when the start time is not 0. Then stores the result in the cache. |
| `putInCache(CacheKeyWrapper, Object)` | Older plugin versions | Stores result in cache. Kept for classes that older plugin versions transformed. |
| `unwrap(Object)` | ASM entry | Converts `NULL_SENTINEL` back to null |
| `isNullSentinel(Object)` | User code | True if the value is a cached null |
| `create(String,int,long,String,String,boolean)` | ASM constructor or `<clinit>` | Factory: creates dispatcher from annotation params (string enums). Used when `autoMonitor = false`. |
| `create(String,int,long,String,String,boolean,boolean,double,int)` | ASM constructor or `<clinit>` | Factory with auto-monitor params. Used when `autoMonitor = true`. |
| `invalidate()` | `MemoCacheManager` | Clears this dispatcher's cache |
| `invalidateEntry(Object[])` | `MemoCacheManager` | Removes one entry and its TTL timestamp |
| `invalidateEntry(CacheKeyWrapper)` | User code | Removes one entry by a key you already built |
| `invoke(Object[], Callable)` | Direct use | Combined check-compute-store (for testing). Ignores auto-monitor. |
| `getStats()` | User code | Returns `CacheStats` or null |
| `getMetrics()` | User code | Returns `MemoMetrics` |
| `isDisabled()` | User code | True if auto-monitor disabled the cache |
| `reenable()` | User code | Turns the cache on again and resets the stats |
| `getMethodName()` | User code | Returns the method key |
| `size()` | User code | Current number of cached entries |

### MemoCacheManager Methods

The plugin registers each dispatcher by its method key, `name_xxxxx`, for example `price_ed849`. The key is not the plain method name.

| Method | Description |
|--------|-------------|
| `register(String, MemoDispatcher)` | Register a dispatcher by method key |
| `getDispatcher(String)` | Look up dispatcher by method key |
| `invalidateAll()` | Clear ALL registered dispatchers' caches |
| `invalidate(String[])` | Clear only the named dispatchers' caches (selective). Unknown keys are ignored. |
| `invalidateEntry(String, Object[])` | Remove one entry from one dispatcher. Unknown keys are ignored. |
| `getDispatchers()` | Get all registered dispatchers (Map) |
| `totalSize()` | Sum of entries across all dispatchers |
| `dumpReport()` | Multi-line report of each dispatcher with stats and metrics |
| `logReport()` | Writes `dumpReport()` through `MemoLogger` at `INFO` |

### CacheStats Methods

| Method | Description |
|--------|-------------|
| `recordHit()` | Increment hit counter |
| `recordMiss()` | Increment miss counter |
| `recordEviction()` | Increment eviction counter |
| `getHitCount()` | Total cache hits |
| `getMissCount()` | Total cache misses |
| `getEvictionCount()` | Total evictions |
| `getRequestCount()` | `hits + misses` |
| `getHitRate()` | `hits / (hits + misses)` as double |
| `reset()` | Reset all counters to 0 |

### MemoMetrics Methods

| Method | Description |
|--------|-------------|
| `getTotalLookupNanos()` / `getLookupSamples()` | Total time and count of cache hits |
| `getTotalComputeNanos()` / `getComputeSamples()` | Total time and count of computes on misses |
| `getMeanLookupNanos()` / `getMeanComputeNanos()` | Mean times |
| `getEstimatedSavedNanos()` | `hits * meanCompute - totalLookup` |
| `reset()` | Reset all counters to 0 |

### MemoLogger Methods

| Method | Description |
|--------|-------------|
| `setLevel(LogLevel)` / `getLevel()` | Set or get the global level. The default is `OFF`. |
| `isLoggable(LogLevel)` | True if the level is enabled |
| `setSink(LogSink)` | Install a sink. `null` restores the default sink. |
| `error(String, Throwable)`, `warn`, `info`, `debug`, `trace` | Write one message |

## Gradle Plugin

### Plugin ID

```
id("io.github.sanadlab")
```

### Behavior

- **Android projects**: Registers `MemoizeClassVisitorFactory` via AGP Instrumentation API. Android unit tests (`testDebugUnitTest`) run against the transformed classes. Tested with AGP 8.10.1.
- **JVM projects**: Adds a `doLast` action to every `JavaCompile` task and to the `compileKotlin` task. The action rewrites the class files in place with `JvmBytecodeTransformer`. For Kotlin, it rewrites `build/classes/kotlin/main` only. Kotlin test classes are not transformed.
- **Incremental builds**: In JVM mode, the plugin skips a class that already has `__memoCacheManager` or `__memoStaticCacheManager`. So incremental compilation does not transform a class two times.
- Scope: project classes only. On Android it skips `android.*`, `androidx.*`, `kotlin.*` and `java.*`. Both modes skip `io.github.sanadlab.runtime.*` and `io.github.sanadlab.annotations.*`. Other `io.github.sanadlab.*` classes are transformed.
- The plugin reads all `@Memoize` parameters except `scope` and passes them to `MemoDispatcher.create()`. It passes `autoMonitor`, `minHitRate` and `monitorWindow` only when `autoMonitor = true`.
- The generated code calls `MemoDispatcher.computeStart()` and the 3-argument `putInCache()`. Use the same version for the plugin and the runtime.
- The generated code boxes primitive values with `Integer.valueOf`, `Boolean.valueOf` and the other `valueOf` methods.
- Configuration errors throw `MemoizeConfigurationException`. The build fails with the error message. These are configuration errors:
  - A static invalidator that names an instance cache.
  - A name in `@CacheInvalidate(value)`, `@Invalidation(method)` or `@InvalidateCacheEntry(method)` that matches no `@Memoize` method of the class.
  - An `@Invalidation` with an empty `method`, or with both `keys` and `keyBuilder`.
  - A `keyBuilder` that names no method of the class.
  - Static methods in interfaces, Java interface default methods, and Kotlin `-Xjvm-default=all` or `-jvm-default=no-compatibility` default methods.

### Injected Synthetic Fields

For a class with N `@Memoize` methods, the plugin adds:

| Field | Type | Count |
|-------|------|-------|
| `__memoCacheManager` | `MemoCacheManager` | 1 per class with instance `@Memoize` methods or a bare instance `@CacheInvalidate` |
| `__memoStaticCacheManager` | `MemoCacheManager` | 1 per class with static `@Memoize` methods or a bare static `@CacheInvalidate` (`static`) |
| `__memoDispatcher_<name>_<hash>` | `MemoDispatcher` | 1 per `@Memoize` method (`static` for a static method) |

The `<hash>` is a 5-hex-digit hash of `name + descriptor`, ensuring overloaded methods get unique fields. All fields are `private synthetic`. The plugin sets up the static fields at the start of `<clinit>`. It sets up the instance fields at the end of each constructor, before each return. The plugin never adds fields to an interface. An invalidator that names caches uses the manager of those caches. So an instance method that only clears static caches does not add an instance manager.

## File Locations

```
memoize-annotations/src/main/java/io/github/sanadlab/annotations/
  ├── Memoize.java
  ├── CacheInvalidate.java
  ├── Invalidation.java
  ├── InvalidateCacheEntry.java
  ├── CacheKey.java
  ├── CacheScope.java
  ├── EvictionPolicy.java
  └── ThreadSafety.java

memoize-runtime/src/main/java/io/github/sanadlab/runtime/
  ├── MemoCache.java
  ├── LruMemoCache.java
  ├── UnsynchronizedLruMemoCache.java
  ├── FifoMemoCache.java
  ├── LfuMemoCache.java
  ├── ConcurrentMemoCache.java
  ├── CacheKeyWrapper.java
  ├── MemoDispatcher.java
  ├── MemoCacheManager.java
  ├── CacheStats.java
  ├── MemoMetrics.java
  ├── MemoLogger.java
  ├── LogLevel.java
  └── LogSink.java

memoize-ksp/src/main/kotlin/io/github/sanadlab/ksp/
  ├── MemoizeProcessorProvider.kt
  └── MemoizeProcessor.kt

memoize-gradle-plugin/src/main/kotlin/io/github/sanadlab/plugin/
  ├── MemoizePlugin.kt
  ├── MemoizeClassVisitorFactory.kt
  ├── MemoizeClassVisitor.kt
  ├── MemoizeConfigurationException.kt
  └── JvmBytecodeTransformer.kt

memoize-gradle-plugin/src/test/kotlin/io/github/sanadlab/plugin/
  ├── StaticMemoizeTransformTest.kt
  ├── InterfaceMethodsTest.kt
  ├── AlreadyTransformedTest.kt
  └── JavaSources.kt
```
