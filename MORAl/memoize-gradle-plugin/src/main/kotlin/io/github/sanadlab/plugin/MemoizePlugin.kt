package io.github.sanadlab.plugin

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

/**
 * Gradle plugin that registers the ASM bytecode transformation for @Memoize annotations.
 *
 * Supports two modes:
 * - Android projects: Uses AGP Instrumentation API (automatic via transformClassesWith)
 * - JVM projects: Registers a post-compilation task that transforms class files in-place
 *
 * Applied via `id("io.github.sanadlab")` in build.gradle.kts.
 */
class MemoizePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Try Android first
        if (applyAndroid(project)) return

        // Fall back to JVM
        applyJvm(project)
    }

    private fun applyAndroid(project: Project): Boolean {
        try {
            val androidComponentsClass = Class.forName("com.android.build.api.variant.AndroidComponentsExtension")
            val ext = project.extensions.findByType(androidComponentsClass) ?: return false

            // Use reflection to avoid compile-time AGP dependency for JVM-only consumers
            val onVariantsMethod = androidComponentsClass.getMethod("onVariants",
                Class.forName("com.android.build.api.variant.VariantSelector"),
                Class.forName("kotlin.jvm.functions.Function1"))

            // Direct API call since we have compileOnly AGP dependency
            @Suppress("UNCHECKED_CAST")
            val androidComponents = ext as com.android.build.api.variant.AndroidComponentsExtension<*, *, *>
            androidComponents.onVariants { variant ->
                variant.instrumentation.transformClassesWith(
                    MemoizeClassVisitorFactory::class.java,
                    com.android.build.api.instrumentation.InstrumentationScope.PROJECT
                ) {}
                variant.instrumentation.setAsmFramesComputationMode(
                    com.android.build.api.instrumentation.FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }
            return true
        } catch (e: ClassNotFoundException) {
            return false
        }
    }

    private fun applyJvm(project: Project) {
        // For JVM projects (Kotlin JVM, Java, etc.), register a task that transforms
        // compiled class files after compilation
        project.afterEvaluate {
            project.tasks.withType(JavaCompile::class.java).configureEach { compileTask ->
                compileTask.doLast {
                    val classesDir = compileTask.destinationDirectory.asFile.get()
                    if (classesDir.exists()) {
                        transformClassesInDirectory(classesDir, project)
                    }
                }
            }

            // Also handle Kotlin compilation output
            project.tasks.findByName("compileKotlin")?.doLast {
                val kotlinClassesDir = project.layout.buildDirectory
                    .dir("classes/kotlin/main").get().asFile
                if (kotlinClassesDir.exists()) {
                    transformClassesInDirectory(kotlinClassesDir, project)
                }
            }
        }
    }

    /**
     * Recursively transforms all .class files in the given directory using the
     * MemoizeClassVisitor. This is the JVM equivalent of AGP's transformClassesWith.
     */
    private fun transformClassesInDirectory(directory: File, project: Project) {
        directory.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { classFile ->
                val className = classFile.relativeTo(directory).path
                    .replace(File.separatorChar, '.')
                    .removeSuffix(".class")

                // Skip framework/runtime classes
                if (className.startsWith("io.github.sanadlab.runtime.") ||
                    className.startsWith("io.github.sanadlab.annotations.")) {
                    return@forEach
                }

                try {
                    val originalBytes = classFile.readBytes()
                    val transformedBytes = JvmBytecodeTransformer.transform(originalBytes, className)
                    if (transformedBytes != null) {
                        classFile.writeBytes(transformedBytes)
                        project.logger.info("MemoizePlugin: Transformed $className")
                    }
                } catch (e: MemoizeConfigurationException) {
                    throw GradleException("MemoizePlugin: ${e.message}", e)
                } catch (e: Exception) {
                    project.logger.warn("MemoizePlugin: Failed to transform $className: ${e.message}")
                }
            }
    }
}
