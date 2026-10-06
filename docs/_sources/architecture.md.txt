# Architecture

## Overview

MORAl uses a three-layer architecture that separates **validation** (compile time) from **transformation** (build time) from **execution** (runtime).

```
                        ┌─────────────────────────────────────────────┐
                        │              Developer Source                │
                        │  @Memoize / @CacheInvalidate annotations    │
                        └──────────────────┬──────────────────────────┘
                                           │
                    ┌──────────────────────┼──────────────────────┐
                    │                      │                      │
             ┌──────▼──────┐      ┌────────▼────────┐    ┌───────▼────────┐
             │  KSP Layer  │      │   ASM Layer      │    │ Runtime Layer  │
             │  (optional) │      │   (Gradle Plugin) │    │                │
             ├─────────────┤      ├──────────────────┤    ├────────────────┤
             │ Validates:  │      │ Transforms:      │    │ Executes:      │
             │ - void ret  │      │ - Adds fields    │    │ - Cache lookup │
             │ - abstract  │      │ - Patches ctors  │    │ - Key building │
             │ - maxSize   │      │ - Injects cache  │    │ - LRU eviction │
             │ - mutability│      │   check/store    │    │ - Invalidation │
             │ - inline    │      │ - Injects        │    │ - TTL expiry   │
             │ Generates:  │      │   invalidation   │    │ - Statistics   │
             │ nothing     │      │                  │    │                │
             └─────────────┘      └──────────────────┘    └────────────────┘
              Compile Time             Build Time              Runtime
```

## Module Structure

```
MORAl/
├── memoize-annotations/        # Pure Java library (no dependencies)
│   └── @Memoize, @CacheInvalidate, @Invalidation, @InvalidateCacheEntry,
│       @CacheKey, enums
│
├── memoize-runtime/            # Java library (depends on annotations)
│   └── MemoCache, LruMemoCache, UnsynchronizedLruMemoCache, FifoMemoCache,
│       LfuMemoCache, ConcurrentMemoCache, CacheKeyWrapper,
│       MemoDispatcher, MemoCacheManager, CacheStats,
│       MemoMetrics, MemoLogger, LogLevel, LogSink
│
├── memoize-ksp/                # Kotlin library (depends on annotations + KSP API)
│   └── MemoizeProcessor -- compile-time validation only
│
├── memoize-gradle-plugin/      # Gradle plugin (depends on annotations + AGP API + ASM)
│   └── MemoizePlugin, MemoizeClassVisitorFactory, MemoizeClassVisitor,
│       JvmBytecodeTransformer, MemoizeConfigurationException
│
├── memoize-test-android/       # Android app (consumes all above)
│   └── Annotated LinkedList + tests
│
├── memoize-test-jvm/           # JVM app (validates non-Android support)
│   └── Overloaded Calculator, DocumentStore, static-method classes + tests
│
└── memoize-test-kotlin-jvm/    # Kotlin JVM app (validates Kotlin forms)
    └── Kotlin classes, objects, companions, top-level functions + tests
```

### Dependency Graph

```
memoize-annotations  ◄──── memoize-runtime
        ▲                        ▲
        │                        │
        ├──── memoize-ksp        │
        │                        │
        ├──── memoize-gradle-plugin
        │                        │
        └────────────────────────┴──── memoize-test-android
                                        (also applies gradle plugin)
```

The **annotations** module has zero dependencies and can be used by any JVM project. The **runtime** module depends only on annotations. Both are shipped in the APK. The KSP and plugin modules are build-time-only.

## Design Decisions

### Why ASM Bytecode Transformation?

Three approaches were evaluated:

| Approach | Transparent? | Java+Kotlin? | Complexity | Chosen? |
|----------|-------------|--------------|------------|---------|
| **Pure KSP (wrapper classes)** | No -- requires call-site changes | Partial | Low | No |
| **Kotlin Compiler Plugin** | Yes | Kotlin only | Very high | No |
| **AGP + ASM (bytecode)** | Yes | Yes | Medium | **Yes** |

The ASM approach was chosen because:
1. **Fully transparent**: No call-site changes required.
2. **Language-agnostic**: Works on compiled `.class` files, regardless of source language.
3. **Platform-flexible**: Android via AGP Instrumentation API; JVM via post-compilation transform. Same ASM visitor logic for both. In JVM mode, the plugin skips a class that an earlier build already transformed.
4. **Runtime library handles complexity**: The ASM-injected bytecode is a thin shell that delegates to well-tested runtime classes.

### Why Two-Pass ASM?

The transformation uses the ASM **tree API** (ClassNode) for a first pass and the **visitor API** (AdviceAdapter) for a second:

- **Pass 1 (Tree API)**: Collects all annotation metadata by scanning every method. Then adds fields, patches constructors (instance caches) and patches the start of `<clinit>` (static caches). This is necessary because constructors appear before other methods in bytecode, but we need to know all memoized methods to generate the correct initialization code.

- **Pass 2 (Visitor API)**: Uses `AdviceAdapter` to inject cache-check/cache-store into method bodies. `AdviceAdapter` provides clean `onMethodEnter`/`onMethodExit` hooks that handle all edge cases (multiple return points, try-finally blocks).

### Why Inline Injection (Not Trampoline)?

The initial design used a "trampoline" pattern (rename method, generate wrapper). The implemented approach injects cache logic directly into the method body because:
- No method renaming avoids R8/ProGuard complications.
- No lambda/Callable generation avoids per-call allocation.
- Simpler bytecode: the injected code is a fixed pattern regardless of method shape.

### Why Both Bulk and Selective Invalidation?

`@CacheInvalidate` supports two modes:
- **Bulk** (no args): Clears all caches of its own kind. An instance method clears the instance caches of its object. A static method clears the static caches of its class. Correctness-first default -- safe when you're unsure which caches a mutation affects.
- **Selective** (named methods): Clears only specified caches. Performance optimization when you know exactly which queries are affected.

At build time, `@CacheInvalidate("search")` is resolved to all method keys matching that name (including all overloads). This resolution happens in the ASM visitor, not at runtime.
