# Getting Started

## Prerequisites

- Android Gradle Plugin 8.0+
- Gradle 8.0+
- JDK 17+ to run Gradle. The plugin is compiled for Java 17.
- Java 11+ or Kotlin for your code. The runtime library targets Java 11.
- Kotlin 2.0+ only if you use the optional KSP checks

## Installation

### Step 1: Add the plugin to your settings

The plugin is not published to a repository. Add MORAl as an included build.

The library modules `memoize-annotations` and `memoize-runtime` come from `mavenLocal()`. Publish them first:

```bash
cd path/to/MORAl
./gradlew publishToMavenLocal
```

The plugin and the runtime must come from the same MORAl version. The generated code calls runtime methods such as `computeStart()`. If you update MORAl, publish the runtime again.

**Kotlin DSL** (`settings.gradle.kts`):

```kotlin
pluginManagement {
    // The plugin is not published. Include the MORAl build.
    includeBuild("path/to/MORAl")

    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()  // memoize-annotations and memoize-runtime
    }
}
```

**Groovy DSL** (`settings.gradle`):

```groovy
pluginManagement {
    // The plugin is not published. Include the MORAl build.
    includeBuild 'path/to/MORAl'

    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()  // memoize-annotations and memoize-runtime
    }
}
```

### Step 2: Apply the plugin and add dependencies

**Kotlin DSL** (`app/build.gradle.kts`):

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.sanadlab")  // Add this
}

dependencies {
    implementation("io.github.sanadlab:memoize-annotations:0.1.0")
    implementation("io.github.sanadlab:memoize-runtime:0.1.0")
}
```

**Groovy DSL** (`app/build.gradle`):

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'io.github.sanadlab'  // Add this
}

dependencies {
    implementation 'io.github.sanadlab:memoize-annotations:0.1.0'
    implementation 'io.github.sanadlab:memoize-runtime:0.1.0'
}
```

The KSP processor (`memoize-ksp`) is optional. It is not published, and these steps do not add it. The plugin does not apply KSP. Without KSP, the plugin still transforms your code, but the compile-time checks do not run.

### Step 3: Annotate your methods

Mark expensive reads with `@Memoize`, and mutators with `@CacheInvalidate` so
caches stay coherent with the underlying state.

#### 3.1 &nbsp; The minimal pattern

```java
import io.github.sanadlab.annotations.Memoize;
import io.github.sanadlab.annotations.CacheInvalidate;

public class MyRepository {

    @Memoize(maxSize = 256)
    public UserProfile loadProfile(int userId) {
        return db.queryProfile(userId);              // expensive — cached
    }

    @CacheInvalidate("loadProfile")
    public void updateProfile(int userId, String name) {
        db.update(userId, name);                     // mutates → wipes loadProfile cache
    }
}
```

No call-site changes needed. The build-time transformation wires everything up.

#### 3.2 &nbsp; Selective full-flush across multiple methods

Legacy `value` form lists cache names to wipe entirely. Every other cache on
the instance is untouched:

```java
@Memoize  public List<Doc>    listDocs(String folder) { ... }
@Memoize  public int          docCount()               { ... }
@Memoize  public List<String> listTags(int docId)      { ... }

@CacheInvalidate({"listDocs", "docCount"})
public void addDoc(Doc doc) {
    db.insert(doc);
    // listTags cache is *not* cleared — tags didn't change.
}
```

#### 3.3 &nbsp; Single-entry eviction when you know which row changed

A full flush is wasteful if only one cached row is stale. Use
`@InvalidateCacheEntry` (single target) or the structured
`@CacheInvalidate(targets = ...)` form (multiple targets). Here's the former:

```java
import io.github.sanadlab.annotations.InvalidateCacheEntry;

@Memoize
public UserProfile loadProfile(int userId) { ... }

@InvalidateCacheEntry(method = "loadProfile", keys = {0})
public void updateProfile(int userId, String name) {
    db.update(userId, name);
    // Only loadProfile's entry for this userId is evicted.
}
```

`keys = {0}` means "take parameter index 0 of the enclosing method
(`userId`) and rebuild the target's cache key from it."

#### 3.4 &nbsp; Heterogeneous targets on one mutator

