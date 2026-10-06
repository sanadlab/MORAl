# Testing

## Test Summary

| Module | Test Suite | Tests | Coverage |
|--------|-----------|-------|---------|
| `memoize-runtime` | `CacheKeyWrapperTest` | 9 | Key equality/hashing: empty, primitive, composite, null, array, boolean |
| `memoize-runtime` | `LruMemoCacheTest` | 7 | Get/put, LRU eviction order, clear, stats, invalid maxSize |
| `memoize-runtime` | `FifoMemoCacheTest` | 10 | Get/put, FIFO eviction order, hits do not reorder, remove, null put, clear, stats, invalid maxSize |
| `memoize-runtime` | `LfuMemoCacheTest` | 12 | Get/put, LFU eviction order, ties, frequency promotion, remove, null put, clear, stats, invalid maxSize, size bound after a removal |
| `memoize-runtime` | `MemoDispatcherTest` | 17 | Caching, miss, zero-args, null returns, FIFO and LFU policies, single-entry invalidation, full invalidation, stats, composite keys, auto-monitor, compute timing |
| `memoize-runtime` | `MemoCacheManagerTest` | 7 | Bulk invalidation, selective invalidation, unknown names, single-entry invalidation, dispatcher lookup, total size |
| `memoize-gradle-plugin` | `StaticMemoizeTransformTest` | 7 | Build errors for unsupported static cases, `ThreadSafety.NONE` warning, new `<clinit>` |
| `memoize-gradle-plugin` | `InterfaceMethodsTest` | 4 | Java interface default methods fail the build, abstract methods are skipped with a warning |
| `memoize-gradle-plugin` | `AlreadyTransformedTest` | 3 | A class that an earlier build transformed is not transformed again |
| `memoize-gradle-plugin` | `InvalidationRulesTest` | 13 | Unknown and malformed targets fail the build, both annotations on one method, no `invalidateAll()` fallback, `valueOf` boxing, compute timing, managers, Kotlin interface names and copies |
| `memoize-test-jvm` | `CalculatorTest` | 10 | Overloading, thread safety options, selective invalidation, auto-monitor |
| `memoize-test-jvm` | `DocumentStoreTest` | 13 | Structured `@CacheInvalidate(targets = ...)`: KEYS, KEY_BUILDER and FLUSH modes, their combinations, and `@CacheInvalidate` with `@InvalidateCacheEntry` on one method |
| `memoize-test-jvm` | `ComputeMetricsTest` | 2 | The generated code records compute and lookup times when INFO logging is on, and nothing when it is off |
| `memoize-test-jvm` | `StaticMathTest` | 12 | Static methods: caching, recursion, method references and reflection, static invalidation (name list, single entry, structured, bare), nested class, concurrent callers, injected fields |
| `memoize-test-jvm` | `StaticInitOrderTest` | 2 | Memoized static calls from a static field initializer, a `static {}` block and an enum constructor |
| `memoize-test-jvm` | `PriceListTest` | 8 | Static and instance caches in one class, instance invalidators on static caches, bare invalidation of each kind |
| `memoize-test-kotlin-jvm` | `StringProcessorTest` | 13 | Nullable returns, overloading, selective invalidation, `ThreadSafety.NONE`, unbounded cache, independent instances |
| `memoize-test-kotlin-jvm` | `MathServiceTest` | 6 | Auto-monitor, TTL expiry, stats, full invalidation |
| `memoize-test-kotlin-jvm` | `TopLevelFunctionsTest` | 5 | Top-level functions: caching, invalidation, call from the file-class initializer |
| `memoize-test-kotlin-jvm` | `RegistryTest` | 5 | `object` with `@JvmStatic` and plain members, invalidation across static and instance caches |
| `memoize-test-kotlin-jvm` | `TemperatureTest` | 5 | Companion `@JvmStatic` functions: bridge skipped, invalidation seen through the bridge |
| `memoize-test-kotlin-jvm` | `ShapeTest` | 10 | Interface default methods: per-instance caches in the implementing classes, per-instance invalidation, named invalidators, overrides |
| `memoize-test-android` | `LinkedListMemoTest` | 13 | Android end-to-end: correctness, caching, invalidation, independence, null handling |
| `memoize-test-android` | `TransformAppliedTest` | 1 | The Android unit tests run against the transformed classes |
| | **Total** | **194** | |

