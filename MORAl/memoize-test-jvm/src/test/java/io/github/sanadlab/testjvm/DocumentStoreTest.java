package io.github.sanadlab.testjvm;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-end tests for {@code @CacheInvalidate(targets = { @Invalidation... })}.
 * Every test populates caches first, performs the mutating call, then checks
 * whether subsequent memoized calls recompute (cache miss) or return the cached
 * value (cache hit) via side-effect counters on {@link DocumentStore}.
 */
public class DocumentStoreTest {

    private DocumentStore store;

    @Before
    public void setUp() {
        store = new DocumentStore();
        store.seed(1, "alpha");
        store.seed(2, "bravo");
        store.seed(3, "charlie");
    }

    // ---------- KEYS mode ----------

    @Test
    public void updateEvictsOnlyTheUpdatedIdFromGetDocument() {
        // Prime caches for multiple ids.
        assertEquals("alpha",   store.getDocument(1));
        assertEquals("bravo",   store.getDocument(2));
        assertEquals("charlie", store.getDocument(3));
        int baseCalls = store.getDocumentCalls;

        // Update only id=2: should evict just that entry.
        store.updateDocument(2, "bravo-v2");

        assertEquals("alpha",    store.getDocument(1));     // hit, no recompute
        assertEquals("bravo-v2", store.getDocument(2));     // miss, recompute
        assertEquals("charlie",  store.getDocument(3));     // hit, no recompute

        // Exactly one recomputation happened after the update.
        assertEquals(baseCalls + 1, store.getDocumentCalls);
    }

    @Test
    public void updateDoesNotTouchUnrelatedCaches() {
        int countBefore = store.getDocumentCount();
        int countCalls = store.getDocumentCountCalls;

        store.updateDocument(1, "alpha-v2");

        assertEquals(countBefore, store.getDocumentCount());        // cache still valid
        assertEquals(countCalls,  store.getDocumentCountCalls);     // not re-invoked
    }

    // ---------- KEY_BUILDER mode (scalar return, auto-wrapped) ----------

    @Test
    public void addDocumentEvictsBuilderKeyedEntryAndFlushesCount() {
        // Prime both caches.
        assertEquals("missing", store.getDocument(42));   // id 42 not seeded yet
        assertEquals(3, store.getDocumentCount());
        int docCallsBefore = store.getDocumentCalls;
        int countCallsBefore = store.getDocumentCountCalls;

        store.addDocument(new DocumentStore.Document(42, "omega"));

        // getDocument(42) should miss (specific entry evicted by docKey).
        assertEquals("omega", store.getDocument(42));
        assertEquals(docCallsBefore + 1, store.getDocumentCalls);

        // getDocumentCount should miss (allEntries=true → full flush).
        assertEquals(4, store.getDocumentCount());
        assertEquals(countCallsBefore + 1, store.getDocumentCountCalls);
    }

    @Test
    public void addDocumentLeavesOtherGetDocumentEntriesAlone() {
        assertEquals("alpha", store.getDocument(1));
        int before = store.getDocumentCalls;

        store.addDocument(new DocumentStore.Document(99, "zulu"));

        assertEquals("alpha", store.getDocument(1));            // unchanged → hit
        assertEquals(before, store.getDocumentCalls);           // no recompute
    }

    // ---------- KEY_BUILDER mode (Object[] return) ----------

    @Test
    public void objectArrayReturningBuilderPassesThroughDirectly() {
        assertEquals("alpha", store.getDocument(1));
        int before = store.getDocumentCalls;

        store.touchDocument(1);

        assertEquals("alpha", store.getDocument(1));           // miss → recompute
        assertEquals(before + 1, store.getDocumentCalls);
    }

    // ---------- KEY_BUILDER mode (static helper) ----------

    @Test
    public void staticKeyBuilderEvictsSpecificEntry() {
        assertEquals("tags(1)", store.getTags(1));
        assertEquals("tags(2)", store.getTags(2));
        int before = store.getTagsCalls;

        store.retag(1);

        assertEquals("tags(1)", store.getTags(1));             // miss → recompute
        assertEquals("tags(2)", store.getTags(2));             // still hit
        assertEquals(before + 1, store.getTagsCalls);
    }

    // ---------- Mixed legacy `value` + structured `targets` ----------

    @Test
    public void legacyAndStructuredCoexist() {
        // Prime both caches.
        assertEquals("tags(5)", store.getTags(5));
        assertEquals("tags(6)", store.getTags(6));
        assertEquals("alpha",   store.getDocument(1));
        int tagsBefore = store.getTagsCalls;
        int docsBefore = store.getDocumentCalls;

        store.markStale(1);

        // Legacy value = {"getTags"} should wipe ALL getTags entries.
        assertEquals("tags(5)", store.getTags(5));
        assertEquals("tags(6)", store.getTags(6));
        assertEquals(tagsBefore + 2, store.getTagsCalls);

        // Structured target for getDocument(id=1) should evict just that entry.
        assertEquals("alpha", store.getDocument(1));
        assertEquals(docsBefore + 1, store.getDocumentCalls);
    }

    // ---------- keyBuilder reading ambient instance state ----------

