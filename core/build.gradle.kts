// The engine, in common Kotlin (Nov 23 2026): the same code runs on Android (the jvm target), iOS and the web.
// See docs/KOTLIN-ENGINE.md. The iOS and JS targets are only set up where they can build (a Mac for iOS) or when
// asked for, so the Android build on Linux never needs the Kotlin/Native or Node toolchains.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val onMac = System.getProperty("os.name").startsWith("Mac")
val wantIos = onMac || providers.gradleProperty("mm.ios").isPresent
val wantJs = providers.gradleProperty("mm.js").isPresent

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (wantIos) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            it.binaries.framework { baseName = "MessCore"; isStatic = true }
        }
    }
    if (wantJs) {
        js { browser(); binaries.library() }
    }
    sourceSets.all {
        languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }
}

// The engine test and the shared mess round trip (plain Java programs) run against the Kotlin engine on the JVM.
val mainJvm = kotlin.jvm().compilations.getByName("main")
val mainDeps = mainJvm.runtimeDependencyFiles ?: files()
val testOut = layout.buildDirectory.dir("enginetest")
val compileEngineTest by tasks.registering(JavaCompile::class) {
    source(rootProject.files("tools/EngineTest.java", "shared/fixtures/RoundTrip.java"))
    classpath = files(mainJvm.output.allOutputs, mainDeps)
    destinationDirectory.set(testOut)
    options.release.set(17)
    dependsOn(mainJvm.compileTaskProvider)
}
tasks.register<JavaExec>("engineTest") {
    group = "verification"
    description = "tools/EngineTest.java against the Kotlin engine"
    dependsOn(compileEngineTest)
    classpath = files(testOut, mainJvm.output.allOutputs, mainDeps)
    mainClass.set("EngineTest")
    args(rootProject.file("app/src/main/assets/sounds").path + "/", testOut.get().asFile.path)
}
tasks.register<JavaExec>("roundTrip") {
    group = "verification"
    description = "shared/fixtures/RoundTrip.java against the Kotlin engine"
    dependsOn(compileEngineTest)
    classpath = files(testOut, mainJvm.output.allOutputs, mainDeps)
    mainClass.set("RoundTrip")
    args(rootProject.file("shared/fixtures").path, rootProject.file("app/src/main/assets/sounds").path + "/")
}