## Running Tests

### Runtime Library Tests

These test the core caching library without any ASM involvement:

```bash
cd MORAl
./gradlew :memoize-runtime:test
```

### Gradle Plugin Unit Tests

These compile small Java classes with `javac` and run the transform on them. They cover the cases that must stop the build, so they cannot live in the integration projects:

```bash
cd MORAl
./gradlew :memoize-gradle-plugin:test
```

### Android Integration Tests

These test the annotated LinkedList in the test Android app:

```bash
cd MORAl/memoize-test-android
./gradlew testDebugUnitTest
```

### JVM Integration Tests

These test overloading, thread safety, selective and structured invalidation, and static methods on plain JVM:

```bash
cd MORAl/memoize-test-jvm
./gradlew test
```

### Kotlin JVM Integration Tests

These test Kotlin-compiled classes with the bytecode transformation, including top-level functions, `object`, companion objects and interface default methods:

```bash
cd MORAl/memoize-test-kotlin-jvm
./gradlew test
```

### Full Build Verification

Confirms the ASM transformation produces valid bytecode:

```bash
# Android
cd MORAl/memoize-test-android
./gradlew assembleDebug

# JVM (Java)
cd MORAl/memoize-test-jvm
./gradlew build

# JVM (Kotlin)
cd MORAl/memoize-test-kotlin-jvm
./gradlew build
```

### Bytecode Inspection

Verify the transformation is structurally correct:

```bash
javap -p build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs/io/github/sanadlab/test/LinkedList.class
```

Expected output includes `__memoCacheManager` and `__memoDispatcher_*` fields.

## Runtime Unit Tests

### CacheKeyWrapperTest

Source: [`memoize-runtime/src/test/java/io/github/sanadlab/runtime/CacheKeyWrapperTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-runtime/src/test/java/io/github/sanadlab/runtime/CacheKeyWrapperTest.java)

Tests `CacheKeyWrapper` equality and hashing:

- `emptyKeysShouldBeEqual` -- `EMPTY` singleton equals new empty wrapper
- `singlePrimitiveKeyEquality` -- `{42}` equals `{42}`
- `differentPrimitiveKeysNotEqual` -- `{42}` not equals `{99}`
- `compositeKeyEquality` -- `{1, "hello", 3.14}` equals same
- `compositeKeyDifference` -- `{1, "hello"}` not equals `{1, "world"}`
- `nullArgsHandled` -- `{null, "test"}` equals `{null, "test"}`
- `nullVsNonNull` -- `{null}` not equals `{"test"}`
- `arrayArgs` -- `{int[]{1,2,3}}` equals `{int[]{1,2,3}}` (deep equality)
- `booleanKey` -- `{true}` equals `{true}`, not equals `{false}`

### LruMemoCacheTest

Source: [`memoize-runtime/src/test/java/io/github/sanadlab/runtime/LruMemoCacheTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-runtime/src/test/java/io/github/sanadlab/runtime/LruMemoCacheTest.java)

- `basicGetPut` -- Put and retrieve a value
- `evictsWhenOverMaxSize` -- Adding 4th entry to size-3 cache evicts the oldest
- `lruOrderRespected` -- Accessing an entry makes it "recent", protecting it from eviction
- `clearRemovesAll` -- `clear()` empties the cache
- `statsTracksEvictions` -- `CacheStats` eviction counter increments on eviction
- `rejectsZeroMaxSize` -- Constructor throws `IllegalArgumentException` for `maxSize = 0`
- `rejectsNegativeMaxSize` -- Same for negative values

### FifoMemoCacheTest