    @Test
    public void keyBuilderReadsAmbientInstanceState() {
        store.setTenant(1);
        assertEquals("t1/d7", store.taggedByTenant(1, 7));
        assertEquals("t1/d7", store.taggedByTenant(1, 7));     // hit
        int before = store.getTaggedByTenantCalls;

        // refreshTenantDoc builds key (currentTenantId, docId) = (1, 7).
        store.refreshTenantDoc(7);

        assertEquals("t1/d7", store.taggedByTenant(1, 7));      // miss → recompute
        assertEquals(before + 1, store.getTaggedByTenantCalls);
    }

    @Test
    public void keyBuilderReflectsChangedAmbientState() {
        // Prime two tenants' entries.
        store.setTenant(1);
        assertEquals("t1/d7", store.taggedByTenant(1, 7));
        store.setTenant(2);
        assertEquals("t2/d7", store.taggedByTenant(2, 7));
        int before = store.getTaggedByTenantCalls;

        // With tenant=2, refreshTenantDoc(7) evicts (2,7) only — (1,7) survives.
        store.refreshTenantDoc(7);

        assertEquals("t2/d7", store.taggedByTenant(2, 7));     // miss → recompute
        store.setTenant(1);
        assertEquals("t1/d7", store.taggedByTenant(1, 7));     // hit, untouched
        assertEquals(before + 1, store.getTaggedByTenantCalls);
    }

    // ---------- Multi-arg keyBuilder with default (auto-forward) keyBuilderArgs ----------

    @Test
    public void multiArgKeyBuilderAutoForwardsFirstNEnclosingParams() {
        assertEquals("t3/d9",  store.taggedByTenant(3, 9));
        assertEquals("t3/d10", store.taggedByTenant(3, 10));
        int before = store.getTaggedByTenantCalls;

        // reassignTag(tenantId, docId, reason) — autoForwardKey has arity 2,
        // so the transform forwards enclosing args 0,1 and drops `reason`.
        store.reassignTag(3, 9, "unused-reason");

        assertEquals("t3/d9",  store.taggedByTenant(3, 9));    // miss
        assertEquals("t3/d10", store.taggedByTenant(3, 10));   // hit
        assertEquals(before + 1, store.getTaggedByTenantCalls);
    }

    // ---------- Explicit keyBuilderArgs with reordering ----------

    @Test
    public void explicitKeyBuilderArgsReorderEnclosingParams() {
        assertEquals("t5/d11", store.taggedByTenant(5, 11));
        assertEquals("t5/d12", store.taggedByTenant(5, 12));
        int before = store.getTaggedByTenantCalls;

        // auditReorder(String, int tenantId=5, int docId=11) — keyBuilderArgs={2,1}
        // passes (docId, tenantId) into reorderedKey, which emits the target
        // tuple as (tenantId, docId).
        store.auditReorder("label", 5, 11);

        assertEquals("t5/d11", store.taggedByTenant(5, 11));   // miss
        assertEquals("t5/d12", store.taggedByTenant(5, 12));   // hit
        assertEquals(before + 1, store.getTaggedByTenantCalls);
    }

    // ---------- All three modes combined on one mutator ----------

    @Test
    public void allThreeModesFireIndependently() {
        assertEquals(3,       store.getDocumentCount());
        assertEquals("alpha", store.getDocument(1));
        assertEquals("bravo", store.getDocument(2));
        store.setTenant(7);
        assertEquals("t7/d1", store.taggedByTenant(7, 1));
        assertEquals("t7/d2", store.taggedByTenant(7, 2));

        int docCountBefore = store.getDocumentCountCalls;
        int docBefore      = store.getDocumentCalls;
        int tenantBefore   = store.getTaggedByTenantCalls;

        // touchAllThree(1) fires:
        //   FLUSH         getDocumentCount (full wipe)
        //   KEYS {0}      evict getDocument(1) only
        //   KEY_BUILDER   evict taggedByTenant(7, 1) only (currentTenantId=7)
        store.touchAllThree(1);

        assertEquals(3, store.getDocumentCount());
        assertEquals(docCountBefore + 1, store.getDocumentCountCalls);

        assertEquals("alpha", store.getDocument(1));           // miss
        assertEquals("bravo", store.getDocument(2));           // hit
        assertEquals(docBefore + 1, store.getDocumentCalls);

        assertEquals("t7/d1", store.taggedByTenant(7, 1));     // miss
        assertEquals("t7/d2", store.taggedByTenant(7, 2));     // hit
        assertEquals(tenantBefore + 1, store.getTaggedByTenantCalls);
    }

    // ---------- @CacheInvalidate and @InvalidateCacheEntry on one method ----------

    @Test
    public void cacheInvalidateAndInvalidateCacheEntryBothRun() {
        store.getDocumentCount();
        store.getDocument(1);
        store.getDocument(2);
        int countCalls = store.getDocumentCountCalls;
        int docCalls = store.getDocumentCalls;

        store.replaceDocument(1, "alpha-v2");

        assertEquals(3, store.getDocumentCount());             // miss: flushed by @CacheInvalidate
        assertEquals(countCalls + 1, store.getDocumentCountCalls);
        assertEquals("alpha-v2", store.getDocument(1));        // miss: evicted by @InvalidateCacheEntry
        assertEquals("bravo", store.getDocument(2));           // hit
        assertEquals(docCalls + 1, store.getDocumentCalls);
    }
}
