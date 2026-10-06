# Examples

## Basic Memoization

### Simple Pure Function

```java
import io.github.sanadlab.annotations.Memoize;

public class MathUtils {

    @Memoize(maxSize = 256)
    public long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }
}
```

The recursive calls will hit the cache, turning O(2^n) into O(n).

### Query Method with Full Invalidation

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

    @CacheInvalidate  // Clears ALL caches (search + length)
    public void insert(int data) {
        Node newNode = new Node(data);
        // ... insertion logic ...
    }

    @CacheInvalidate
    public void delete(int key) {
        // ... deletion logic ...
    }
}
```

**Execution flow:**

```
list.insert(10)       // Inserts, clears all caches
list.insert(20)       // Inserts, clears all caches
list.search(10)       // MISS -> traverses -> returns true -> caches
list.search(10)       // HIT -> returns true immediately
list.length()         // MISS -> counts -> returns 2 -> caches
list.length()         // HIT -> returns 2 immediately
list.insert(30)       // Inserts, clears ALL caches (search + length)
list.search(10)       // MISS -> re-traverses -> returns true -> caches
list.length()         // MISS -> re-counts -> returns 3 -> caches
```

### Selective Cache Invalidation

When a class has many memoized methods and you know a mutation only affects specific caches, use selective invalidation to avoid unnecessarily clearing unrelated caches:

```java
import io.github.sanadlab.annotations.Memoize;
import io.github.sanadlab.annotations.CacheInvalidate;

public class DocumentStore {

    @Memoize(maxSize = 100)
    public Document getDocument(int id) {
        return db.queryDocument(id);
    }

    @Memoize(maxSize = 50)
    public int getDocumentCount() {
        return db.countDocuments();
    }

    @Memoize(maxSize = 200)
    public List<Tag> getTags() {
        return db.queryAllTags();
    }

    @Memoize
    public UserPrefs getUserPrefs() {
        return db.queryPrefs();
    }

    // Adding a document affects getDocument and getDocumentCount,
    // but NOT getTags or getUserPrefs
    @CacheInvalidate({"getDocument", "getDocumentCount"})
    public void addDocument(Document doc) {
        db.insertDocument(doc);
    }

    // Updating tags only affects getTags
    @CacheInvalidate("getTags")
    public void updateTags(List<Tag> tags) {
        db.replaceTags(tags);
    }

    // Nuclear option: clear everything
    @CacheInvalidate
    public void resetAll() {
        db.clearAll();
    }
}
```

**Execution flow:**

```
store.getDocument(1)    // MISS -> queries DB -> caches
store.getTags()         // MISS -> queries DB -> caches
store.getUserPrefs()    // MISS -> queries DB -> caches

store.addDocument(doc)  // Clears ONLY getDocument + getDocumentCount
                        // getTags and getUserPrefs caches are PRESERVED

store.getDocument(1)    // MISS -> re-queries (was invalidated)
store.getTags()         // HIT -> still cached! (was NOT invalidated)
store.getUserPrefs()    // HIT -> still cached!

store.updateTags(tags)  // Clears ONLY getTags

store.getTags()         // MISS -> re-queries
store.getUserPrefs()    // HIT -> still cached!
```

## Method Overloading

Overloaded methods are fully supported. Each overload gets its own independent cache:

```java
import io.github.sanadlab.annotations.Memoize;
import io.github.sanadlab.annotations.CacheInvalidate;

public class Calculator {
    private int base = 0;

    @Memoize(maxSize = 64)
    public long compute(int x) {
        // Single-arg overload -- has its own cache
        return base + x * 1000L;
    }

    @Memoize(maxSize = 64)
    public long compute(int x, int y) {
        // Two-arg overload -- separate cache from compute(int)
        return base + x * y * 1000L;
    }

    // Invalidates BOTH overloads of compute (all methods named "compute")
    @CacheInvalidate("compute")
    public void setBase(int newBase) {
        this.base = newBase;
    }
}
```

Under the hood, each overload gets a unique field name via a hash of the method descriptor:

```
compute(int)      → __memoDispatcher_compute_ddad9
compute(int, int) → __memoDispatcher_compute_df4b2
```

When `@CacheInvalidate("compute")` is used, **all overloads** matching the name are invalidated. This resolution happens at build time.

## Thread Safety Options

Choose the right synchronization strategy based on your access pattern:

```java
import io.github.sanadlab.annotations.Memoize;
import io.github.sanadlab.annotations.ThreadSafety;