Source: [`memoize-runtime/src/test/java/io/github/sanadlab/runtime/FifoMemoCacheTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-runtime/src/test/java/io/github/sanadlab/runtime/FifoMemoCacheTest.java)

- `basicGetPut` -- Put and retrieve a value
- `evictsOldestInsertionWhenFull` -- The first entry put in is the first entry evicted
- `hitsDoNotReorderEviction` -- A `get` does not protect an entry from eviction
- `putExistingKeyDoesNotChangeInsertionOrder` -- Updating a key keeps its place in the queue
- `removeDropsEntry` -- `remove()` deletes one entry
- `putNullActsAsRemove` -- Putting `null` removes the entry
- `clearRemovesAll` -- `clear()` empties the cache
- `statsRecordsEvictions` -- `CacheStats` counts evictions
- `rejectsZeroMaxSize` -- Constructor throws `IllegalArgumentException` for `maxSize = 0`
- `rejectsNegativeMaxSize` -- Same for negative values

### LfuMemoCacheTest

Source: [`memoize-runtime/src/test/java/io/github/sanadlab/runtime/LfuMemoCacheTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-runtime/src/test/java/io/github/sanadlab/runtime/LfuMemoCacheTest.java)

- `basicGetPut` -- Put and retrieve a value
- `evictsLeastFrequentFirst` -- The entry with the fewest hits is evicted first
- `tiesBrokenByInsertionOrder` -- With equal counts, the oldest entry is evicted
- `frequencyPromotesOnHit` -- A hit raises the frequency of an entry
- `minFreqResetsAfterEviction` -- The minimum frequency is correct after an eviction
- `updatingExistingKeyPromotesFrequency` -- Putting an existing key also raises its frequency
- `removeDropsEntryAndFrequency` -- `remove()` deletes the entry and its frequency record
- `putNullActsAsRemove` -- Putting `null` removes the entry
- `clearResetsMinFreq` -- `clear()` empties the cache and resets the minimum frequency
- `statsRecordsEvictions` -- `CacheStats` counts evictions
- `rejectsZeroMaxSize` -- Constructor throws `IllegalArgumentException` for `maxSize = 0`
- `staysWithinMaxSizeAfterRemovingTheLastMinFrequencyEntry` -- After the only entry at the lowest frequency is removed, the cache still evicts correctly and stays within `maxSize`

### MemoDispatcherTest

Source: [`memoize-runtime/src/test/java/io/github/sanadlab/runtime/MemoDispatcherTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-runtime/src/test/java/io/github/sanadlab/runtime/MemoDispatcherTest.java)

- `cachesResultOnSecondCall` -- Second call with same args returns cached value (compute called once)
- `differentArgsMissCache` -- Different args trigger recomputation
- `zeroArgMethodCached` -- Empty args use `EMPTY` key, caches correctly
- `nullReturnValueCached` -- `null` result is cached (not recomputed on second call)
- `fifoPolicyEvictsOldestInsertion` -- `EvictionPolicy.FIFO` evicts the oldest entry
- `lfuPolicyEvictsLeastFrequent` -- `EvictionPolicy.LFU` evicts the least used entry
- `invalidateEntryDropsSingleKey` -- `invalidateEntry` removes one key and keeps the others
- `invalidateEntryUnknownKeyIsNoOp` -- `invalidateEntry` with an unknown key changes nothing
- `invalidateClearsCache` -- After `invalidate()`, same args trigger recomputation
- `statsRecordHitsAndMisses` -- Hit/miss counts are accurate, hit rate calculated correctly
- `autoMonitorDisablesCacheOnLowHitRate` -- 10 unique calls → 0% hit rate → cache disabled
- `autoMonitorKeepsCacheOnHighHitRate` -- 2 unique + 8 repeated → 80% hit rate → cache stays enabled
- `autoMonitorReenableResetsStats` -- After `reenable()`, stats reset and cache is active again
- `autoMonitorBypassesCacheWhenDisabled` -- Disabled cache always returns null, doesn't store
- `multipleArgsCompositeKey` -- `{1, "a", 3.14}` and `{1, "b", 3.14}` are different keys
- `computeStartIsZeroWhenLoggingIsOff` -- With logging off, `computeStart()` returns 0 and `putInCache(key, value, 0)` records no compute sample
- `putInCacheRecordsComputeTimeWhenTimingIsOn` -- At INFO level, `putInCache(key, value, start)` records one compute sample

### MemoCacheManagerTest

Source: [`memoize-runtime/src/test/java/io/github/sanadlab/runtime/MemoCacheManagerTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-runtime/src/test/java/io/github/sanadlab/runtime/MemoCacheManagerTest.java)

