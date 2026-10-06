package io.github.sanadlab.testkotlin

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Tests for @JvmStatic functions in a companion object. Kotlin callers use
 * Temperature.Companion. Java callers use the static bridge in Temperature,
 * which these tests call through reflection.
 */
class TemperatureTest {

    private val bridge = Temperature::class.java.getMethod("toFahrenheit", Int::class.javaPrimitiveType)

    @Before
    fun setUp() {
        Temperature.resetTemperature()
    }

    @Test
    fun jvmStaticCompanionFunctionIsCached() {
        assertEquals(212.0, Temperature.toFahrenheit(100), 0.0)
        assertEquals(212.0, Temperature.toFahrenheit(100), 0.0)
        assertEquals(1, Temperature.toFahrenheitCalls)
    }

    @Test
    fun bridgeAndCompanionShareOneCache() {
        assertTrue(Modifier.isStatic(bridge.modifiers))
        assertEquals(212.0, bridge.invoke(null, 100) as Double, 0.0)
        assertEquals(212.0, Temperature.toFahrenheit(100), 0.0)
        assertEquals(212.0, bridge.invoke(null, 100) as Double, 0.0)
        assertEquals(1, Temperature.toFahrenheitCalls)
    }

    @Test
    fun companionInvalidationIsSeenThroughTheBridge() {
        assertEquals(212.0, bridge.invoke(null, 100) as Double, 0.0)
        Temperature.setOffset(0.0)
        // If the bridge had its own cache, setOffset would not clear it,
        // and this call would return the old value 212.0.
        assertEquals(180.0, bridge.invoke(null, 100) as Double, 0.0)
    }

    @Test
    fun bareJvmStaticInvalidatorFlushesCompanionCache() {
        Temperature.toFahrenheit(0)
        Temperature.resetTemperature()   // also sets the counter to 0
        Temperature.toFahrenheit(0)
        assertEquals(1, Temperature.toFahrenheitCalls)
    }

    @Test
    fun onlyTheCompanionIsTransformed() {
        assertTrue(Temperature::class.java.declaredFields.none { it.name.startsWith("__memo") })
        assertNotNull(Temperature.Companion::class.java.getDeclaredField("__memoCacheManager"))
    }
}