public class Processor {

    // Default: thread-safe with synchronized LRU cache
    @Memoize(maxSize = 128)
    public Result processMultiThreaded(String input) { ... }

    // No synchronization: fastest, for single-threaded paths only
    @Memoize(maxSize = 128, threadSafety = ThreadSafety.NONE)
    public Result processMainThreadOnly(String input) { ... }
}
```

`ThreadSafety.NONE` uses `UnsynchronizedLruMemoCache` -- a plain `LinkedHashMap` with no locks. This eliminates ~20-30ns of synchronization overhead per call but is unsafe for concurrent access.

## TTL (Time-To-Live) Expiry

Cache entries that become stale after a time period:

```java
@Memoize(maxSize = 100, expireAfterWrite = 30000)  // 30-second TTL
public ExchangeRate getRate(String currency) {
    return api.fetchRate(currency);
}
```

After 30 seconds, cached entries are considered expired and recomputed on the next call.

## Kotlin Example

```kotlin
import io.github.sanadlab.annotations.Memoize
import io.github.sanadlab.annotations.CacheInvalidate

class UserRepository(private val db: Database) {

    @Memoize(maxSize = 100)
    fun findUser(id: Int): User? {
        return db.query("SELECT * FROM users WHERE id = ?", id)
    }

    @Memoize(maxSize = 50)
    fun countActiveUsers(): Int {
        return db.queryScalar("SELECT COUNT(*) FROM users WHERE active = 1")
    }

    @CacheInvalidate
    fun createUser(name: String, email: String) {
        db.execute("INSERT INTO users (name, email) VALUES (?, ?)", name, email)
    }

    @CacheInvalidate
    fun deleteUser(id: Int) {
        db.execute("DELETE FROM users WHERE id = ?", id)
    }
}
```

## Static Methods

### Java

```java
public final class Units {
    private static double rate = 1.0;

    @Memoize(maxSize = 256)
    public static long convert(int cents) {
        return Math.round(cents * rate);
    }

    // Static invalidator: clears the static convert() cache
    @CacheInvalidate("convert")
    public static void setRate(double newRate) {
        rate = newRate;
    }
}
```

### Kotlin

```kotlin
// Top-level function: a static method of the file class
@Memoize
fun slugify(title: String): String = title.lowercase().replace(" ", "-")

// @JvmStatic in an object: a static method of Registry
object Registry {
    @JvmStatic
    @Memoize
    fun lookup(id: Int): String = loadName(id)

    // Plain member of the object: can clear the static cache
    @CacheInvalidate("lookup")
    fun rename(id: Int, name: String) { /* ... */ }
}
```

A `@JvmStatic` function in a `companion object` also works. Kotlin callers and Java callers share the cache of the companion. See [Static Methods](annotations.md#static-methods) for the rules.

## Multiple Memoized Methods

When a class has many memoized methods, `@CacheInvalidate` clears all of them at once:

```java
public class ImageProcessor {

    @Memoize(maxSize = 32)
    public Bitmap loadThumbnail(String uri) { /* expensive I/O */ }

    @Memoize(maxSize = 16)
    public ImageMetadata getMetadata(String uri) { /* parse EXIF */ }

    @Memoize(maxSize = 64)
    public int[] getHistogram(String uri) { /* compute pixel histogram */ }

    @CacheInvalidate
    public void clearImageCache() {
        // Clears loadThumbnail, getMetadata, AND getHistogram caches
    }
}
```

## Methods Returning Null

Null return values are cached correctly. The library uses a sentinel pattern to distinguish "cache miss" from "cached null":

```java
public class DataService {