- `invalidateAllClearsAllDispatchers` -- Two dispatchers registered; `invalidateAll()` clears both
- `getDispatcherReturnsRegistered` -- Lookup by registration key works; unknown key returns null
- `selectiveInvalidateClearsOnlySpecifiedDispatchers` -- 3 dispatchers; invalidate 2, verify 3rd is untouched
- `selectiveInvalidateIgnoresUnknownNames` -- Non-existent names don't affect existing caches
- `invalidateEntryDropsSingleRowOnNamedDispatcher` -- `invalidateEntry(key, args)` removes one row of one dispatcher
- `invalidateEntryUnknownMethodIsNoOp` -- `invalidateEntry` with an unknown key changes nothing
- `totalSizeAcrossAllDispatchers` -- Sum of entries across all dispatchers

## Plugin Unit Tests

Each test compiles a small Java class with `javac` and gives its bytes to `JvmBytecodeTransformer`.

### StaticMemoizeTransformTest

Source: [`memoize-gradle-plugin/src/test/kotlin/io/github/sanadlab/plugin/StaticMemoizeTransformTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-gradle-plugin/src/test/kotlin/io/github/sanadlab/plugin/StaticMemoizeTransformTest.kt)

- `classWithoutStaticInitializerGetsOne` -- The plugin creates `<clinit>` and only static `__memo*` fields
- `staticMemoizeInInterfaceFails` -- Static `@Memoize` in an interface throws `MemoizeConfigurationException`
- `staticLegacyInvalidatorOnInstanceCacheFails` -- Static `@CacheInvalidate("get")` on an instance cache fails
- `staticStructuredInvalidatorOnInstanceCacheFails` -- Static `@Invalidation` on an instance cache fails
- `staticInvalidateCacheEntryOnInstanceCacheFails` -- Static `@InvalidateCacheEntry` on an instance cache fails
- `staticInvalidatorWithInstanceKeyBuilderFails` -- Static invalidator with an instance `keyBuilder` fails
- `staticMethodWithThreadSafetyNoneWarns` -- Static method with `ThreadSafety.NONE` prints a warning

### InterfaceMethodsTest

Source: [`memoize-gradle-plugin/src/test/kotlin/io/github/sanadlab/plugin/InterfaceMethodsTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-gradle-plugin/src/test/kotlin/io/github/sanadlab/plugin/InterfaceMethodsTest.kt)

- `javaDefaultMethodFails` -- `@Memoize` on a Java interface default method throws `MemoizeConfigurationException`
- `javaDefaultInvalidatorFails` -- `@CacheInvalidate` on a Java interface default method fails the same way
- `abstractInterfaceMethodIsSkippedWithWarning` -- An abstract interface method gets no fields and a warning
- `abstractClassMethodIsSkippedButConcreteMethodIsInstrumented` -- In an abstract class, the abstract method is skipped and the concrete method gets its cache

### AlreadyTransformedTest

Source: [`memoize-gradle-plugin/src/test/kotlin/io/github/sanadlab/plugin/AlreadyTransformedTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-gradle-plugin/src/test/kotlin/io/github/sanadlab/plugin/AlreadyTransformedTest.kt)

In JVM mode, the plugin transforms every class file in the output directory after each compile. With incremental compilation, some of these classes were transformed by an earlier build. Each test transforms a class two times and expects the second call to return `null`:

- `instanceClassIsTransformedOnlyOnce` -- Class with instance caches
- `staticClassIsTransformedOnlyOnce` -- Class with static caches
- `classWithOnlyInvalidatorsIsTransformedOnlyOnce` -- Class with only `@CacheInvalidate`

### InvalidationRulesTest

Source: [`memoize-gradle-plugin/src/test/kotlin/io/github/sanadlab/plugin/InvalidationRulesTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-gradle-plugin/src/test/kotlin/io/github/sanadlab/plugin/InvalidationRulesTest.kt)

Tests how the transform resolves invalidation targets, and the shape of the generated code. The Kotlin cases use class files that the test generates with ASM:

- `unknownNameInValueFails` -- A typo in `@CacheInvalidate("...")` fails the build
- `unknownStructuredTargetFails` -- A typo in `@Invalidation(method = ...)` fails the build
- `unknownEntryTargetFails` -- A typo in `@InvalidateCacheEntry(method = ...)` fails the build
- `keysAndKeyBuilderTogetherFail` -- `keys` and `keyBuilder` in one `@Invalidation` fail the build
- `missingKeyBuilderFails` -- A `keyBuilder` that names no method fails the build
- `bothAnnotationsOnOneMethodTakeEffect` -- `@CacheInvalidate` and `@InvalidateCacheEntry` on one method both emit their calls
- `namedInvalidatorNeverFallsBackToInvalidateAll` -- A named invalidator never calls `invalidateAll()`
- `boxingUsesValueOf` -- The generated code boxes with `valueOf` and has no `new Integer` or `new Boolean`
- `memoizedMethodTimesTheCompute` -- The generated code calls `computeStart()` and the three-argument `putInCache`
- `instanceInvalidatorOfStaticCacheAddsNoInstanceManager` -- An instance method that clears only static caches adds no instance manager
- `kotlinInterfaceWithValidNamesIsLeftWithoutFields` -- A Kotlin interface with correct names gets no fields
- `typoInKotlinInterfaceFails` -- A typo in the names of a Kotlin interface fails the build
- `kotlinInterfaceCopyWithOverriddenTargetIsSkipped` -- Kotlin's copy of an invalidator whose target is not memoized in the class is dropped, with no manager and no `invalidateAll()`

## Integration Tests

### LinkedListMemoTest (Android)

Source: [`memoize-test-android/src/test/java/io/github/sanadlab/test/LinkedListMemoTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-android/src/test/java/io/github/sanadlab/test/LinkedListMemoTest.java)

End-to-end tests using the annotated `LinkedList`:

- `searchReturnsCorrectResults` -- Basic search correctness
- `lengthReturnsCorrectResults` -- Length accuracy after insertions
- `searchCachesResult` -- Second identical search returns same result
- `cacheInvalidatedOnInsert` -- `insert()` clears length cache
- `cacheInvalidatedOnDelete` -- `delete()` clears both search and length caches
- `cacheInvalidatedOnInsertAtHead` -- `insertAtHead()` clears caches
- `differentInstancesHaveIndependentCaches` -- Two `LinkedList` instances don't share caches
- `multipleMemoizedMethodsWorkIndependently` -- `search` and `length` caches are independent
- `describeReturnsCorrectResults` -- Object return type works
- `describeNullResultIsCached` -- Null return from `describe(99)` is cached
- `describeInvalidatedOnInsert` -- Cache of null result cleared after insert
- `searchFalseIsCached` -- `false` (int 0 at bytecode level) is cached correctly
- `emptyListOperations` -- Empty list returns correct defaults

### TransformAppliedTest (Android)

Source: [`memoize-test-android/src/test/java/io/github/sanadlab/test/TransformAppliedTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-android/src/test/java/io/github/sanadlab/test/TransformAppliedTest.java)

- `unitTestsSeeTheTransformedClass` -- `LinkedList` has the synthetic `__memoCacheManager` field and at least one dispatcher field, so the unit tests run against the transformed class

### CalculatorTest (JVM)

Source: [`memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/CalculatorTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/CalculatorTest.java)

JVM-specific tests using a `Calculator` class with overloaded methods and thread safety options:

- `basicMemoization` -- Basic cache behavior on JVM
- `overloadedMethodsHaveIndependentCaches` -- `compute(int)` and `compute(int,int)` use separate caches
- `overloadedMethodsCacheIndependently` -- Calling one overload doesn't interfere with the other
- `selectiveInvalidationClearsTargets` -- `@CacheInvalidate({"compute", "format"})` clears those but not `fastCompute`
- `fullInvalidationClearsAllCaches` -- `@CacheInvalidate` (no args) clears everything
- `nonThreadSafeMemoWorks` -- `ThreadSafety.NONE` produces correct results (uses `UnsynchronizedLruMemoCache`)
- `formatMemoization` -- String return type caching (same object reference on cache hit)
- `nullSafeFormatVariants` -- Edge case with zero arguments
- `autoMonitorDisablesOnLowHitRate` -- 10 unique calls disable auto-monitored cache; recomputation verified
- `autoMonitorKeepsCacheOnHighHitRate` -- High hit rate keeps cache active; cached values returned

### DocumentStoreTest (JVM)

Source: [`memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/DocumentStoreTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/DocumentStoreTest.java)

