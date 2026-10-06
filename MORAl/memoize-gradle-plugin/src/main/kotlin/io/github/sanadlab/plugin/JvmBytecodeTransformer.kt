package io.github.sanadlab.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Standalone bytecode transformer for JVM (non-Android) projects.
 * Applies the same MemoizeClassVisitor transformation used in the AGP pipeline.
 *
 * Returns transformed bytes, or null if the class was not modified (no annotations found).
 */
object JvmBytecodeTransformer {

    /**
     * Transform a single class's bytecode. Returns the transformed bytes,
     * or null if no @Memoize/@CacheInvalidate annotations were found, or if
     * an earlier build already transformed the class.
     */
    fun transform(classBytes: ByteArray, className: String): ByteArray? {
        // Quick check: skip if bytecode doesn't contain our annotation descriptors
        // (avoids expensive parsing for the vast majority of classes)
        val bytesStr = String(classBytes, Charsets.ISO_8859_1)
        if (!bytesStr.contains("io/github/sanadlab/annotations/Memoize") &&
            !bytesStr.contains("io/github/sanadlab/annotations/CacheInvalidate")) {
            return null
        }

        val cr = ClassReader(classBytes)
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)

        // MemoizeClassVisitor writes to a ClassWriter internally and then
        // chains through to the provided nextVisitor. We pass `cw` as the output.
        val visitor = MemoizeClassVisitor(Opcodes.ASM9, cw, className)
        cr.accept(visitor, ClassReader.EXPAND_FRAMES)

        // An earlier build already transformed this class: keep the file as it is.
        if (visitor.alreadyTransformed) return null

        return cw.toByteArray()
    }
}
