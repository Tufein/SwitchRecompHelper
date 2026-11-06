plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Target Java 17 bytecode so the Android app (also 17) can consume it, whichever JDK runs the build.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // zstd (NCZ). compileOnly: the app supplies the Android (AAR) build with its native libs; tests use the JVM jar.
    compileOnly("com.github.luben:zstd-jni:1.5.6-4")
    testImplementation("com.github.luben:zstd-jni:1.5.6-4")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