Tests for the structured `@CacheInvalidate(targets = { @Invalidation(...) })` form. Each test fills the caches, calls a mutator, and then uses call counters to tell a hit from a miss:

- `updateEvictsOnlyTheUpdatedIdFromGetDocument` -- KEYS mode evicts only the updated id
- `updateDoesNotTouchUnrelatedCaches` -- KEYS mode keeps the other caches
- `addDocumentEvictsBuilderKeyedEntryAndFlushesCount` -- A key builder that returns a single value evicts one entry, and FLUSH clears the count cache
- `addDocumentLeavesOtherGetDocumentEntriesAlone` -- The key builder evicts only its own entry
- `objectArrayReturningBuilderPassesThroughDirectly` -- A key builder that returns `Object[]` gives the argument list directly
- `staticKeyBuilderEvictsSpecificEntry` -- A static key builder evicts one entry
- `legacyAndStructuredCoexist` -- `value` and `targets` on the same method both run
- `keyBuilderReadsAmbientInstanceState` -- A key builder can read instance fields
- `keyBuilderReflectsChangedAmbientState` -- The key builder sees the current value of those fields
- `multiArgKeyBuilderAutoForwardsFirstNEnclosingParams` -- With no `keyBuilderArgs`, the first N parameters go to the key builder
- `explicitKeyBuilderArgsReorderEnclosingParams` -- `keyBuilderArgs` can reorder the parameters
- `allThreeModesFireIndependently` -- FLUSH, KEYS and KEY_BUILDER on one method each do their own work
- `cacheInvalidateAndInvalidateCacheEntryBothRun` -- `@CacheInvalidate("getDocumentCount")` and `@InvalidateCacheEntry` on one method: the count cache is flushed and one document entry is evicted

### ComputeMetricsTest (JVM)

Source: [`memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/ComputeMetricsTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/ComputeMetricsTest.java)

Tests the timing that the generated code records in `MemoMetrics`, read through the private manager field of `Calculator`:

- `timingOnRecordsComputeAndLookup` -- At INFO level, one miss gives one compute sample and one hit gives one lookup sample
- `timingOffRecordsNothing` -- With logging off, no samples are recorded

### StaticMathTest (JVM)

Source: [`memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/StaticMathTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/StaticMathTest.java)

Tests for a class whose caches and invalidators are all static. Each test starts with `StaticMath.reset()`, because a static cache lives as long as its class:

- `staticMethodIsCached` -- Second call with the same argument is a hit
- `differentArgsMiss` -- Different arguments run the body again
- `multiArgKey` -- `label(String, int)` uses both arguments as the key
- `recursiveCallsUseTheCache` -- `fib(50)` runs the body 51 times
- `methodReferenceAndReflectionUseTheCache` -- `StaticMath::square` and `Method.invoke` hit the cache
- `legacyStaticInvalidationFlushesOnlyTheNamedCache` -- Static `@CacheInvalidate("square")` keeps the `fib` cache
- `staticInvalidateCacheEntryEvictsOneEntry` -- Static `@InvalidateCacheEntry` evicts one row
- `staticStructuredInvalidation` -- Static `keyBuilder` evicts one row, `allEntries` flushes `fib`
- `bareStaticInvalidationFlushesAllStaticCachesOfTheClass` -- Bare static `@CacheInvalidate` clears every static cache of the class, but not the cache of the nested class
- `staticNestedClassIsCached` -- Static method in a static nested class
- `concurrentCallersGetCorrectValues` -- 8 threads share one static cache and get correct results
- `transformAddsOnlyStaticFields` -- Static manager and 3 static dispatchers, no instance manager

### StaticInitOrderTest (JVM)

