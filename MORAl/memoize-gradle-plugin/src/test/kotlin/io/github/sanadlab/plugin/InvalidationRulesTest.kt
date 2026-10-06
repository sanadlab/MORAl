package io.github.sanadlab.plugin

import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Unit tests for how the transform resolves invalidation targets, and for the
 * shape of the generated code (boxing, compute timing, managers).
 */
class InvalidationRulesTest {

    private fun transform(className: String, body: String): ByteArray? =
        JvmBytecodeTransformer.transform(JavaSources.compile(className, body), className)

    private fun node(bytes: ByteArray) = ClassNode().also { ClassReader(bytes).accept(it, 0) }

    private fun ClassNode.method(name: String): MethodNode = methods.first { it.name == name }

    private fun MethodNode.calls(): List<MethodInsnNode> =
        instructions.toArray().filterIsInstance<MethodInsnNode>()

    private fun assertConfigurationError(className: String, body: String, expected: String) {
        val error = assertThrows(MemoizeConfigurationException::class.java) { transform(className, body) }
        assertTrue(error.message, error.message!!.contains(expected))
    }

    // ---------- Unknown or malformed targets fail the build ----------

    @Test
    fun unknownNameInValueFails() = assertConfigurationError("Typo1", """
        public class Typo1 {
            @Memoize public int get(int id) { return id; }
            @CacheInvalidate("gte") public void clear() { }
        }
    """, "Typo1.clear names 'gte'")

    @Test
    fun unknownStructuredTargetFails() = assertConfigurationError("Typo2", """
        public class Typo2 {
            @Memoize public int get(int id) { return id; }
            @CacheInvalidate(targets = {@Invalidation(method = "gte", allEntries = true)})
            public void clear() { }
        }
    """, "Typo2.clear names 'gte'")

    @Test
    fun unknownEntryTargetFails() = assertConfigurationError("Typo3", """
        public class Typo3 {
            @Memoize public int get(int id) { return id; }
            @InvalidateCacheEntry(method = "gte", keys = {0})
            public void forget(int id) { }
        }
    """, "Typo3.forget names 'gte'")

    @Test
    fun keysAndKeyBuilderTogetherFail() = assertConfigurationError("Both", """
        public class Both {
            @Memoize public int get(int id) { return id; }
            @CacheInvalidate(targets = {@Invalidation(method = "get", keys = {0}, keyBuilder = "key")})
            public void forget(int id) { }
            private Object key(int id) { return id; }
        }
    """, "sets both 'keys' and 'keyBuilder'")

    @Test
    fun missingKeyBuilderFails() = assertConfigurationError("NoBuilder", """
        public class NoBuilder {
            @Memoize public int get(int id) { return id; }
            @CacheInvalidate(targets = {@Invalidation(method = "get", keyBuilder = "key")})
            public void forget(int id) { }
        }
    """, "names the keyBuilder 'key'")

    // ---------- Generated code ----------

    @Test
    fun bothAnnotationsOnOneMethodTakeEffect() {
        val bytes = transform("Both2", """
            public class Both2 {
                @Memoize public int get(int id) { return id; }
                @Memoize public int count() { return 0; }
                @CacheInvalidate("count")
                @InvalidateCacheEntry(method = "get", keys = {0})
                public void replace(int id) { }
            }
        """)!!
        val calls = node(bytes).method("replace").calls().map { it.name }
        assertTrue(calls.toString(), "invalidate" in calls)
        assertTrue(calls.toString(), "invalidateEntry" in calls)
    }

    @Test
    fun namedInvalidatorNeverFallsBackToInvalidateAll() {
        val bytes = transform("Named", """
            public class Named {
                @Memoize public int get(int id) { return id; }
                @CacheInvalidate(targets = {@Invalidation(method = "get", keys = {0})})
                public void forget(int id) { }
            }
        """)!!
        val calls = node(bytes).method("forget").calls().map { it.name }
        assertFalse(calls.toString(), "invalidateAll" in calls)
        assertTrue(calls.toString(), "invalidateEntry" in calls)
    }

