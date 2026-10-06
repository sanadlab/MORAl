# MORAl

**Annotation-based memoization library for Android and JVM**

MORAl lets you cache method results by adding a single `@Memoize` annotation. A Gradle plugin performs ASM bytecode transformation at build time to inject all cache mechanics transparently -- no call-site changes, no wrapper classes, no boilerplate.

```java
@Memoize(maxSize = 64)
public boolean search(int key) {
    // expensive computation -- automatically cached
}

@CacheInvalidate
public void insert(int data) {
    // mutating method -- automatically clears all caches
}
```

## Key Features

- **Transparent** -- No call-site changes. The method signature is unchanged.
- **Java + Kotlin** -- Works on both Java and Kotlin code (bytecode-level transformation).
- **Android + JVM** -- Supports Android projects (via AGP) and plain JVM projects (via post-compilation transform).
- **Lightweight** -- Small runtime library (14 classes). ASM injection is a thin coordination shell.
- **Configurable** -- Set the cache size, eviction policy, TTL, thread safety and stats with annotation parameters. `scope = CacheScope.CLASS` and `@CacheKey` have no effect yet.
- **Overload-safe** -- Overloaded methods get independent caches via descriptor-based hashing.
- **Static methods** -- Static methods, Kotlin top-level functions and `@JvmStatic` functions get one cache per class.
- **Selective invalidation** -- `@CacheInvalidate({"method1"})` clears specific caches, or omit args to clear all caches of the same kind (instance or static).
- **Embedded observability** -- built-in `MemoLogger` facade (OFF by default, Logcat-aware on Android) and per-dispatcher timing metrics for benchmarking real devices without pulling in a third-party logger.

## How It Works

```
Source Code        Compile Time         Build Time (AGP/JVM)    Runtime
==========        ============         ================        =======

@Memoize    --->  KSP validates  --->  ASM transforms   --->  MemoDispatcher
                  (optional,           bytecode                checks cache,
                   errors/warnings)    (adds fields,           method body runs
                                        injects cache logic)   on miss, stores result
```

```{toctree}
:maxdepth: 2
:caption: Contents

getting-started
architecture
annotations
runtime
observability
bytecode-transformation
examples
testing
faq
limitations
api-reference
```
