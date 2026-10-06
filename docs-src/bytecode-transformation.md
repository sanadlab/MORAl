# Build-Time Bytecode Transformation

This is the core mechanism that makes `@Memoize` transparent. The Gradle plugin transforms `.class` files at build time using ASM bytecode manipulation.

Source: [`memoize-gradle-plugin/src/main/kotlin/io/github/sanadlab/plugin/`](https://github.com/sanadlab/MORAl/tree/main/MORAl/memoize-gradle-plugin/src/main/kotlin/io/github/sanadlab/plugin)

## Plugin Registration

The `MemoizePlugin` auto-detects the project type and registers the appropriate transformation:

```kotlin
// MemoizePlugin.kt
class MemoizePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Try Android first
        if (applyAndroid(project)) return
        // Fall back to JVM
        applyJvm(project)
    }
}
```

### Android Mode (AGP detected)

Uses the AGP Instrumentation API. Runs during the `transformDebugClassesWithAsm` task:

```kotlin
androidComponents.onVariants { variant ->
    variant.instrumentation.transformClassesWith(
        MemoizeClassVisitorFactory::class.java,
        InstrumentationScope.PROJECT
    ) {}
    variant.instrumentation.setAsmFramesComputationMode(
        FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
    )
}
```

### JVM Mode (no AGP)

Registers a post-compilation `doLast` action on `JavaCompile` and `compileKotlin` tasks. Walks the classes directory and transforms `.class` files in-place using `JvmBytecodeTransformer`:

```kotlin
compileTask.doLast {
    val classesDir = compileTask.destinationDirectory.asFile.get()
    transformClassesInDirectory(classesDir, project)
}
```

The `JvmBytecodeTransformer` does a quick string scan for annotation descriptors before parsing, so unrelated classes are skipped with near-zero overhead.

The action transforms every class file in the output directory after each compile. With incremental compilation, it also sees classes that an earlier build transformed. The visitor skips a class that already has a `__memoCacheManager` or `__memoStaticCacheManager` field, and `JvmBytecodeTransformer` then returns `null`. So the file stays as it is, and no class is transformed twice.

## Class Filtering

The factory determines which classes to instrument:

```kotlin
// MemoizeClassVisitorFactory.kt
override fun isInstrumentable(classData: ClassData): Boolean {
    val className = classData.className
    return !className.startsWith("android.") &&
           !className.startsWith("androidx.") &&
           !className.startsWith("kotlin.") &&
           !className.startsWith("java.") &&
           !className.startsWith("io.github.sanadlab.runtime.") &&
           !className.startsWith("io.github.sanadlab.annotations.")
}
```

All project classes pass through the visitor. Classes without `@Memoize` or `@CacheInvalidate` annotations are passed through unmodified (the visitor detects this in Phase 1 and short-circuits).

## Two-Pass Transformation Strategy

The transformation uses two passes because of a chicken-and-egg problem: constructors appear before other methods in bytecode, but we need to know all memoized methods to generate constructor initialization code.

```
Pass 1 (Tree API - ClassNode):
  ├── Read entire class into memory
  ├── Skip the class if it already has a manager field (JVM incremental builds)
  ├── Scan ALL methods for annotations → collect metadata
  │     (skip Kotlin @JvmStatic companion bridges, abstract methods and
  │      Kotlin $DefaultImpls classes, check the static and interface rules
  │      and the invalidation targets)
  ├── Add fields: __memoCacheManager, __memoDispatcher_*         (instance methods)
  │               __memoStaticCacheManager, static __memoDispatcher_*  (static methods)
  ├── Patch constructors: insert instance initialization before RETURN
  ├── Patch <clinit>: insert static initialization at the start
  └── Serialize modified ClassNode to byte[]

Pass 2 (Visitor API - AdviceAdapter):
  ├── Re-read the modified bytes
  ├── For @Memoize methods: inject cache-check at entry, computeStart() after
  │     a miss, cache-store before each normal return
  ├── For invalidators: inject invalidateAll(), invalidate(String[])
  │     or invalidateEntry(...) before each normal return
  └── Write to output ClassVisitor
```

## Phase 1: Annotation Scanning

The tree API provides full access to the class structure:

```kotlin
// Simplified from MemoizeClassVisitor.visitEnd()
for (method in classNode.methods) {
    val allAnnotations = (method.visibleAnnotations.orEmpty()) +
                         (method.invisibleAnnotations.orEmpty())
    for (ann in allAnnotations) {
        when (ann.desc) {
            "Lio/github/sanadlab/annotations/Memoize;" -> {
                // Read maxSize parameter
                var maxSize = 128
                if (ann.values != null) {
                    var i = 0
                    while (i < ann.values.size - 1) {
                        if (ann.values[i] == "maxSize") maxSize = ann.values[i+1] as Int
                        i += 2
                    }
                }
                memoizedMethods.add(MemoMethodInfo(method.name, method.desc,
                    methodKey(method.name, method.desc), maxSize))
            }
            "Lio/github/sanadlab/annotations/CacheInvalidate;" -> {
                invalidateMethods.add(method.name)
            }
        }
    }
}
```

Annotation values in the ASM tree model are stored as alternating name-value pairs: `["maxSize", 64, "recordStats", false]`.

The real code reads eight of the nine `@Memoize` attributes. It ignores `scope`. It also parses `value`, `targets` and `@InvalidateCacheEntry`. It skips Kotlin companion bridges, abstract methods and Kotlin `$DefaultImpls` classes. It checks every invalidation target (see [Build Errors](#build-errors)).

## Phase 2: Field Addition

For a class with `search(int)` (maxSize=64) and `length()` (maxSize=128) memoized:

```kotlin
// One MemoCacheManager field per class
classNode.fields.add(FieldNode(
    ACC_PRIVATE | ACC_SYNTHETIC, "__memoCacheManager",
    "Lio/github/sanadlab/runtime/MemoCacheManager;", null, null
))

// One MemoDispatcher field per memoized method (with hash suffix)
classNode.fields.add(FieldNode(
    ACC_PRIVATE | ACC_SYNTHETIC, "__memoDispatcher_search_297da",
    "Lio/github/sanadlab/runtime/MemoDispatcher;", null, null
))
classNode.fields.add(FieldNode(
    ACC_PRIVATE | ACC_SYNTHETIC, "__memoDispatcher_length_8aec2",
    "Lio/github/sanadlab/runtime/MemoDispatcher;", null, null
))
```

**Overload-safe field naming:** Each field name includes a 5-hex-digit hash derived from the method name + JVM descriptor. This ensures overloaded methods get unique fields:

```
search(int)    → methodKey("search", "(I)Z")     → "search_297da"
search(String) → methodKey("search", "(Ljava/lang/String;)Z") → "search_ce55b"
length()       → methodKey("length", "()I")       → "length_8aec2"
```

Fields are marked `ACC_SYNTHETIC` so they don't appear in IDE auto-complete.

## Phase 3: Constructor Patching

Before every `RETURN` instruction in every `<init>` method, the following bytecode is inserted (shown as equivalent Java):

```java
// Initialization inserted before constructor returns:
this.__memoCacheManager = new MemoCacheManager();

// MemoDispatcher.create() gets the method key and the annotation values:
this.__memoDispatcher_search_297da = MemoDispatcher.create(
    "search_297da", 64, -1L, "LRU", "CONCURRENT", false
);
this.__memoCacheManager.register("search_297da", this.__memoDispatcher_search_297da);

this.__memoDispatcher_length_8aec2 = MemoDispatcher.create(
    "length_8aec2", 128, -1L, "LRU", "CONCURRENT", false
);
this.__memoCacheManager.register("length_8aec2", this.__memoDispatcher_length_8aec2);
```

`MemoDispatcher.create()` takes 6 parameters: the method key and five values from the `@Memoize` annotation (`maxSize`, `expireAfterWrite`, `eviction`, `threadSafety`, `recordStats`). If `autoMonitor = true`, the plugin calls the 9-parameter `create()`, which also passes `autoMonitor`, `minHitRate` and `monitorWindow`.

This is implemented by finding all `RETURN` opcodes in the constructor's instruction list and inserting `InsnList` nodes before each:

```kotlin
private fun patchConstructor(method: MethodNode, internalName: String, ...) {
    val returnInsns = mutableListOf<AbstractInsnNode>()
    var node = method.instructions.first
    while (node != null) {
        if (node.opcode == Opcodes.RETURN) returnInsns.add(node)
        node = node.next
    }
    for (ret in returnInsns) {
        val initInsns = InsnList()
        // ... build initialization bytecode ...
        method.instructions.insertBefore(ret, initInsns)
    }
}
```

## Phase 4: Method Body Instrumentation

### @Memoize Methods

The `MemoizeMethodAdapter` (extending ASM's `AdviceAdapter`) injects at two points:

#### At Method Entry (`onMethodEnter`)

For `@Memoize public boolean search(int key)`:

```java
// 1. Build cache key from arguments
CacheKeyWrapper __key = this.__memoDispatcher_search_297da.buildKey(
    new Object[]{ Integer.valueOf(key) }  // box primitive
);

// 2. Check cache
Object __cached = this.__memoDispatcher_search_297da.getIfCached(__key);

// 3. If cache hit, unbox and return
if (__cached != null) {
    return ((Boolean) MemoDispatcher.unwrap(__cached)).booleanValue();
}

// 4. Cache miss -- start the compute timer (0 when INFO logging is off)
long __start = this.__memoDispatcher_search_297da.computeStart();

// 5. Fall through to original method body
```

The actual bytecode for this (`javap -p -c` of the transformed `LinkedList` in `memoize-test-android`):

```
 0: aload_0
 1: getfield      __memoDispatcher_search_297da
 4: iconst_1                          // array size = 1
 5: anewarray     java/lang/Object
 8: dup
 9: iconst_0                          // index 0
10: iload_1                           // load arg 'key'
11: invokestatic  Integer.valueOf(int) // box
14: aastore
15: invokevirtual MemoDispatcher.buildKey
18: astore_2                          // store key in local
19: aload_0
20: getfield      __memoDispatcher_search_297da
23: aload_2
24: invokevirtual MemoDispatcher.getIfCached
27: dup
28: ifnull        41                  // jump to miss
31: invokestatic  MemoDispatcher.unwrap
34: checkcast     java/lang/Boolean
37: invokevirtual Boolean.booleanValue
40: ireturn                           // return cached value
41: pop                               // miss: discard null
42: aload_0
43: getfield      __memoDispatcher_search_297da
46: invokevirtual MemoDispatcher.computeStart
49: lstore_3                          // store the start time in a local
    // ... original method body follows ...
```

The plugin boxes with ASM `GeneratorAdapter.valueOf()`, so it emits `Integer.valueOf(key)`, `Boolean.valueOf(z)` and so on. The JDK caches the boxed objects of small values, so these calls often do not allocate.

#### Before Method Return (`onMethodExit`)

Before each return instruction, the return value is duplicated, boxed, and stored in the cache:

```java
// Return value is on the stack (e.g., 'true' as an int 1)
// Duplicate it (one copy for the actual return, one for caching)
boolean __returnValue = /* on stack */;

// Box and store. putInCache also records the compute time when __start != 0.
this.__memoDispatcher_search_297da.putInCache(__key, Boolean.valueOf(__returnValue), __start);

// Original return instruction executes
return __returnValue;
```

The actual bytecode before the `return true` in `search`:

```
70: iconst_1                          // the return value
71: dup                               // one copy to cache
72: invokestatic  Boolean.valueOf(boolean)
75: astore        6
77: aload_0
78: getfield      __memoDispatcher_search_297da
81: aload_2                           // the key
82: aload         6                   // the boxed result
84: lload_3                           // the start time
85: invokevirtual MemoDispatcher.putInCache(CacheKeyWrapper, Object, long)
88: pop
89: ireturn
```

The generated code calls `computeStart()` and the 3-argument `putInCache()`. So the plugin and the runtime library must come from the same MORAl version. The hit path does not call `computeStart()`.

#### Primitive Type Handling

The adapter handles all 8 Java primitive types plus object/array types:

| Return Type | Boxing Call | Unboxing Call | Return Opcode |
|-------------|------------|---------------|---------------|
| `boolean` | `Boolean.valueOf(z)` | `Boolean.booleanValue()` | `IRETURN` |
| `byte` | `Byte.valueOf(b)` | `Byte.byteValue()` | `IRETURN` |
| `char` | `Character.valueOf(c)` | `Character.charValue()` | `IRETURN` |
| `short` | `Short.valueOf(s)` | `Short.shortValue()` | `IRETURN` |
| `int` | `Integer.valueOf(i)` | `Integer.intValue()` | `IRETURN` |
| `long` | `Long.valueOf(l)` | `Long.longValue()` | `LRETURN` |
| `float` | `Float.valueOf(f)` | `Float.floatValue()` | `FRETURN` |
| `double` | `Double.valueOf(d)` | `Double.doubleValue()` | `DRETURN` |
| `Object` / arrays | no-op | `checkcast` | `ARETURN` |

For 2-slot types (`long`, `double`), `DUP2` is used instead of `DUP`.

The JDK caches all `Boolean` and `Byte` values, `Short`, `Integer` and `Long` values from -128 to 127, and `Character` values from 0 to 127. Boxing these values does not allocate. Larger values, and all `float` and `double` values, allocate a new object.

### @CacheInvalidate Methods — Legacy Form

The `InvalidateMethodAdapter` injects one of two calls before each return, depending on the annotation's `value`.

#### Bare `@CacheInvalidate` → `invalidateAll()`

**Source:**
```java
@CacheInvalidate
public void reset() {
    this.base = 0;
}
```

**Transformed (equivalent Java):**
```java
public void reset() {
    this.base = 0;
    if (this.__memoCacheManager != null) {
        this.__memoCacheManager.invalidateAll();
    }
}
```

#### `@CacheInvalidate({"name1", "name2"})` → `invalidate(String[])`

The user-facing names are resolved at build time to the `name_XXXXX` methodKeys — that's how overloaded methods get flushed together.

**Source:**
```java
@CacheInvalidate({"compute", "format"})
public void setBase(int newBase) {
    this.base = newBase;
}
```

**Transformed** (the `Calculator` class in `memoize-test-jvm`):
```java
public void setBase(int newBase) {
    this.base = newBase;
    if (this.__memoCacheManager != null) {
        this.__memoCacheManager.invalidate(new String[]{
            "compute_ddad9",     // all overloads of compute(...)
            "compute_df4b2",
            "format_9889b"
        });
    }
}
```

The plugin checks each name at build time. A name that matches no `@Memoize` method of the class fails the build with `MemoizeConfigurationException`. Only a bare `@CacheInvalidate` (no `value` and no `targets` in the source) emits `invalidateAll()`. A named invalidator never falls back to `invalidateAll()`.

The constructor creates the manager just before it returns. The null check protects an invalidator that runs before that point, for example one that the constructor body calls. A class whose only annotation is a bare `@CacheInvalidate` still gets the manager field and the constructor code.

Exception paths (`ATHROW`) do NOT trigger invalidation — if a mutation throws, the state may not have changed.

### @InvalidateCacheEntry Methods

The `InvalidateEntryMethodAdapter` evicts exactly one row from one target cache. It boxes the enclosing-method parameters named by `keys` into an `Object[]` and calls `manager.invalidateEntry(methodKey, args)`. The runtime rebuilds the target's `CacheKeyWrapper` from `args` and removes that entry.

**Source:**
```java
@Memoize
public UserProfile loadProfile(int userId) { ... }

@InvalidateCacheEntry(method = "loadProfile", keys = {0})
public void updateProfile(int userId, String name) {
    db.update(userId, name);
}
```

**Transformed:**
```java
public void updateProfile(int userId, String name) {
    db.update(userId, name);
    if (this.__memoCacheManager != null) {
        this.__memoCacheManager.invalidateEntry(
            "loadProfile_xxxxx",   // the hash depends on the full descriptor
            new Object[]{ Integer.valueOf(userId) }
        );
    }
}
```

Primitive parameters are auto-boxed to match the target cache's key shape. An out-of-range index in `keys` is skipped at transform time, so a typo does not crash the build. Its slot in the key array stays `null`, so the key matches no entry and the call evicts nothing.

If a method has both `@CacheInvalidate` and `@InvalidateCacheEntry`, both take effect. The plugin adds the `@InvalidateCacheEntry` as one more `keys` directive, after the directives of `@CacheInvalidate`.

### @CacheInvalidate Methods — Structured `targets`

When `@CacheInvalidate(targets = { @Invalidation(...), ... })` is used, `InvalidateMethodAdapter` emits one self-contained invalidation block per `@Invalidation` directive. Each block reloads the manager and null-guards independently. Legacy `value` (if also specified) fires first; structured directives follow in declared order.

Three per-target modes exist. Below, each is shown side-by-side: source on the left, generated code on the right.

The method keys in these examples come from the `DocumentStore` class in `memoize-test-jvm`. There, `getDocument(int)` returns `String`.

#### Mode 1: `allEntries = true` (FLUSH)

Same effect as legacy `@CacheInvalidate("method")`, but inside the structured list.

**Source:**
```java
@Invalidation(method = "getDocumentCount", allEntries = true)
```

**Generated (per-target block):**
```java
if (this.__memoCacheManager != null) {
    this.__memoCacheManager.invalidate(new String[]{ "getDocumentCount_8cdaa" });
}
```

#### Mode 2: `keys = {...}` (KEYS) — param-index keyed eviction

Semantically identical to `@InvalidateCacheEntry`, embedded as one directive among others on the same mutator.

**Source:**
```java
@Invalidation(method = "getDocument", keys = {0})
public void updateDocument(int id, String content) { ... }
```

**Generated:**
```java
if (this.__memoCacheManager != null) {
    this.__memoCacheManager.invalidateEntry(
        "getDocument_e7641",
        new Object[]{ Integer.valueOf(id) }   // enclosing param at index 0, boxed
    );
}
```

#### Mode 3: `keyBuilder = "..."` (KEY_BUILDER) — helper-built keys

The transform calls your named helper and forwards its return as the target's argument tuple. Two sub-paths depending on the helper's return type.

**3a. Helper returns `Object[]` — passed through verbatim.**

**Source:**
```java
@CacheInvalidate(targets = {
    @Invalidation(method = "taggedByTenant",
                  keyBuilder = "tenantTagKey",
                  keyBuilderArgs = {0})
})
public void refreshTenantDoc(int docId) { ... }

private Object[] tenantTagKey(int docId) {
    return new Object[]{ currentTenantId, docId };  // reads instance field
}
```

**Generated:**
```java
public void refreshTenantDoc(int docId) {
    // ... original body ...
    if (this.__memoCacheManager != null) {
        this.__memoCacheManager.invalidateEntry(
            "taggedByTenant_e0c58",
            this.tenantTagKey(docId)   // Object[] return used as the args tuple
        );
    }
}
```

**3b. Helper returns a scalar (or boxed primitive) — auto-wrapped into a one-element `Object[]`.**

**Source:**
```java
@CacheInvalidate(targets = {
    @Invalidation(method = "getDocument", keyBuilder = "docKey")
})
public void addDocument(Document doc) {
    db.insert(doc);
}

private Object docKey(Document doc) {
    return doc.id;   // scalar
}
```

**Generated:**
```java
public void addDocument(Document doc) {
    db.insert(doc);
    if (this.__memoCacheManager != null) {
        Object[] __key = new Object[1];
        __key[0] = this.docKey(doc);   // auto-wrap
        this.__memoCacheManager.invalidateEntry("getDocument_e7641", __key);
    }
}
```

#### Default `keyBuilderArgs` auto-forwards

When `keyBuilderArgs = {}` (the default) and the helper declares N parameters, the transform forwards the first N enclosing-method parameters in order. This lets `keyBuilder = "docKey"` "just work" without spelling out indices in the common "pass the args through" case.

**Source:**
```java
@CacheInvalidate(targets = {
    @Invalidation(method = "taggedByTenant", keyBuilder = "autoForwardKey")
})
public void reassignTag(int tenantId, int docId, String reason) { ... }

private Object[] autoForwardKey(int tenantId, int docId) {
    return new Object[]{ tenantId, docId };
}
```

**Generated:**
```java
public void reassignTag(int tenantId, int docId, String reason) {
    // ... original body ...
    if (this.__memoCacheManager != null) {
        // autoForwardKey has arity 2 → first 2 enclosing params forwarded.
        // `reason` (arg index 2) is NOT forwarded.
        this.__memoCacheManager.invalidateEntry(
            "taggedByTenant_e0c58",
            this.autoForwardKey(tenantId, docId)
        );
    }
}
```

#### Helper visibility → invoke opcode

The transform picks the right JVM invoke opcode based on the helper's access modifiers — this matters because `INVOKEVIRTUAL` is illegal on private methods per JVMS.

| Helper modifier | Emitted opcode |
|---|---|
| `static` | `INVOKESTATIC` |
| `private` (instance) | `INVOKESPECIAL` |
| any other (instance) | `INVOKEVIRTUAL` |

#### Heterogeneous directives on one mutator

All three modes (and legacy `value`) can coexist. Each emits its own self-contained block in source order.

**Source:**
```java
@CacheInvalidate(
    value = {"getTags"},
    targets = {
        @Invalidation(method = "getDocumentCount", allEntries = true),
        @Invalidation(method = "getDocument",     keys = {0}),
        @Invalidation(method = "taggedByTenant",  keyBuilder = "tenantTagKey")
    }
)
public void touchAllThree(int docId) { ... }
```

**Generated (outline):**
```java
public void touchAllThree(int docId) {
    // ... original body ...

    // Legacy value first
    if (this.__memoCacheManager != null) {
        this.__memoCacheManager.invalidate(new String[]{ "getTags_93303" });
    }

    // Structured target 1: FLUSH
    if (this.__memoCacheManager != null) {
        this.__memoCacheManager.invalidate(new String[]{ "getDocumentCount_8cdaa" });
    }

    // Structured target 2: KEYS
    if (this.__memoCacheManager != null) {
        this.__memoCacheManager.invalidateEntry(
            "getDocument_e7641",
            new Object[]{ Integer.valueOf(docId) }
        );
    }

    // Structured target 3: KEY_BUILDER
    if (this.__memoCacheManager != null) {
        this.__memoCacheManager.invalidateEntry(
            "taggedByTenant_e0c58",
            this.tenantTagKey(docId)
        );
    }
}
```

Each block reloads the manager and null-guards independently. The cost is a few extra `GETFIELD` instructions per target — negligible since invalidation paths aren't hot.

## Static Methods

A static `@Memoize` method has no `this`, so its cache state lives in static fields. The transform changes four things for static methods. Instance methods keep the code shown above.

### Static Fields

For `@Memoize public static int square(int x)`, the plugin adds:

```java
private static synthetic MemoCacheManager __memoStaticCacheManager;
private static synthetic MemoDispatcher   __memoDispatcher_square_3c8be;
```

The plugin adds a manager only where a cache or a bare invalidator needs it:

- The instance manager `__memoCacheManager` is added only if the class has an instance `@Memoize` method or a bare instance `@CacheInvalidate`. Only then does the plugin patch the constructors.
- The static manager `__memoStaticCacheManager` is added only if the class has a static `@Memoize` method or a bare static `@CacheInvalidate`. Only then does the plugin patch `<clinit>`.

A class with static and instance `@Memoize` methods gets both managers. An instance method that clears only static caches does not give each instance an empty manager. Its generated code loads the static manager.

### Static Initializer Patching

The plugin inserts the static setup at the **start** of `<clinit>`. If the class has no `<clinit>`, the plugin creates one.

```java
static {
    // Inserted by the plugin, before the original static initializer code:
    __memoStaticCacheManager = new MemoCacheManager();
    __memoDispatcher_square_3c8be = MemoDispatcher.create(
        "square_3c8be", 128, -1L, "LRU", "CONCURRENT", false
    );
    __memoStaticCacheManager.register("square_3c8be", __memoDispatcher_square_3c8be);

    // The original static initializer code follows.
}
```

The start position matters. A static field initializer, a `static {}` block or an enum constructor can call a memoized static method while `<clinit>` runs. Java builds the enum constants at the start of `<clinit>`. If the setup were at the end of `<clinit>`, these calls would read a null dispatcher, and class initialization would fail with `ExceptionInInitializerError`.

### Method Body

The cache check and the cache store are the same as for an instance method. Only the dispatcher load changes: `getstatic` replaces `aload_0` + `getfield`.

```
 0: getstatic     __memoDispatcher_square_3c8be
 3: iconst_1
 4: anewarray     java/lang/Object
 7: dup
 8: iconst_0
 9: iload_0                           // first argument is slot 0 in a static method
    ...                               // box, build the key, check the cache (same as an instance method)
```

ASM's `loadArg` already starts the argument slots at 0 for static methods.

### Invalidation Routing

Each invalidation block loads the manager that holds its target:

| Invalidator | Target | Manager in the generated code |
|-------------|--------|-------------------------------|
| Instance method | Instance cache | `this.__memoCacheManager` |
| Instance method | Static cache | `__memoStaticCacheManager` |
| Static method | Static cache | `__memoStaticCacheManager` |
| Static method | Instance cache | None. The build fails. |

A bare `@CacheInvalidate` calls `invalidateAll()` on the manager of its own kind: the instance manager for an instance method, the static manager for a static method. If a legacy name list mixes static and instance targets, the plugin emits one `invalidate(...)` block for each manager.

**Source:**
```java
@CacheInvalidate({"eurRate", "convert"})   // both targets are static
public void applyRate(double newRate) {
    eurRate = newRate;
}
```

**Transformed (equivalent Java):**
```java
public void applyRate(double newRate) {
    eurRate = newRate;
    if (__memoStaticCacheManager != null) {
        __memoStaticCacheManager.invalidate(new String[]{ "eurRate_c9261", "convert_f5255" });
    }
}
```

### Kotlin `@JvmStatic` Companion Bridges

For a `@JvmStatic` function in a companion object, Kotlin emits two methods. The real function is an instance method of `Host$Companion`. A static bridge in `Host` forwards to it. Kotlin copies all annotations to the bridge:

```
// Host (outer class)
public static final double toFahrenheit(int);
  0: getstatic     Host.Companion : LHost$Companion;
  3: iload_0
  4: invokevirtual Host$Companion.toFahrenheit:(I)D
  7: dreturn
```

The plugin skips a static method when all of these conditions are true:

1. The class has the `@kotlin.Metadata` annotation.
2. The body starts with `getstatic` of a field of this class, and the field type is a nested class of this class.
3. Only argument loads follow.
4. Then comes `invokevirtual` of a method with the same name and descriptor on that nested class.
5. A return ends the body.

For a skipped bridge, the plugin ignores `@Memoize`, `@CacheInvalidate` and `@InvalidateCacheEntry`. The companion copy has the same annotations and gets the cache. If the plugin instrumented the bridge too, the bridge would get a second cache. The invalidators in the companion clear only the companion cache, so Java callers of the bridge would get stale values.

### Build Errors

For these cases, the plugin throws `MemoizeConfigurationException`:

- A static method with `@Memoize`, `@CacheInvalidate` or `@InvalidateCacheEntry` in an interface.
- A static invalidator that names an instance cache, through `value`, an `@Invalidation` target or `@InvalidateCacheEntry`.
- A static invalidator whose `keyBuilder` is an instance method.
- A name in `@CacheInvalidate(value)`, an `@Invalidation(method)` or an `@InvalidateCacheEntry(method)` that matches no `@Memoize` method of the class. The only exception is a Kotlin interface copy (see [Interface Default Methods](#interface-default-methods) below).
- An `@Invalidation` without a `method`, an `@Invalidation` with both `keys` and `keyBuilder`, and a `keyBuilder` that names no method of the class.
- An interface default method with `@Memoize`, `@CacheInvalidate` or `@InvalidateCacheEntry`, unless it is a Kotlin interface compiled in a compatibility mode. See [Interface Default Methods](#interface-default-methods) below.

In JVM projects, `MemoizePlugin` changes this exception into a `GradleException`, so the build fails with the message. Other transform failures only log a warning and leave the class unchanged. In Android projects, AGP stops the build for any exception from the visitor.

## Interface Default Methods

The plugin never adds fields to an interface, so an interface cannot hold a per-instance cache. The plugin memoizes an interface default method only through the annotated copies that Kotlin puts in each implementing class. Such a copy is an ordinary instance method. The plugin memoizes it like any other instance method. So each instance gets its own cache, and a default `@CacheInvalidate` method clears the caches of its own instance only.

The plugin checks each annotated method in this order:

1. **Kotlin `$DefaultImpls` class.** The class has `@kotlin.Metadata` and an inner-class entry for itself with the name `DefaultImpls`. Kotlin puts the body of a default method there, as a static method with the same annotations. The plugin skips every method of this class.
2. **Abstract method.** An abstract method has no body. The plugin skips it. If the class is not a Kotlin class, the plugin prints a warning. Kotlin copies the annotations of a default method to the abstract interface method, so a Kotlin class gets no warning.
3. **Non-static interface method (a default method).**
   - If the class is a Kotlin class and has a method named `access$<name>$jd`, the plugin skips the method. This helper shows that Kotlin compiled the interface in a compatibility mode. Then the implementing classes can hold the annotated copies.
   - Otherwise the plugin throws `MemoizeConfigurationException`. This covers Java default methods and Kotlin `-Xjvm-default=all` (`-jvm-default=no-compatibility`).

On the interface itself, the plugin also checks that each invalidation target names a `@Memoize` method of the interface. A typo there fails the build.

In an implementing class, the plugin finds the Kotlin copies by their body. A copy loads `this` (`aload 0`) and the arguments. Then it calls `invokestatic Iface$DefaultImpls.<name>` or `invokespecial Iface.<name>`, and returns. The class can override a target of such a copy without `@Memoize`. So in a copy, a target that the class does not memoize is skipped with no error. If a copied invalidator has no target left, the plugin does not instrument it and adds no manager for it.

Kotlin makes the copies with `-Xjvm-default=disable` (the default in Kotlin 2.0 and 2.1) and with `-jvm-default=enable` (the default in Kotlin 2.2+). Kotlin 2.0 and 2.1 with `-Xjvm-default=all-compatibility` make no copies. A Java class that implements a Kotlin interface gets no copies either. In these cases the method is not cached, and the build gives no error. A class that overrides the method without `@Memoize` is not cached. See [Interface Default Methods](annotations.md#interface-default-methods) for the full description.

## Verifying the Transformation

After building, you can inspect the transformed bytecode:

```bash
# Build the APK
./gradlew assembleDebug

# Find the transformed class
find build -name "LinkedList.class" -path "*transformDebug*"

# Inspect with javap
javap -p -c <path_to_transformed_class>
```

Expected output shows:
- `__memoCacheManager` and `__memoDispatcher_*` fields
- Constructor with `new MemoCacheManager()` and `MemoDispatcher.create(...)` calls
- Memoized methods with cache-check at entry, `computeStart()` after a miss, and cache-store before returns
- Invalidate methods with `invalidateAll()`, `invalidate(String[])` or `invalidateEntry(...)` before returns
- For static `@Memoize` methods: `__memoStaticCacheManager`, static `__memoDispatcher_*` fields, and a `static {}` block that starts with `new MemoCacheManager()`
