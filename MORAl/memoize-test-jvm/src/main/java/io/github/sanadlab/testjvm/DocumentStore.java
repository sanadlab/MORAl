package io.github.sanadlab.testjvm;

import io.github.sanadlab.annotations.CacheInvalidate;
import io.github.sanadlab.annotations.InvalidateCacheEntry;
import io.github.sanadlab.annotations.Invalidation;
import io.github.sanadlab.annotations.Memoize;

import java.util.HashMap;
import java.util.Map;

/**
 * JVM test subject exercising the structured {@code @CacheInvalidate(targets = {...})}
 * form with nested {@code @Invalidation} directives. Demonstrates all three
 * per-target modes (FLUSH, KEYS, KEY_BUILDER) and the scalar vs. {@code Object[]}
 * key-builder return contract.
 *
 * The {@code *_calls} counters let tests tell a cache hit from a true re-invocation.
 */
public class DocumentStore {

    public static final class Document {
        public final int id;
        public final String content;
        public Document(int id, String content) {
            this.id = id;
            this.content = content;
        }
    }

    private final Map<Integer, String> db = new HashMap<>();

    public int getDocumentCalls = 0;
    public int getDocumentCountCalls = 0;
    public int getTagsCalls = 0;

    // Seed the store WITHOUT going through any @CacheInvalidate path.
    public void seed(int id, String content) {
        db.put(id, content);
    }

    @Memoize
    public String getDocument(int id) {
        getDocumentCalls++;
        return db.getOrDefault(id, "missing");
    }

    @Memoize
    public int getDocumentCount() {
        getDocumentCountCalls++;
        return db.size();
    }

    @Memoize
    public String getTags(int id) {
        getTagsCalls++;
        return "tags(" + id + ")";
    }

    // --- KEYS mode: enclosing param indices give the target's cache key ---

    // An update affects one entry of getDocument and (semantically) nothing else.
    @CacheInvalidate(targets = {
        @Invalidation(method = "getDocument", keys = {0})
    })
    public void updateDocument(int id, String newContent) {
        db.put(id, newContent);
    }

    // --- KEY_BUILDER mode: helper method returns a scalar; transform wraps it ---

    // Add: one-doc eviction on getDocument via scalar-returning builder, plus a
    // full flush of getDocumentCount because the count changed.
    @CacheInvalidate(targets = {
        @Invalidation(method = "getDocument",      keyBuilder = "docKey"),
        @Invalidation(method = "getDocumentCount", allEntries = true)
    })
    public void addDocument(Document doc) {
        db.put(doc.id, doc.content);
    }

    // Private instance helper — must be invoked via INVOKESPECIAL by the transform.
    private Object docKey(Document doc) {
        return doc.id;
    }

    // --- KEY_BUILDER mode: helper returns Object[] — used verbatim as args tuple ---

    @CacheInvalidate(targets = {
        @Invalidation(method = "getDocument", keyBuilder = "explicitKey", keyBuilderArgs = {0})
    })
    public void touchDocument(int id) {
        // no-op; exists solely to verify the Object[] return path.
    }

    private Object[] explicitKey(int id) {
        return new Object[]{id};
    }

    // --- KEY_BUILDER mode: static helper, multiple forwarded args, reads a static field ---

    private static final int TENANT = 42;

    @CacheInvalidate(targets = {
        @Invalidation(method = "getTags", keyBuilder = "staticTagKey", keyBuilderArgs = {0})
    })
    public void retag(int id) {
        // real app would write tags; we just invalidate
    }

    private static Object staticTagKey(int id) {
        // Demonstrates: static, returning scalar, reading ambient static state.
        // (We return `id` as-is here; the key shape must still match getTags's
        // single int param.)
        return id + (TENANT * 0);
    }

    // --- Mixed legacy + structured on the same method ---

    // Legacy name-list wipes getTags entirely; structured entry evicts one
    // getDocument row. Both fire on every call.
    @CacheInvalidate(
        value = {"getTags"},
        targets = {
            @Invalidation(method = "getDocument", keys = {0})
        }
    )
    public void markStale(int id) {
        // no-op
    }

    // --- Tenant-scoped cache: compound key (tenantId, docId) ---

    private int currentTenantId = 0;
    public int getTaggedByTenantCalls = 0;

    public void setTenant(int tenantId) { this.currentTenantId = tenantId; }

    @Memoize
    public String taggedByTenant(int tenantId, int docId) {
        getTaggedByTenantCalls++;
        return "t" + tenantId + "/d" + docId;
    }

    // keyBuilder reads ambient instance state (currentTenantId) that is NOT in
    // the enclosing method's parameters. Returns an Object[] tuple matching
    // taggedByTenant's (int, int) signature.
    @CacheInvalidate(targets = {
        @Invalidation(method = "taggedByTenant", keyBuilder = "tenantTagKey")
    })
    public void refreshTenantDoc(int docId) {
        // no-op; the point is the invalidation directive.
    }

    private Object[] tenantTagKey(int docId) {
        return new Object[]{currentTenantId, docId};   // reads ambient state
    }

    // --- Multi-arg keyBuilder with auto-forward (default keyBuilderArgs) ---

    @CacheInvalidate(targets = {
        @Invalidation(method = "taggedByTenant", keyBuilder = "autoForwardKey")
    })
    public void reassignTag(int tenantId, int docId, String reason) {
        // The helper declares 2 params; the transform auto-forwards
        // enclosing args 0 and 1 (tenantId, docId). `reason` is NOT forwarded.
    }

    private Object[] autoForwardKey(int tenantId, int docId) {
        return new Object[]{tenantId, docId};
    }

    // --- Explicit keyBuilderArgs with reordering / subsetting ---

    @CacheInvalidate(targets = {
        // Forward enclosing args in reversed order: docId (index 2) then tenantId (index 1).
        @Invalidation(method = "taggedByTenant",
                      keyBuilder = "reorderedKey",
                      keyBuilderArgs = {2, 1})
    })
    public void auditReorder(String auditLabel, int tenantId, int docId) { }

    private Object[] reorderedKey(int docId, int tenantId) {
        // Builder receives (docId, tenantId) due to keyBuilderArgs reordering.
        // Emits tuple in target-signature order (tenantId, docId).
        return new Object[]{tenantId, docId};
    }

    // --- All three modes combined on one mutator ---

    @CacheInvalidate(targets = {
        @Invalidation(method = "getDocumentCount", allEntries = true),          // FLUSH
        @Invalidation(method = "getDocument",     keys = {0}),                  // KEYS
        @Invalidation(method = "taggedByTenant",  keyBuilder = "tenantTagKey")  // KEY_BUILDER
    })
    public void touchAllThree(int docId) { }

    // --- @CacheInvalidate and @InvalidateCacheEntry on the same method: both run ---

    @CacheInvalidate("getDocumentCount")
    @InvalidateCacheEntry(method = "getDocument", keys = {0})
    public void replaceDocument(int id, String content) {
        db.put(id, content);
    }
}
