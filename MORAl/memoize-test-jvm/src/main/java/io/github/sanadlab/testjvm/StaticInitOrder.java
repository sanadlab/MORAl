package io.github.sanadlab.testjvm;

import io.github.sanadlab.annotations.Memoize;

/**
 * JVM test subject: a static field initializer and a static block call a
 * memoized method while the class initializes. The transform must set up the
 * static dispatchers at the START of {@code <clinit>}. If it does not, these
 * calls get a null dispatcher and class initialization fails.
 */
public final class StaticInitOrder {

    public static int doubledCalls;

    public static final int FROM_FIELD = doubled(21);
    public static final int FROM_BLOCK;

    static {
        FROM_BLOCK = doubled(21);   // same argument: cache hit
    }

    private StaticInitOrder() { }

    @Memoize
    public static int doubled(int x) {
        doubledCalls++;
        return 2 * x;
    }
}