Real mutators often affect several caches differently: one gets its row
evicted, another has to be flushed whole. Use `targets`:

```java
import io.github.sanadlab.annotations.CacheInvalidate;
import io.github.sanadlab.annotations.Invalidation;

@Memoize public Document getDocument(int id)      { ... }
@Memoize public int      getDocumentCount()       { ... }

// Adding a document: the new row invalidates *only* getDocument(doc.id),
// but the count cache must be flushed.
@CacheInvalidate(targets = {
    @Invalidation(method = "getDocument",      keyBuilder = "docKey"),
    @Invalidation(method = "getDocumentCount", allEntries = true)
})
public void addDocument(Document doc) {
    db.insert(doc);
}

private Object docKey(Document doc) {
    return doc.id;    // scalar — the transform wraps it as the target's arg tuple
}
```

Three per-target modes:

| Mode | Written as | Effect |
|------|------------|--------|
| Full flush | `allEntries = true` | Evict every entry of the named cache. |
| Param-index key | `keys = {0, 2}` | Take enclosing args at indices 0 and 2, box them, use as the target's cache key. |
| Builder method | `keyBuilder = "docKey"` | Call the named helper, use its return value as the target's key. |

#### 3.5 &nbsp; Key builders with ambient / extra state

The helper named by `keyBuilder` is plain Java — it can read static fields,
instance fields, thread-locals, call other methods, etc. When the helper needs
information that *isn't* in the mutating method's parameter list, pass it
through `this` or a static:

```java
public class DocumentStore {
    private int currentUserId;   // set elsewhere (e.g., per request)

    @Memoize public Document getDocument(int userId, int id) { ... }

    @CacheInvalidate(targets = {
        @Invalidation(method = "getDocument", keyBuilder = "docKey")
    })
    public void addDocument(Document doc) {
        db.insert(doc, currentUserId);
    }

    // Reads instance state. It gets only the arguments of the enclosing method.
    // Returns the arguments of getDocument(userId, id), in order.
    private Object[] docKey(Document doc) {
        return new Object[]{ currentUserId, doc.id };
    }
}
```

The key builder must return the arguments of the target method. A value of a different shape, such as the string `userId + ":" + doc.id`, matches no entry, so the call evicts nothing.