    @Test
    fun boxingUsesValueOf() {
        val bytes = transform("Boxing", """
            public class Boxing {
                @Memoize public boolean even(int x) { return x % 2 == 0; }
            }
        """)!!
        val even = node(bytes).method("even")
        val newTypes = even.instructions.toArray().filterIsInstance<TypeInsnNode>()
            .filter { it.opcode == Opcodes.NEW }.map { it.desc }
        assertFalse(newTypes.toString(), "java/lang/Integer" in newTypes || "java/lang/Boolean" in newTypes)
        val valueOfOwners = even.calls().filter { it.name == "valueOf" }.map { it.owner }
        assertTrue(valueOfOwners.toString(), "java/lang/Integer" in valueOfOwners)
        assertTrue(valueOfOwners.toString(), "java/lang/Boolean" in valueOfOwners)
    }

    @Test
    fun memoizedMethodTimesTheCompute() {
        val bytes = transform("Timed", """
            public class Timed {
                @Memoize public int twice(int x) { return 2 * x; }
            }
        """)!!
        val calls = node(bytes).method("twice").calls()
        assertTrue(calls.any { it.name == "computeStart" && it.desc == "()J" })
        assertTrue(calls.any {
            it.name == "putInCache" &&
                it.desc == "(Lio/github/sanadlab/runtime/CacheKeyWrapper;Ljava/lang/Object;J)Ljava/lang/Object;"
        })
    }

    @Test
    fun instanceInvalidatorOfStaticCacheAddsNoInstanceManager() {
        val bytes = transform("Shared", """
            public class Shared {
                private static int rate = 1;
                @Memoize public static int scaled(int x) { return x * rate; }
                @CacheInvalidate("scaled") public void setRate(int r) { rate = r; }
            }
        """)!!
        val fields = node(bytes).fields.map { it.name }
        assertTrue(fields.toString(), "__memoStaticCacheManager" in fields)
        assertFalse(fields.toString(), "__memoCacheManager" in fields)
    }

    // ---------- Kotlin interfaces (class files generated with ASM) ----------

    private val kotlinMetadata = "Lkotlin/Metadata;"
    private val memoizeDesc = "Lio/github/sanadlab/annotations/Memoize;"
    private val invalidateDesc = "Lio/github/sanadlab/annotations/CacheInvalidate;"

    /** A Kotlin interface (disable mode): abstract area() with @Memoize, abstract reset() naming [target]. */
    private fun kotlinInterface(target: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "KShape", null, "java/lang/Object", null)
        cw.visitAnnotation(kotlinMetadata, true).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "area", "()I", null, null).apply {
            visitAnnotation(memoizeDesc, false).visitEnd()
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "reset", "()V", null, null).apply {
            visitAnnotation(invalidateDesc, false).apply {
                visitArray("value").apply { visit(null, target); visitEnd() }
                visitEnd()
            }
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun kotlinInterfaceWithValidNamesIsLeftWithoutFields() {
        val bytes = JvmBytecodeTransformer.transform(kotlinInterface("area"), "KShape")!!
        assertTrue(node(bytes).fields.none { it.name.startsWith("__memo") })
    }

    @Test
    fun typoInKotlinInterfaceFails() {
        val error = assertThrows(MemoizeConfigurationException::class.java) {
            JvmBytecodeTransformer.transform(kotlinInterface("aera"), "KShape")
        }
        assertTrue(error.message, error.message!!.contains("KShape.reset names 'aera'"))
    }

    @Test
    fun kotlinInterfaceCopyWithOverriddenTargetIsSkipped() {
        // Like a class that overrides area() without @Memoize: it keeps only Kotlin's
        // copy of reset(), which forwards to KShape$DefaultImpls and names area.
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "KTile", null, "java/lang/Object", arrayOf("KShape"))
        cw.visitAnnotation(kotlinMetadata, true).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "reset", "()V", null, null).apply {
            visitAnnotation(invalidateDesc, false).apply {
                visitArray("value").apply { visit(null, "area"); visitEnd() }
                visitEnd()
            }
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESTATIC, "KShape\$DefaultImpls", "reset", "(LKShape;)V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()

        val bytes = JvmBytecodeTransformer.transform(cw.toByteArray(), "KTile")!!
        val tile = node(bytes)
        // Nothing to clear, so no manager and no invalidateAll() fallback.
        assertTrue(tile.fields.none { it.name.startsWith("__memo") })
        assertTrue(tile.method("reset").calls().none { it.owner == "io/github/sanadlab/runtime/MemoCacheManager" })
    }
}
