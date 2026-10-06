package io.github.sanadlab.testjvm;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for static memoized methods that are called while their class
 * initializes. If the static dispatchers were not ready, class initialization
 * would fail with an ExceptionInInitializerError.
 */
public class StaticInitOrderTest {

    @Test
    public void staticInitializerCallsAreCached() {
        assertEquals(42, StaticInitOrder.FROM_FIELD);
        assertEquals(42, StaticInitOrder.FROM_BLOCK);
        assertEquals(1, StaticInitOrder.doubledCalls);   // the static block call was a hit

        assertEquals(42, StaticInitOrder.doubled(21));
        assertEquals(1, StaticInitOrder.doubledCalls);   // still a hit after initialization
    }

    @Test
    public void enumConstructorCallsAreCached() {
        assertEquals(30, Planet.MERCURY.gravity);
        assertEquals(60, Planet.VENUS.gravity);
        assertEquals(60, Planet.EARTH.gravity);
        assertEquals(2, Planet.gravityCalls);   // EARTH used the entry that VENUS made

        assertEquals(60, Planet.gravityFor(6));
        assertEquals(2, Planet.gravityCalls);
    }
}
