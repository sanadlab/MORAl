# FAQ

## General

### What does this library do?

MORAl automatically caches method return values based on their arguments. When you annotate a method with `@Memoize`, the library injects cache-check logic at build time. The first call computes and caches the result; subsequent calls with the same arguments return the cached value instantly.

### How is this different from Spring's @Cacheable?

Spring's `@Cacheable` uses AOP proxies at runtime, requires the Spring container, and only works for Spring-managed beans. MORAl uses ASM bytecode transformation at build time, has no runtime framework dependency, and works on any Android class. Spring's caching infrastructure is also too heavyweight for mobile.

### Does it work on Kotlin classes?

Yes. The ASM transformation operates on compiled `.class` files, making it language-agnostic. Both Java and Kotlin classes are supported, including Kotlin's `final`-by-default classes.

### Does it work with method overloading?

Yes. Each overload gets a unique dispatcher field via a 5-hex-digit hash of the method name + JVM descriptor:

```java
@Memoize public long compute(int x) { ... }       // → __memoDispatcher_compute_ddad9
@Memoize public long compute(int x, int y) { ... } // → __memoDispatcher_compute_df4b2
```

When using `@CacheInvalidate("compute")`, all overloads matching that name are invalidated.

### Does it work on static methods?

Yes. A static `@Memoize` method gets one cache for its class, and all callers share it. Kotlin top-level functions and `@JvmStatic` functions are static methods, so they work too. An instance method can invalidate static caches, but a static method cannot invalidate instance caches. Static methods in interfaces and Kotlin `inline` functions are not supported. See [Static Methods](annotations.md#static-methods).

### Does it work on interface default methods?

Only on Kotlin interfaces, and only in some compiler modes. Kotlin puts an annotated copy of the default method in each implementing class. The plugin memoizes that copy, so each instance gets its own cache. This works with `-Xjvm-default=disable` (the default in Kotlin 2.0 and 2.1) and with `-jvm-default=enable` (the default in Kotlin 2.2+). A class that overrides the method without `@Memoize` is not cached. The copied invalidators in that class skip this target. With `-Xjvm-default=all-compatibility` in Kotlin 2.0 and 2.1, or in a Java class that implements a Kotlin interface, the method is not cached and you get no error. Java default methods and Kotlin `-Xjvm-default=all` (`-jvm-default=no-compatibility`) fail the build. See [Interface Default Methods](annotations.md#interface-default-methods).

## Cache Behavior

### How are cache keys constructed?

Method arguments are boxed into an `Object[]` array and wrapped in a `CacheKeyWrapper`. Key equality uses `Arrays.deepHashCode()` and `Arrays.deepEquals()`, which handles primitives, nested arrays, nulls, and objects correctly.

For this to work, argument objects must have correct `hashCode()` and `equals()` implementations. Kotlin `data class` types work perfectly. Java records also work.

### What happens when a method returns null?

Null returns are cached correctly using a sentinel pattern. The library distinguishes between "no cache entry" (miss) and "cached null" (hit). A null result is computed once and returned from cache on subsequent calls.

### What happens when a @CacheInvalidate method throws?

Caches are NOT invalidated when an exception is thrown. This is intentional: if the mutation failed, the cached state may still be valid. Invalidation only happens on successful (non-throwing) method completion.

### Can I invalidate a specific method's cache instead of all?

Yes. Pass the names of the memoized methods you want to invalidate:

```java
// Invalidate only the "search" cache
@CacheInvalidate("search")
public void updateSearchIndex() { ... }

// Invalidate "search" and "length" but not "describe"
@CacheInvalidate({"search", "length"})
public void insert(int data) { ... }

// Invalidate all instance caches of this object (default when no names specified)
@CacheInvalidate
public void resetAll() { ... }
```

A bare `@CacheInvalidate` clears all caches of its own kind. On an instance method, it clears the instance caches of that object. Static caches stay. On a static method, it clears the static caches of the class.

The method names in the annotation must match the names of `@Memoize`-annotated methods on the same class. A name that matches no `@Memoize` method fails the build with `MemoizeConfigurationException`. Only a bare `@CacheInvalidate` (no `value` and no `targets`) clears all caches.

### What happens with very cheap methods?

Memoization adds overhead (~50-100ns per call for cache hit). If the method body itself takes less than ~100ns, memoization will make it slower, not faster. Only memoize methods where the computation cost significantly exceeds the cache overhead.

### Is there a cache size limit by default?

Yes. The default `maxSize` is 128 entries with LRU eviction. When the cache is full, the least recently accessed entry is evicted to make room.

## Build & Integration

### What Gradle/AGP version is required?

For Android: AGP 8.0+. For JVM: any Gradle 8.0+. Gradle must run on JDK 17+, because the plugin is compiled for Java 17. Kotlin 2.0+ for KSP validation.

### Does it work for non-Android JVM projects?

Yes. The plugin auto-detects the project type. For Android projects it uses the AGP Instrumentation API. For JVM projects it adds a `doLast` action to each `JavaCompile` task and to the `compileKotlin` task. The action transforms the `.class` files in place. It skips a class that an earlier build already transformed. Same `@Memoize` annotations, same behavior.

### Does it work with Kotlin Multiplatform?

Not yet. The plugin transforms only the output of `JavaCompile` tasks and of the task named `compileKotlin`. A KMP JVM target compiles its Kotlin code with `compileKotlinJvm`, so the plugin does not transform those classes.

### Does it affect build time?

Minimally. The ASM transformation adds < 1 second for typical projects. Only project classes are scanned (not dependencies). Classes without annotations are passed through unmodified.

### Does it work with R8/ProGuard?

It should, but ProGuard keep rules are not yet bundled with the library. You may need to add:

```text
-keep class io.github.sanadlab.runtime.** { *; }
-keepclassmembers class * {
    private io.github.sanadlab.runtime.MemoCacheManager __memoCacheManager;
    private static io.github.sanadlab.runtime.MemoCacheManager __memoStaticCacheManager;
    private io.github.sanadlab.runtime.MemoDispatcher __memoDispatcher_*;
}
```

### Can I use this with the KSP processor only (no ASM)?

Yes, but the KSP processor only validates -- it doesn't generate code or add caching behavior. Without the Gradle plugin, `@Memoize` annotations have no runtime effect. The KSP module is optional; the ASM transformation works without it.

## Debugging

### Why do I see extra code when debugging a memoized method?

The ASM transformation injects cache-check bytecode at the start of the method and cache-store bytecode before returns. When stepping through in a debugger, you'll see these injected instructions. The original method body is unchanged after the cache-check section.

### How can I verify the transformation worked?

```bash
# Build the APK
./gradlew assembleDebug

# Inspect the transformed class
javap -p -c build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs/com/example/MyClass.class
```

Look for `__memoCacheManager` and `__memoDispatcher_*` fields.

### How can I see cache hit rates?

Use `@Memoize(recordStats = true)` and access stats at runtime (requires programmatic access to the instance's dispatcher fields, which are synthetic/private).

## Thread Safety

### Is the cache thread-safe?

By default, yes. With the default LRU eviction, `ThreadSafety.CONCURRENT` and `ThreadSafety.SYNCHRONIZED` both use `LruMemoCache`, which has synchronized methods. `ThreadSafety.NONE` uses `UnsynchronizedLruMemoCache`, which provides no synchronization (use only on single-threaded access paths). FIFO and LFU caches are always synchronized. Only `EvictionPolicy.NONE` uses a `ConcurrentHashMap` (`ConcurrentMemoCache`), whatever the `threadSafety` value.

### Can cache stampede happen?

Yes, with every cache type. The lock covers only one get or one put. The method body runs outside the lock. If two threads miss the cache at the same time, both run the method body. The last put wins. The result is correct, but expensive computations run more than once.