Source: [`memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/StaticInitOrderTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/StaticInitOrderTest.java)

Tests for memoized static calls while the class initializes. If the static setup were not at the start of `<clinit>`, these tests would fail with `ExceptionInInitializerError`:

- `staticInitializerCallsAreCached` -- A static field initializer and a `static {}` block call `doubled(21)`. The second call is a hit.
- `enumConstructorCallsAreCached` -- Enum constants call `gravityFor(size)` in their constructor. Two constants with the same size share one entry.

### PriceListTest (JVM)

Source: [`memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/PriceListTest.java`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-jvm/src/test/java/io/github/sanadlab/testjvm/PriceListTest.java)

Tests for a class with static and instance `@Memoize` methods:

- `instancesHaveOwnCachesAndShareStaticCaches` -- Each instance computes `price` one time, and the static `convert` cache is shared
- `zeroArgStaticMethodIsCached` -- Static method with no arguments
- `instanceInvalidatorFlushesNamedStaticCaches` -- Instance `@CacheInvalidate({"eurRate", "convert"})` clears both static caches
- `instanceInvalidateCacheEntryEvictsOneStaticEntry` -- Instance `@InvalidateCacheEntry` evicts one row of a static cache
- `instanceKeyBuilderEvictsOneStaticEntry` -- Instance `keyBuilder` evicts one row of a static cache
- `bareInstanceInvalidatorKeepsStaticCaches` -- Bare instance `@CacheInvalidate` clears instance caches only
- `bareStaticInvalidatorKeepsInstanceCaches` -- Bare static `@CacheInvalidate` clears static caches only
- `classHasStaticAndInstanceManagers` -- Both managers exist, with the correct `static` modifier

### StringProcessorTest (Kotlin JVM)

Source: [`memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/StringProcessorTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/StringProcessorTest.kt)

Kotlin-specific integration tests using `StringProcessor` with Kotlin features:

- `basicMemoization` -- String reverse with prefix, same object reference on cache hit
- `differentArgsMiss` -- Different inputs produce different cached results
- `nullableReturnCached` -- Kotlin `Int?` return type: null cached correctly
- `nullableReturnNonNull` -- Kotlin `Int?` return type: non-null cached correctly
- `wordCountMemoized` -- Int return type caching from Kotlin
- `overloadedTransformSingleArg` -- Single-arg `transform` cached independently
- `overloadedTransformTwoArgs` -- Two-arg `transform` cached independently
- `overloadsHaveIndependentCaches` -- Overloaded methods don't share caches
- `selectiveInvalidation` -- `@CacheInvalidate("reverse", "transform")` clears targets but not `wordCount`
- `fullInvalidation` -- `@CacheInvalidate` clears all caches
- `threadSafetyNoneWorks` -- `ThreadSafety.NONE` with Kotlin-compiled classes
- `unboundedCacheWorks` -- `EvictionPolicy.NONE` produces `ConcurrentMemoCache`
- `independentInstances` -- Different instances have isolated caches

### MathServiceTest (Kotlin JVM)

Source: [`memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/MathServiceTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/MathServiceTest.kt)

Kotlin tests for auto-monitor, TTL, and stats:

- `autoMonitorDisablesOnLowHitRate` -- 10 unique fib calls → cache disabled → recomputation with changed multiplier
- `autoMonitorKeepsCacheOnHighHitRate` -- 1 miss + 9 hits → cache stays active → cached value returned
- `ttlExpiresCachedEntry` -- Entry expires after TTL, recomputation returns updated value
- `ttlReturnsCachedBeforeExpiry` -- Entry returned from cache before TTL expires
- `statsTrackedWithRecordStats` -- `recordStats = true` doesn't break caching behavior
- `fullInvalidationClearsAll` -- `@CacheInvalidate` clears all caches across different configurations

### TopLevelFunctionsTest (Kotlin JVM)

Source: [`memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/TopLevelFunctionsTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/TopLevelFunctionsTest.kt)

Tests for Kotlin top-level functions, which compile to static methods of `TopLevelFunctionsKt`:

- `staticInitializerCanCallMemoizedFunction` -- A top-level `val` calls `slugify` while the file class initializes
- `topLevelFunctionIsCached` -- Second call is a hit
- `namedInvalidationFlushesCache` -- `@CacheInvalidate("slugify")` on a top-level function
- `bareInvalidationFlushesCache` -- Bare `@CacheInvalidate` on a top-level function
- `fileClassGetsStaticManager` -- The file class has a static `__memoStaticCacheManager`

### RegistryTest (Kotlin JVM)

