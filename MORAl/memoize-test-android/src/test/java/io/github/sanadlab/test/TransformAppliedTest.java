package io.github.sanadlab.test;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * Checks that the unit tests run against the transformed classes. AGP puts the
 * output of transformDebugClassesWithAsm on the unit-test runtime classpath, so
 * LinkedList has the fields that the plugin adds.
 */
public class TransformAppliedTest {

    @Test
    public void unitTestsSeeTheTransformedClass() throws Exception {
        Field manager = LinkedList.class.getDeclaredField("__memoCacheManager");
        assertTrue(manager.isSynthetic());

        int dispatchers = 0;
        for (Field field : LinkedList.class.getDeclaredFields()) {
            if (field.getName().startsWith("__memoDispatcher_")) dispatchers++;
        }
        assertTrue("expected at least one dispatcher field", dispatchers > 0);
    }
}
