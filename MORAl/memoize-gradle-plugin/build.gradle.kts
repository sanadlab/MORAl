plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

group = "io.github.sanadlab"
version = "0.1.0"

dependencies {
    implementation(project(":memoize-annotations"))
    compileOnly(libs.agp.api)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.util)
    implementation("org.ow2.asm:asm-tree:${libs.versions.asm.get()}")
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    plugins {
        create("memoize") {
            id = "io.github.sanadlab"
            implementationClass = "io.github.sanadlab.plugin.MemoizePlugin"
        }
    }
}
