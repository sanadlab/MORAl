# Runtime Library

The runtime library provides the cache infrastructure that ASM-injected bytecode calls into. All classes are in the `io.github.sanadlab.runtime` package.

Source: [`memoize-runtime/src/main/java/io/github/sanadlab/runtime/`](https://github.com/sanadlab/MORAl/tree/main/MORAl/memoize-runtime/src/main/java/io/github/sanadlab/runtime)

## Class Hierarchy

```
MemoCache<K,V>  (interface)
├── LruMemoCache<K,V>               -- LinkedHashMap (access-order) + synchronized
├── UnsynchronizedLruMemoCache<K,V>  -- LinkedHashMap (access-order), NO synchronization
├── FifoMemoCache<K,V>              -- LinkedHashMap (insertion-order) + synchronized
├── LfuMemoCache<K,V>               -- Frequency buckets (O(1) get/put/evict) + synchronized
└── ConcurrentMemoCache<K,V>         -- ConcurrentHashMap, unbounded

CacheKeyWrapper              -- Wraps Object[] as cache key
MemoDispatcher               -- Per-method cache coordinator
MemoCacheManager             -- Per-instance or per-class (static) manager (bulk + selective invalidation)
CacheStats                   -- Hit/miss/eviction counters
MemoMetrics                  -- Per-dispatcher compute/lookup timing (opt-in)
MemoLogger / LogLevel / LogSink -- Embedded observability facade
```

See [Observability](observability.md) for the logging and metrics subsystem in
full, including performance trade-offs of each log level.

## MemoCache Interface

The base cache contract. Five implementations are provided.

```java
public interface MemoCache<K, V> {
    V get(K key);           // Returns null if not present
    void put(K key, V value);
    void remove(K key);     // No-op if absent
    void clear();
    int size();
}
```

## LruMemoCache

LRU (Least Recently Used) cache backed by `LinkedHashMap` with access-order iteration.

```java
public final class LruMemoCache<K, V> implements MemoCache<K, V> {
    // Internal: access-order LinkedHashMap with removeEldestEntry override
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(capacity, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxSize;
        }
    };
}
```

**Thread safety:** All operations (`get`, `put`, `remove`, `clear`, `size`) are `synchronized`.

**Eviction:** When `put()` causes size to exceed `maxSize`, the least recently accessed entry is automatically removed. If a `CacheStats` instance is provided, evictions are counted.

**Time complexity:** O(1) for get and put. Eviction is O(1) amortized (removing the head of the linked list).

**Hot-path caveat:** every `get()` re-links the accessed node to the tail of the access-order list. This is two pointer updates and a field write per hit. For hit-heavy workloads where locality is weak, `FifoMemoCache` skips the relink entirely and is measurably cheaper on the hit path.

## UnsynchronizedLruMemoCache

LRU cache with **no synchronization**. Identical to `LruMemoCache` but without `synchronized` methods. Used when `threadSafety = ThreadSafety.NONE` and `eviction = EvictionPolicy.LRU`. With `ThreadSafety.NONE`, it also holds the TTL timestamps for every eviction policy.

```java
public final class UnsynchronizedLruMemoCache<K, V> implements MemoCache<K, V> {
    // Same LinkedHashMap + removeEldestEntry as LruMemoCache,
    // but get/put/remove/clear/size are NOT synchronized.
}
```

**When to use:** Methods that are only called from a single thread (e.g., Android main thread, a single-threaded executor). Eliminates all synchronization overhead (~20-30ns saved per cache hit vs synchronized).

**Risk:** If called from multiple threads concurrently, results are undefined (data corruption, `ConcurrentModificationException`).

## FifoMemoCache

FIFO cache backed by `LinkedHashMap` in **insertion-order** mode (`accessOrder=false`). Hits do **not** re-link the node, so the hot path is strictly cheaper than LRU.

```java
public final class FifoMemoCache<K, V> implements MemoCache<K, V> {
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(cap, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxSize;
        }
    };
}
```

**When to use:** your workload has little or no temporal locality, or you care about the tightest possible hit path. Examples: a pure deterministic function called with a uniform distribution of arguments; a memoized helper on the UI thread where every nanosecond counts.

**When NOT to use:** anything with a "hot set" of repeated keys where LRU's age-aware eviction dominates. FIFO will happily evict a key that's been hit 1000 times in favour of a one-shot newcomer.

**Thread safety:** `synchronized` methods. No unsynchronized variant is shipped.

**Time complexity:** O(1) for `get`, `put`, `remove`, and eviction.

## LfuMemoCache

Least-Frequently-Used cache with **O(1)** `get` / `put` / `evict`, using the classic frequency-bucket layout:

```
values   : HashMap<K, V>             key -> cached value
freqs    : HashMap<K, Integer>       key -> current hit count
buckets  : HashMap<Integer, LinkedHashSet<K>>    freq -> keys at that freq
minFreq  : int                       running min for O(1) eviction
```

Every `get` increments the key's frequency and moves it to the next bucket. Every `put` that overflows the cache evicts the **oldest** key in the `minFreq` bucket (`LinkedHashSet` keeps insertion order so ties are broken oldest-first, exactly like LRU does for same-age entries). Because `minFreq` is tracked as the cache mutates, the eviction victim is found without scanning.

**When to use:** skewed / Zipfian access patterns where a small subset of arguments dominates the call distribution. Tag lookups, autocomplete prefixes, route resolution, dependency-graph queries, anything that obeys a power law. LFU's hit rate on these workloads is materially better than LRU's.

**When NOT to use:**
- **Scan-heavy workloads.** A single full iteration over many keys inflates everyone's frequency to 1; the cache then behaves like FIFO with extra bookkeeping.
- **Short-lived caches with fast-changing hot sets.** LFU has no aging term, so a key that was hot in the past can pin itself in the cache even after the workload has moved on.
- **Very small caches where bookkeeping overhead dominates.** Three `HashMap`s + one `LinkedHashSet` per frequency is more memory and more cache-miss pressure than LRU/FIFO.

**Thread safety:** `synchronized` methods.

**Time complexity:** O(1) for every operation. Memory: roughly 3x the node count of LRU due to the per-frequency bucket structure.

## ConcurrentMemoCache

Lock-free cache backed by `ConcurrentHashMap`. No automatic eviction.

```java
public final class ConcurrentMemoCache<K, V> implements MemoCache<K, V> {
    private final ConcurrentHashMap<K, V> map;
}
```

**Thread safety:** Lock-free reads, bucket-level write locks (inherent to `ConcurrentHashMap`).

**Use case:** When you need unbounded caching or eviction is handled externally. `MemoDispatcher` uses it as the value cache when `eviction = EvictionPolicy.NONE`, for all `threadSafety` values. TTL timestamps always use an LRU cache.

## CacheKeyWrapper

Immutable wrapper around `Object[]` that provides correct `hashCode()` and `equals()` for use as a cache key.

```java
public final class CacheKeyWrapper {
    public static final CacheKeyWrapper EMPTY = new CacheKeyWrapper(new Object[0]);

    private final Object[] args;
    private final int hashCode;  // Pre-computed in constructor

    public CacheKeyWrapper(Object[] args) {
        this.args = args;
        this.hashCode = Arrays.deepHashCode(args);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CacheKeyWrapper)) return false;
        return Arrays.deepEquals(this.args, ((CacheKeyWrapper) obj).args);
    }
}
```

**Key design decisions:**
- **`Arrays.deepHashCode` / `Arrays.deepEquals`**: Handles primitives (boxed), objects, nested arrays, and nulls uniformly.
- **Pre-computed hash**: The hash is computed once in the constructor since the wrapper is immutable.
- **`EMPTY` singleton**: Zero-argument methods use a shared instance to avoid allocation.

## MemoDispatcher

The core class that ASM-injected bytecode calls. Each `@Memoize`-annotated method gets its own `MemoDispatcher` instance, stored as a synthetic field on the class. An instance method gets one dispatcher for each object. A static method gets one dispatcher for the class.

### Methods Called by ASM Bytecode

The ASM transformation injects calls to these methods at method entry and exit:

```java
// Called at method entry: build a key from boxed arguments
public CacheKeyWrapper buildKey(Object[] args)

// Called at method entry: check cache, returns value or null (miss)
public Object getIfCached(CacheKeyWrapper key)

// Called on a miss: System.nanoTime() when INFO logging is on, else 0
public long computeStart()

// Called before method return: record the compute time, then store the result
public Object putInCache(CacheKeyWrapper key, Object result, long computeStart)

// Called to convert NULL_SENTINEL back to null
public static Object unwrap(Object cached)
```

On a miss, the generated code stores `long __start = dispatcher.computeStart()`. Before each normal return, it calls `dispatcher.putInCache(__key, boxedResult, __start)`. When `__start` is not 0, this call records the compute time. Then it stores the result like the 2-argument `putInCache(CacheKeyWrapper, Object)`. With logging off, `computeStart()` costs one volatile read. The hit path does not call these two methods.

The 2-argument `putInCache()` stays for classes that older plugin versions transformed. The generated code calls the new methods, so use the same version for the plugin and the runtime.

The generated code boxes primitive arguments and results with `Integer.valueOf`, `Boolean.valueOf` and the other `valueOf` methods. The JDK caches `Boolean`, `Byte`, and `Short`, `Integer` and `Long` values from -128 to 127, and `Character` values from 0 to 127. These values do not allocate. Larger values, `float` and `double` still allocate.

### Null Sentinel Pattern

A critical design challenge: distinguishing "cache has no entry" (miss) from "cache has null entry" (hit). The dispatcher uses a sentinel:

```java
private static final Object NULL_SENTINEL = new Object();

// Storing: null becomes NULL_SENTINEL
Object toStore = result != null ? result : NULL_SENTINEL;
cache.put(key, toStore);

// Reading: null from cache.get() means miss; non-null means hit
Object cached = cache.get(key);
if (cached != null) {
    return cached;       // Caller calls unwrap() to convert sentinel back to null
}
return null;             // Cache miss
```

### TTL (Time-To-Live) Support

TTL is off by default (`expireAfterWrite = -1`) and implemented so it costs **nothing** at runtime until the user opts in. When enabled, each `MemoDispatcher` owns a **parallel `timestamps` cache** alongside its value cache, rather than wrapping every entry in a `{value, writeTime}` tuple. The parallel-map layout keeps the TTL-disabled hot path free of an extra allocation on hits.

#### Setup

```java
private final long expireAfterWriteMs;
private final MemoCache<CacheKeyWrapper, Long> timestamps;

if (expireAfterWriteMs > 0) {
    this.timestamps = createCache(maxSize, EvictionPolicy.LRU, threadSafety, null);
} else {
    this.timestamps = null;
}
```

Both caches use `maxSize`, except with `eviction = NONE`. Then the value cache has no limit, but the timestamp cache keeps only `maxSize` entries. An entry that loses its timestamp never expires. When TTL is off, `timestamps == null` and every TTL-related code path is guarded by a single null-check.

#### Write path

`putInCache()` stores the value in `cache`, then stamps the timestamp:

```java
if (timestamps != null) {
    timestamps.put(key, System.nanoTime());
}
```

Two things worth calling out:

1. **`nanoTime`, not `currentTimeMillis`.** Monotonic. Not affected by NTP corrections or user clock tweaks -- important on Android where wall-clock time can jump backwards.
2. **"After write" semantics, not "after access."** Reads do not refresh the timestamp. The annotation is literally `expireAfterWrite`, mirroring Guava / Caffeine naming.

#### Read path

TTL is checked **before** the main value cache lookup:

```java
if (timestamps != null) {
    Long writeTime = timestamps.get(key);
    if (writeTime != null) {
        long elapsed = System.nanoTime() - writeTime;
        if (elapsed > expireAfterWriteMs * 1_000_000L) {
            cache.remove(key);          // drop expired value
            timestamps.remove(key);     // drop its timestamp
            if (stats != null) stats.recordMiss();
            return null;                // treat as a miss
        }
    }
}
```

- **Unit conversion is inline:** `expireAfterWriteMs * 1_000_000L` turns the annotation's millisecond budget into nanoseconds so the compare matches `System.nanoTime()` units.
- **Lazy eviction.** There is no background thread sweeping expired rows. An entry only dies the next time somebody asks for it. Consequence: an expired-but-never-queried entry keeps sitting in the LRU until it's pushed out by capacity pressure. That's deliberate -- no scheduler, no timer threads, and it plays nicely with Android Doze.
- **Two-step eviction.** Both the value and its timestamp are removed via `MemoCache.remove()`.
- **It counts as a miss**, not a no-op: `stats.recordMiss()` fires so hit-rate math stays honest, and the caller falls through to recompute.

#### Invalidation

`invalidate()` clears both caches in lockstep:

```java
public void invalidate() {
    cache.clear();
    if (timestamps != null) timestamps.clear();
}
```

Auto-monitor's cache-disable path does the same thing. If we cleared only one of them, the next lookup would see ghost state.

#### Design trade-offs

| Decision | Why | Cost |
|---|---|---|
| Parallel timestamp cache vs. per-entry wrapper | Zero overhead when TTL is disabled (`timestamps == null` short-circuits) | When enabled: 2x map lookups per hit and an extra `Long` allocation per miss |
| Lazy expiry (check-on-read) vs. background sweeper | No threads, no timers, no wake locks; trivial lifecycle | Expired entries occupy slots until queried or evicted by capacity |
| `nanoTime()` vs. `currentTimeMillis()` | Monotonic -- immune to wall-clock jumps | Cannot survive process restart; memoize has no persistent cache anyway |
| Treat expiry as a cache miss | Hit-rate math stays meaningful; caller falls through to compute + store | None material |
| TTL timestamps always use `LRU` regardless of the value cache's policy | Keeps the parallel map bounded to `maxSize` | The two caches have independent eviction orders -- rare under-churn drift (see below). With `eviction = NONE`, entries beyond `maxSize` lose their timestamps and never expire. |

#### Known limitations

Worth flagging explicitly because they are subtle:

1. **Non-atomic dual-map state.** `cache.put` and `timestamps.put` are two separate calls. Under concurrent access with `ThreadSafety.CONCURRENT` a reader can briefly observe a value without its timestamp (or vice versa). The read path handles the no-timestamp case by treating it as "never expires", so a racing reader can read a stale value for one window around expiry. In practice this only matters at the exact moment of expiry.
2. **LRU-drift between the two caches.** Under heavy churn, the timestamp cache may evict key `K` while the value cache still has it (the value then looks "never expires" forever) or vice versa (value silently dropped). Rare but not provably correct.
3. **No jitter.** If 10 000 entries are written in the same millisecond during a warmup they all expire together, producing a synchronised thundering-herd recompute. No refresh-ahead or per-entry jitter is implemented.
4. **Millisecond granularity with nanosecond arithmetic.** `expireAfterWriteMs * 1_000_000L` is done on a signed long. Fine for any realistic TTL (overflow at ~292 years) but worth a comment if `expireAfterWriteNanos` is ever exposed.
5. **TTL check runs even on warm cache hits.** Two map lookups per hit when TTL is enabled (`timestamps.get` then `cache.get`) -- roughly doubles the steady-state hit cost vs. TTL-off.

Addressing (1) and (2) cleanly would mean switching to a single map of `CacheEntry{value, writeTime}`, at the cost of re-introducing allocation overhead on the TTL-off fast path. That is a deliberate trade-off, not an oversight.

### Auto-Monitor Mode

When `autoMonitor = true`, the dispatcher tracks hit/miss statistics (stats are enabled automatically, regardless of `recordStats`) and evaluates the hit rate after `monitorWindow` calls. If the hit rate is below `minHitRate`, the cache is disabled:

```java
// In putInCache(), after storing the result:
if (autoMonitor && stats != null) {
    long totalRequests = stats.getRequestCount();
    if (totalRequests >= monitorWindow) {
        double hitRate = stats.getHitRate();
        if (hitRate < minHitRate) {
            disabled = true;         // Volatile flag
            cache.clear();           // Free memory
            if (timestamps != null) timestamps.clear();
        }
    }
}
```

When disabled:
- `getIfCached()` immediately returns `null` (cache bypass)
- `putInCache()` is a no-op (nothing stored)
- Cache memory is freed

The `disabled` field is `volatile` for thread-safe reads. The dispatcher can be re-enabled programmatically:

```java
public void reenable() {
    disabled = false;
    if (stats != null) stats.reset();  // Fresh monitoring window
}
```

### Factory Methods (Used by ASM)

The ASM-generated bytecode calls `MemoDispatcher.create()` to instantiate dispatchers. Two overloads exist:

```java
// Standard (6 params): used when autoMonitor = false
MemoDispatcher.create("search_297da", 64, -1L, "LRU", "CONCURRENT", false);

// Extended (9 params): used when autoMonitor = true
MemoDispatcher.create("fib_a3b2c", 32, -1L, "LRU", "CONCURRENT", false,
                       true, 0.5, 200);
//                     autoMonitor, minHitRate, monitorWindow
```

These factories take string names for enums (avoiding complex `GETSTATIC` bytecode for enum constants) and delegate to the full constructor after parsing.

### Callable-Based API

For direct use without ASM (e.g., in tests), the dispatcher also supports a `Callable`-based API:

```java
public Object invoke(Object[] args, Callable<Object> compute) throws Exception
```

`invoke()` has its own check-compute-store code. It does not call `getIfCached()` or `putInCache()`. Thus it ignores auto-monitor. It never disables the cache, and it does not bypass a disabled cache. It records compute time in `MemoMetrics`, like the generated code. It is the only path that logs compute exceptions. The generated bytecode never calls `invoke()`.

## MemoCacheManager

Manages all `MemoDispatcher` instances for a single object, or for a class. A class can have two managers. `__memoCacheManager` holds the instance caches. The static field `__memoStaticCacheManager` holds the static caches.

The plugin registers each dispatcher by its method key, `name_xxxxx`, not by the plain method name. The suffix is a hash of the name and the descriptor, so overloads get different keys.

```java
public final class MemoCacheManager {
    private final ConcurrentHashMap<String, MemoDispatcher> dispatchers = new ConcurrentHashMap<>();

    public void register(String methodName, MemoDispatcher dispatcher) {
        dispatchers.put(methodName, dispatcher);
    }

    // Called by @CacheInvalidate (no args) -- clears ALL caches
    public void invalidateAll() {
        for (MemoDispatcher dispatcher : dispatchers.values()) {
            dispatcher.invalidate();
        }
    }

    // Called by @CacheInvalidate({"search", "length"}) -- selective invalidation.
    // The plugin passes method keys, for example "search_0a3f2".
    public void invalidate(String[] methodNames) {
        for (String name : methodNames) {
            MemoDispatcher dispatcher = dispatchers.get(name);
            if (dispatcher != null) {
                dispatcher.invalidate();
            }
        }
    }

    // Called by @InvalidateCacheEntry and by @Invalidation with keys or keyBuilder
    public void invalidateEntry(String methodKey, Object[] args) {
        MemoDispatcher dispatcher = dispatchers.get(methodKey);
        if (dispatcher != null) {
            dispatcher.invalidateEntry(args);
        }
    }
}
```

The manager supports three invalidation modes:

- **Full invalidation** (`invalidateAll()`): Called when `@CacheInvalidate` has no arguments. Clears every registered cache. Safe default when you're unsure which caches are affected.
- **Selective invalidation** (`invalidate(String[])`): Called when `@CacheInvalidate({"method1", "method2"})` specifies target method names. The plugin passes the keys of all overloads with these names. Only the named caches are cleared. Others remain intact. Unknown keys are ignored at runtime.
- **Single-entry invalidation** (`invalidateEntry(String, Object[])`): Called by `@InvalidateCacheEntry` and by `@Invalidation` with `keys` or `keyBuilder`. Removes one entry and its TTL timestamp.

The plugin checks the names at build time. A name that matches no `@Memoize` method of the class fails the build with `MemoizeConfigurationException`. Only a bare `@CacheInvalidate` calls `invalidateAll()`. A named invalidator never falls back to `invalidateAll()`.

The plugin adds a manager only when the class needs it. The instance manager exists if the class has instance `@Memoize` methods or a bare instance `@CacheInvalidate`. The static manager exists if the class has static `@Memoize` methods or a bare static `@CacheInvalidate`. So an instance method that only clears static caches does not give each instance an empty manager.

The manager also has `getDispatcher(String)`, `getDispatchers()`, `totalSize()`, `dumpReport()` and `logReport()`. The manager fields are `private synthetic`, so user code can reach them only by reflection.

**Why a manager?** When a class has multiple `@Memoize` methods (e.g., `search`, `length`, `describe`), a mutating method needs a centralized way to invalidate the right caches. The manager holds all dispatchers and routes invalidation calls to the correct subset.

## CacheStats

Thread-safe statistics tracker using `AtomicLong` counters. Active when `@Memoize(recordStats = true)` or `@Memoize(autoMonitor = true)`.

```java
public final class CacheStats {
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();

    public double getHitRate() {
        long total = hitCount.get() + missCount.get();
        return total == 0 ? 0.0 : (double) hitCount.get() / total;
    }

    // toString: "CacheStats{hits=42, misses=8, evictions=2, hitRate=84.00%}"
}
```

When `recordStats = false` (default) and `autoMonitor = false`, the `stats` field in `MemoDispatcher` is `null` and all stat-recording calls are skipped -- zero overhead.

## MemoMetrics

Per-dispatcher timing tracker that complements `CacheStats`. Every `MemoDispatcher`
owns one, but the dispatcher only populates it when `MemoLogger` is set to
`INFO`, `DEBUG` or `TRACE` -- so the cost of timing is paid only when the user explicitly
asks for observability. Stores:

- `totalComputeNanos` / `computeSamples` -- cumulative cost of misses
- `totalLookupNanos`  / `lookupSamples`  -- cumulative cost of hits
- `getMeanComputeNanos()` / `getMeanLookupNanos()` -- derived averages
- `getEstimatedSavedNanos()` -- `hits * meanCompute - totalLookup`, a rough
  "wall-clock savings" figure useful for benchmarking studies

For a memoized method, `getIfCached()` records the lookup time of each hit. The 3-argument `putInCache()` records the compute time of each miss, from the start time that `computeStart()` returned. `invoke()` also records both.

`MemoDispatcher.getMetrics()` exposes the instance; `MemoCacheManager.dumpReport()`
renders every dispatcher's stats + metrics as a multi-line report suitable for
end-of-run logging. See [Observability](observability.md).

## Embedded Logging (MemoLogger)

`MemoDispatcher`'s hot paths (`invoke`, `getIfCached`, `computeStart`, `putInCache`, `invalidate`,
the constructor) carry `MemoLogger.isLoggable(level)` guard checks before any
log-call argument is built. At the default `LogLevel.OFF` every guard
short-circuits on a volatile-read + integer compare, so there is no allocation,
no formatting, no sink dispatch -- the logging hooks are effectively free
unless the user opts in. Enabled levels produce dispatcher lifecycle events,
miss/expire/hit events, single-entry invalidations and auto-monitor disables.
Only `invoke()` logs compute exceptions, and the generated bytecode does not
call `invoke()`.

See [Observability](observability.md) for the full level table, sink wiring
(auto-detected Android Logcat via reflection), and performance implications.