When the helper *does* want extra enclosing-method parameters, declare them on
its signature &mdash; the transform auto-forwards the first N parameters of
the mutating method (N = helper's arity):

```java
@CacheInvalidate(targets = {
    @Invalidation(method = "getDocument", keyBuilder = "docKeyAs")
})
public void addDocumentAs(Document doc, int userId) {
    db.insert(doc, userId);
}

// Both (doc, userId) are forwarded automatically because docKeyAs takes 2 args
// and addDocumentAs has ≥ 2 declared parameters.
// The target is getDocument(int userId, int id).
// Use a unique helper name: the plugin uses the first method with that name.
private Object[] docKeyAs(Document doc, int userId) {
    return new Object[]{ userId, doc.id };
}
```

To forward a different subset or reorder, set `keyBuilderArgs` explicitly:

```java
@CacheInvalidate(targets = {
    // Pass only the 3rd enclosing arg to the builder, ignore the first two.
    @Invalidation(method = "getTags",
                  keyBuilder = "tagKey",
                  keyBuilderArgs = {2})
})
public void updateTags(String auditLabel, long ts, int docId) { ... }

private Object tagKey(int docId) { return docId; }
```

Key builders can be `static` and can return `Object[]` to supply a multi-part
key tuple verbatim; anything else is auto-wrapped into a one-element array.

#### 3.6 &nbsp; Legacy and structured can be combined

`value` (full-flush names) and `targets` (structured directives) on the same
annotation both fire &mdash; legacy names first, then each structured
directive in order:

```java
@CacheInvalidate(
    value = {"getTags"},                    // wipe every getTags entry
    targets = {
        @Invalidation(method = "getDocument", keys = {0})   // evict one row
    }
)
public void markStale(int id) { ... }
```

#### 3.7 &nbsp; Static methods and Kotlin top-level functions

`@Memoize` also works on static methods. Each static method gets one cache for its class, and all callers share it.

```java
public final class Geo {
    @Memoize(maxSize = 512)
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        // expensive computation
    }
}
```

```kotlin
@Memoize
fun slugify(title: String): String = title.lowercase().replace(" ", "-")
```

An instance method can invalidate static caches. A static method can invalidate static caches only. See [Static Methods](annotations.md#static-methods) for the supported Kotlin forms and the rules.

### Step 4: Build and verify

```bash
# Build your project
./gradlew assembleDebug

# Verify transformation (optional)
javap -p app/build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs/com/example/MyRepository.class
```

You should see `__memoCacheManager` and `__memoDispatcher_*` fields in the output. For static methods, you see `__memoStaticCacheManager` and static `__memoDispatcher_*` fields.

## Quick Example

### Java

```java
import io.github.sanadlab.annotations.Memoize;
import io.github.sanadlab.annotations.CacheInvalidate;

public class LinkedList {
    private Node head;

    @Memoize(maxSize = 64)
    public boolean search(int key) {
        Node current = head;
        while (current != null) {
            if (current.data == key) return true;
            current = current.next;
        }
        return false;
    }

    @Memoize
    public int length() {
        int count = 0;
        Node current = head;
        while (current != null) { count++; current = current.next; }
        return count;
    }

    // Selective: only invalidates search and length
    @CacheInvalidate({"search", "length"})
    public void insert(int data) {
        Node newNode = new Node(data);
        // ... insert logic ...
    }

    // Full: invalidates all caches on the instance
    @CacheInvalidate
    public void clear() {
        head = null;
    }
}
```

### Kotlin

```kotlin
import io.github.sanadlab.annotations.Memoize
import io.github.sanadlab.annotations.CacheInvalidate

class LinkedList : Iterable<Node> {
    private var head: Node? = null

    @Memoize(maxSize = 64)
    fun search(key: Int): Boolean {
        var current = head
        while (current != null) {
            if (current.data == key) return true
            current = current.next
        }
        return false
    }

    @Memoize
    fun length(): Int {
        var count = 0
        var current = head
        while (current != null) { count++; current = current.next }
        return count
    }

    @CacheInvalidate("search", "length")
    fun insert(data: Int) { /* ... */ }

    @CacheInvalidate
    fun clear() { head = null }
}
```

## JVM (Non-Android) Projects

The plugin also works with plain JVM projects (Java and Kotlin JVM). No AGP required.

### Setup

**Kotlin DSL** (`settings.gradle.kts`):

```kotlin
pluginManagement {
    includeBuild("path/to/MORAl")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}
```

**Kotlin DSL** (`build.gradle.kts`):

```kotlin
plugins {
    java  // or kotlin("jvm")
    id("io.github.sanadlab")
}

dependencies {
    implementation("io.github.sanadlab:memoize-annotations:0.1.0")
    implementation("io.github.sanadlab:memoize-runtime:0.1.0")
}
```

**Groovy DSL** (`build.gradle`):

```groovy
plugins {
    id 'java'  // or id 'org.jetbrains.kotlin.jvm'
    id 'io.github.sanadlab'
}

dependencies {
    implementation 'io.github.sanadlab:memoize-annotations:0.1.0'
    implementation 'io.github.sanadlab:memoize-runtime:0.1.0'
}
```

The plugin detects that AGP is absent. It then adds a `doLast` action to each `JavaCompile` task and to the `compileKotlin` task. The action transforms the `.class` files in place. It skips a class that an earlier build already transformed, so incremental builds do not transform a class twice. No configuration difference from the developer's perspective.

### Kotlin Multiplatform

Kotlin Multiplatform is not supported yet. The plugin transforms only the output of `JavaCompile` tasks and of the task named `compileKotlin` (in `build/classes/kotlin/main`). A KMP JVM target compiles with `compileKotlinJvm` into `build/classes/kotlin/jvm/main`, so the plugin does not transform its Kotlin classes.

## Building from Source

```bash
# Clone and build the library
cd MORAl
./gradlew build

# Publish memoize-annotations and memoize-runtime to mavenLocal
./gradlew publishToMavenLocal

# Build and test the test app
cd memoize-test-android
./gradlew assembleDebug testDebugUnitTest
```