Source: [`memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/RegistryTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/RegistryTest.kt)

Tests for a Kotlin `object` with a `@JvmStatic` function (static cache) and a plain member (instance cache of the singleton):

- `jvmStaticFunctionInObjectIsStaticAndCached` -- `lookup` is a static method and is cached
- `plainObjectMemberIsCached` -- `describe` is cached on the singleton
- `instanceInvalidatorFlushesStaticCache` -- Plain member `rename` clears the static `lookup` cache
- `staticInvalidateCacheEntryEvictsOneEntry` -- `@JvmStatic @InvalidateCacheEntry` evicts one row
- `bareStaticInvalidatorKeepsInstanceCaches` -- Bare `@JvmStatic @CacheInvalidate` keeps the `describe` cache

### TemperatureTest (Kotlin JVM)

Source: [`memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/TemperatureTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/TemperatureTest.kt)

Tests for `@JvmStatic` functions in a companion object. The tests call the static bridge through reflection, as Java callers do:

- `jvmStaticCompanionFunctionIsCached` -- Kotlin calls are cached
- `bridgeAndCompanionShareOneCache` -- Bridge calls and Kotlin calls run the body one time in total
- `companionInvalidationIsSeenThroughTheBridge` -- After the companion `setOffset` clears the cache, the bridge returns the new value
- `bareJvmStaticInvalidatorFlushesCompanionCache` -- Bare `@JvmStatic @CacheInvalidate` clears the companion cache
- `onlyTheCompanionIsTransformed` -- The outer class has no `__memo*` fields

### ShapeTest (Kotlin JVM)

Source: [`memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/ShapeTest.kt`](https://github.com/sanadlab/MORAl/blob/main/MORAl/memoize-test-kotlin-jvm/src/test/kotlin/io/github/sanadlab/testkotlin/ShapeTest.kt)

Tests for `@Memoize` and `@CacheInvalidate` on the default methods of the Kotlin interface `Shape`. `Square` uses the default methods. `Tile` overrides `area()` without `@Memoize`:

- `interfaceLoads` -- `Shape` loads and has no `__memo*` fields
- `defaultMethodIsCached` -- `area()` runs one time for two calls
- `defaultMethodWithArgumentsIsCached` -- `scaled(factor)` uses the argument as the key
- `instancesHaveSeparateCaches` -- Each `Square` has its own cache
- `defaultInvalidatorClearsTheCache` -- `resetShape()` clears the caches of its instance
- `invalidationIsPerInstance` -- `resetShape()` on one instance keeps the caches of another instance
- `overrideWithoutMemoizeIsNotCached` -- `Tile.area()` runs on every call
- `namedDefaultInvalidatorClearsOnlyItsTarget` -- `resetArea()`, which is `@CacheInvalidate("area")`, clears `area()` and keeps `scaled()`
- `namedInvalidatorWithOverriddenTargetClearsNothing` -- `Tile` overrides `area()` without `@Memoize`, so its copy of `resetArea()` has nothing to clear. The `scaled()` cache stays.
- `inheritedDefaultInOverridingClassIsCached` -- `Tile.scaled()` is still cached

The test project compiles with Kotlin 2.0.21 and its default `-Xjvm-default=disable` mode. The 8 original tests were also run by hand in other modes:

| Kotlin mode | Result |
|-------------|--------|
| 2.0.21, `-Xjvm-default=disable` (default) | All pass (in the test suite) |
| 2.4.10, `-jvm-default=enable` (default since Kotlin 2.2) | The 8 original tests pass |
| 2.0.21, `-Xjvm-default=all-compatibility` | The interface loads, but the 5 caching tests fail. Kotlin makes no copies in the implementing classes, so nothing is cached. |
| 2.0.21, `-Xjvm-default=all` | The build fails with `MemoizeConfigurationException`, as designed |

:::{note}
**Android unit tests** (`testDebugUnitTest`) run against the transformed classes. With AGP 8.10.1, the output of `transformDebugClassesWithAsm` is on the unit-test runtime classpath. `TransformAppliedTest` checks this.

**JVM unit tests** run against **post-transformation** bytecode (the plugin's `doLast` hook transforms classes before tests run). This means JVM tests validate actual memoization behavior end-to-end.
:::
