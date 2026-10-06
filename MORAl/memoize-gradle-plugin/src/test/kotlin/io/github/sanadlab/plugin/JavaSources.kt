package io.github.sanadlab.plugin

import io.github.sanadlab.annotations.Memoize
import org.junit.Assert.assertEquals
import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider

/** Compiles small Java test classes with javac, with the annotations on the classpath. */
object JavaSources {

    private val annotationsJar = File(Memoize::class.java.protectionDomain.codeSource.location.toURI()).path

    /** Compiles one Java class in the default package and returns its bytes. */
    fun compile(className: String, body: String): ByteArray {
        val dir = Files.createTempDirectory("memoize-test")
        val source = dir.resolve("$className.java")
        Files.writeString(source, "import io.github.sanadlab.annotations.*;\n$body")
        val exit = ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-classpath", annotationsJar, "-d", dir.toString(), source.toString()
        )
        assertEquals("javac failed for $className", 0, exit)
        return Files.readAllBytes(dir.resolve("$className.class"))
    }
}
