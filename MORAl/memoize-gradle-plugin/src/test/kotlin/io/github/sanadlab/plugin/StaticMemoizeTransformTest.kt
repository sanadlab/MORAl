package io.github.sanadlab.plugin

import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Unit tests for the static-method rules of the transform. Each test compiles
 * a small Java class with javac and gives its bytes to JvmBytecodeTransformer.
 * The integration projects cannot hold these cases, because they stop the build.
 */
class StaticMemoizeTransformTest {

    private fun transform(className: String, body: String): ByteArray? =
        JvmBytecodeTransformer.transform(JavaSources.compile(className, body), className)

    private fun assertConfigurationError(className: String, body: String, expected: String) {
        val error = assertThrows(MemoizeConfigurationException::class.java) { transform(className, body) }
        assertTrue(error.message, error.message!!.contains(expected))
    }

    @Test
    fun classWithoutStaticInitializerGetsOne() {
        val bytes = transform("Util", """
            public class Util {
                @Memoize public static int twice(int x) { return 2 * x; }
            }
        """)!!
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }

        assertTrue(node.methods.any { it.name == "<clinit>" })
        val memoFields = node.fields.filter { it.name.startsWith("__memo") }
        assertEquals(
            setOf("__memoStaticCacheManager", "__memoDispatcher_" + MemoizeClassVisitor.methodKey("twice", "(I)I")),
            memoFields.map { it.name }.toSet()
        )
        assertTrue(memoFields.all { (it.access and Opcodes.ACC_STATIC) != 0 })
    }

    @Test
    fun staticMemoizeInInterfaceFails() = assertConfigurationError("Shape", """
        public interface Shape {
            @Memoize static int sides(int n) { return n; }
        }
    """, "static interface methods are not supported")

    @Test
    fun staticLegacyInvalidatorOnInstanceCacheFails() = assertConfigurationError("Store1", """
        public class Store1 {
            @Memoize public int get(int id) { return id; }
            @CacheInvalidate("get") public static void clear() { }
        }
    """, "Store1.clear is static, so it cannot invalidate the per-instance cache of get")

    @Test
    fun staticStructuredInvalidatorOnInstanceCacheFails() = assertConfigurationError("Store2", """
        public class Store2 {
            @Memoize public int get(int id) { return id; }
            @CacheInvalidate(targets = {@Invalidation(method = "get", allEntries = true)})
            public static void clear() { }
        }
    """, "Store2.clear is static, so it cannot invalidate the per-instance cache of get")

    @Test
    fun staticInvalidateCacheEntryOnInstanceCacheFails() = assertConfigurationError("Store3", """
        public class Store3 {
            @Memoize public int get(int id) { return id; }
            @InvalidateCacheEntry(method = "get", keys = {0})
            public static void forget(int id) { }
        }
    """, "Store3.forget is static, so it cannot invalidate the per-instance cache of get")

    @Test
    fun staticInvalidatorWithInstanceKeyBuilderFails() = assertConfigurationError("Store4", """
        public class Store4 {
            @Memoize public static int get(int id) { return id; }
            @CacheInvalidate(targets = {@Invalidation(method = "get", keyBuilder = "key")})
            public static void forget(int id) { }
            private Object key(int id) { return id; }
        }
    """, "Store4.forget is static, so it cannot call the instance keyBuilder 'key'")

    @Test
    fun staticMethodWithThreadSafetyNoneWarns() {
        val err = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(err, true))
        try {
            transform("Fast", """
                public class Fast {
                    @Memoize(threadSafety = ThreadSafety.NONE) public static int f(int x) { return x; }
                }
            """)
        } finally {
            System.setErr(original)
        }
        assertTrue(err.toString(), err.toString().contains("Fast.f is static and uses ThreadSafety.NONE"))
    }
}
