package io.github.sanadlab.testjvm;

import io.github.sanadlab.annotations.Memoize;

/**
 * JVM test subject: the enum constructor calls a static memoized method of the
 * same enum. Java builds the constants at the start of {@code <clinit>}, so the
 * static dispatchers must exist before the constants.
 */
public enum Planet {
    MERCURY(3), VENUS(6), EARTH(6);

    // No initializer: it would run after the constants and reset the count.
    public static int gravityCalls;

    public final int gravity;

    Planet(int size) {
        this.gravity = gravityFor(size);
    }

    @Memoize
    public static int gravityFor(int size) {
        gravityCalls++;
        return size * 10;
    }
}
