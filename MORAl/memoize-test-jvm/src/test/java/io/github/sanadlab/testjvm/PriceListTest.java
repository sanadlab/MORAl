package io.github.sanadlab.testjvm;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.Assert.*;

/**
 * Tests for a class with static and instance {@code @Memoize} methods:
 * cache sharing, and invalidation across the static/instance boundary.
 */
public class PriceListTest {

    @Before
    public void setUp() {
        PriceList.resetRate();
    }

    @Test
    public void instancesHaveOwnCachesAndShareStaticCaches() {
        PriceList a = new PriceList();
        PriceList b = new PriceList();
        a.setPrice("tea", 300);
        b.setPrice("tea", 300);

        a.price("tea");
        b.price("tea");
        a.price("tea");
        b.price("tea");
        assertEquals(1, a.priceCalls);
        assertEquals(1, b.priceCalls);

        assertEquals(100, PriceList.convert(100));
        assertEquals(100, PriceList.convert(100));
        assertEquals(1, PriceList.convertCalls);
    }

    @Test
    public void zeroArgStaticMethodIsCached() {
        assertEquals(1.0, PriceList.eurRate(), 0.0);
        assertEquals(1.0, PriceList.eurRate(), 0.0);
        assertEquals(1, PriceList.eurRateCalls);
    }

    @Test
    public void instanceInvalidatorFlushesNamedStaticCaches() {
        PriceList.eurRate();
        PriceList.convert(100);

        new PriceList().applyRate(2.0);

        assertEquals(2.0, PriceList.eurRate(), 0.0);
        assertEquals(200, PriceList.convert(100));
        assertEquals(2, PriceList.eurRateCalls);
        assertEquals(2, PriceList.convertCalls);
    }

    @Test
    public void instanceInvalidateCacheEntryEvictsOneStaticEntry() {
        PriceList.convert(100);
        PriceList.convert(200);

        new PriceList().forgetConversion(100);

        PriceList.convert(100);   // miss
        PriceList.convert(200);   // hit
        assertEquals(3, PriceList.convertCalls);
    }

    @Test
    public void instanceKeyBuilderEvictsOneStaticEntry() {
        PriceList.convert(100);
        PriceList.convert(200);

        new PriceList().recheck(200);

        PriceList.convert(100);   // hit
        PriceList.convert(200);   // miss
        assertEquals(3, PriceList.convertCalls);
    }

    @Test
    public void bareInstanceInvalidatorKeepsStaticCaches() {
        PriceList list = new PriceList();
        PriceList.convert(100);
        list.price("tea");

        list.setPrice("tea", 300);

        assertEquals(300, list.price("tea"));   // miss
        assertEquals(2, list.priceCalls);
        PriceList.convert(100);                 // hit
        assertEquals(1, PriceList.convertCalls);
    }

    @Test
    public void bareStaticInvalidatorKeepsInstanceCaches() {
        PriceList list = new PriceList();
        list.setPrice("tea", 300);
        list.price("tea");
        PriceList.convert(100);

        PriceList.resetRate();    // also sets the static counters to 0

        PriceList.convert(100);   // miss
        assertEquals(1, PriceList.convertCalls);
        list.price("tea");        // hit
        assertEquals(1, list.priceCalls);
    }

    @Test
    public void classHasStaticAndInstanceManagers() throws Exception {
        Field staticManager = PriceList.class.getDeclaredField("__memoStaticCacheManager");
        Field instanceManager = PriceList.class.getDeclaredField("__memoCacheManager");
        assertTrue(Modifier.isStatic(staticManager.getModifiers()));
        assertFalse(Modifier.isStatic(instanceManager.getModifiers()));
    }
}
