package io.github.sanadlab.testjvm;

import io.github.sanadlab.runtime.LogLevel;
import io.github.sanadlab.runtime.MemoCacheManager;
import io.github.sanadlab.runtime.MemoDispatcher;
import io.github.sanadlab.runtime.MemoLogger;
import io.github.sanadlab.runtime.MemoMetrics;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests that the generated code records compute and lookup times in
 * {@link MemoMetrics} when timing is on (INFO logging), and records nothing
 * when it is off.
 */
public class ComputeMetricsTest {

    private LogLevel oldLevel;

    @Before
    public void saveLevel() {
        oldLevel = MemoLogger.getLevel();
    }

    @After
    public void restoreLevel() {
        MemoLogger.setLevel(oldLevel);
    }

    /** The metrics of Calculator.compute(int), read through the private manager field. */
    private static MemoMetrics computeMetrics(Calculator calc) throws Exception {
        Field field = Calculator.class.getDeclaredField("__memoCacheManager");
        field.setAccessible(true);
        MemoCacheManager manager = (MemoCacheManager) field.get(calc);
        for (Map.Entry<String, MemoDispatcher> e : manager.getDispatchers().entrySet()) {
            if (e.getValue().getMethodName().equals("compute_ddad9")) return e.getValue().getMetrics();
        }
        throw new AssertionError("no dispatcher for compute(int)");
    }

    @Test
    public void timingOnRecordsComputeAndLookup() throws Exception {
        MemoLogger.setLevel(LogLevel.INFO);
        Calculator calc = new Calculator();

        calc.compute(5);   // miss: one compute sample
        calc.compute(5);   // hit: one lookup sample

        MemoMetrics metrics = computeMetrics(calc);
        assertEquals(1, metrics.getComputeSamples());
        assertEquals(1, metrics.getLookupSamples());
        assertTrue(metrics.getTotalComputeNanos() > 0);
    }

    @Test
    public void timingOffRecordsNothing() throws Exception {
        MemoLogger.setLevel(LogLevel.OFF);
        Calculator calc = new Calculator();

        calc.compute(5);
        calc.compute(5);

        MemoMetrics metrics = computeMetrics(calc);
        assertEquals(0, metrics.getComputeSamples());
        assertEquals(0, metrics.getLookupSamples());
    }
}
