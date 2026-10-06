package io.github.sanadlab.plugin

import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Unit tests for annotations on interface and abstract methods in Java code.
 * An interface cannot hold private cache fields, so the transform must never
 * add fields to an interface. Kotlin interfaces are tested end to end in
 * memoize-test-kotlin-jvm (ShapeTest).
 */
class InterfaceMethodsTest {

    private fun transform(className: String, body: String): ByteArray? =
        JvmBytecodeTransformer.transform(JavaSources.compile(className, body), className)

    private fun memoFields(bytes: ByteArray): List<String> {
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        return node.fields.map { it.name }.filter { it.startsWith("__memo") }
    }

    private fun captureStdErr(block: () -> Unit): String {
        val err = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(err, true))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return err.toString()
    }

    @Test
    fun javaDefaultMethodFails() {
        val error = assertThrows(MemoizeConfigurationException::class.java) {
            transform("Shape1", """
                public interface Shape1 {
                    int side();
                    @Memoize default int area() { return side() * side(); }
                }
            """)
        }
        assertTrue(error.message, error.message!!.contains("Shape1.area is an interface default method"))
    }

    @Test
    fun javaDefaultInvalidatorFails() {
        val error = assertThrows(MemoizeConfigurationException::class.java) {
            transform("Shape2", """
                public interface Shape2 {
                    @CacheInvalidate default void reset() { }
                }
            """)
        }
        assertTrue(error.message, error.message!!.contains("Shape2.reset is an interface default method"))
    }

    @Test
    fun abstractInterfaceMethodIsSkippedWithWarning() {
        var bytes: ByteArray? = null
        val err = captureStdErr {
            bytes = transform("Shape3", """
                public interface Shape3 {
                    @Memoize int area();
                }
            """)
        }
        assertEquals(emptyList<String>(), memoFields(bytes!!))
        assertTrue(err, err.contains("Shape3.area is abstract"))
    }

    @Test
    fun abstractClassMethodIsSkippedButConcreteMethodIsInstrumented() {
        var bytes: ByteArray? = null
        val err = captureStdErr {
            bytes = transform("Base", """
                public abstract class Base {
                    @Memoize public abstract int area();
                    @Memoize public int twice(int x) { return 2 * x; }
                }
            """)
        }
        val fields = memoFields(bytes!!)
        assertTrue(fields.toString(), fields.contains("__memoDispatcher_" + MemoizeClassVisitor.methodKey("twice", "(I)I")))
        assertFalse(fields.toString(), fields.any { it.startsWith("__memoDispatcher_area") })
        assertTrue(err, err.contains("Base.area is abstract"))
    }
}