    @Memoize(maxSize = 200)
    public String findDescription(int id) {
        // May return null if id not found
        Record r = db.find(id);
        return r != null ? r.description : null;
    }
}
```

```
service.findDescription(999)   // MISS -> returns null -> caches null
service.findDescription(999)   // HIT -> returns null (from cache, not recomputed)
```

## Zero-Argument Methods

Methods with no arguments are memoized using a shared `EMPTY` key singleton:

```java
@Memoize
public int length() {
    int count = 0;
    Node current = head;
    while (current != null) { count++; current = current.next; }
    return count;
}
```

Only one cache entry is ever created (keyed on `CacheKeyWrapper.EMPTY`).

## Multi-Argument Methods

Methods with multiple arguments use a composite key via `CacheKeyWrapper`:

```java
@Memoize(maxSize = 128)
public double distance(double x1, double y1, double x2, double y2) {
    return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
}
```

The cache key is `new CacheKeyWrapper(new Object[]{x1, y1, x2, y2})` with equality based on `Arrays.deepEquals`.

## Auto-Monitoring Mode

Enable auto-monitoring to automatically disable caches that aren't performing well. This is useful when you're unsure whether memoization will be beneficial for a method, or when argument cardinality may be too high.

### Basic Auto-Monitor

```java
import io.github.sanadlab.annotations.Memoize;

public class SearchService {

    // Disables itself if hit rate < 30% after 100 calls
    @Memoize(maxSize = 128, autoMonitor = true)
    public Result search(String query) {
        return expensiveSearch(query);
    }
}
```

### Custom Thresholds

```java
// Stricter: requires 50% hit rate, evaluated after 200 calls
@Memoize(maxSize = 64, autoMonitor = true, minHitRate = 0.5, monitorWindow = 200)
public Data fetchData(int key) {
    return slowFetch(key);
}
```

### Kotlin Auto-Monitor

```kotlin
@Memoize(maxSize = 32, autoMonitor = true, minHitRate = 0.5, monitorWindow = 10)
fun monitoredCompute(x: Int): Long {
    // If this method gets called with too many unique arguments,
    // auto-monitor will disable caching after 10 calls
    return expensiveOperation(x)
}
```

**How it works:**

```
call(1)  // miss  (1 of 100)
call(2)  // miss  (2 of 100)
...
call(1)  // hit   (50 of 100)
...
// After 100 calls: hit rate = 35% > 30% → cache stays active

// OR: all unique args
call(1)   // miss
call(2)   // miss
...
call(100) // miss
// After 100 calls: hit rate = 0% < 30% → cache DISABLED
// All subsequent calls bypass caching entirely
```

When disabled, the cache memory is freed and every call runs the method body. Each call still builds the key and calls the dispatcher, which returns at once. Use `MemoDispatcher.reenable()` programmatically to re-enable if conditions change.

## Annotated Demo Apps

The library has been applied to several demo applications in `demos/`. Three of them are linked-list demos:

### Java Android Demo

**File:** `demos/linkedlistdemo/linked_list_demo_java_gradle_android/app/src/main/java/com/linkedlist/app/LinkedList.java`

```java
import io.github.sanadlab.annotations.CacheInvalidate;
import io.github.sanadlab.annotations.Memoize;

public class LinkedList implements Iterable<Node> {
    private Node head;

    @CacheInvalidate
    public void insert(int data) { /* ... */ }

    @CacheInvalidate
    public void insertAtHead(int data) { /* ... */ }

    @CacheInvalidate
    public void delete(int key) { /* ... */ }

    @Memoize(maxSize = 64)
    public boolean search(int key) { /* ... */ }

    @Memoize
    public int length() { /* ... */ }
}
```

### Kotlin Android Demo

**File:** `demos/linkedlistdemo/linked_list_demo_kotlin_gradle_android/app/src/main/java/com/linkedlist/app/LinkedList.kt`

```kotlin
import io.github.sanadlab.annotations.CacheInvalidate
import io.github.sanadlab.annotations.Memoize

class LinkedList : Iterable<Node> {
    private var head: Node? = null

    @CacheInvalidate fun insert(data: Int) { /* ... */ }
    @CacheInvalidate fun insertAtHead(data: Int) { /* ... */ }
    @CacheInvalidate fun delete(key: Int) { /* ... */ }

    @Memoize(maxSize = 64) fun search(key: Int): Boolean { /* ... */ }
    @Memoize fun length(): Int { /* ... */ }
}
```

### Mixed Java+Kotlin Android Demo

**Directory:** `demos/linkedlistdemo/linked_list_demo_kotlin_java_gradle_android/`

Contains both a Java `JavaLinkedList` and a Kotlin `LinkedList`, both annotated. The ASM transformation handles both languages identically since it operates at bytecode level.
