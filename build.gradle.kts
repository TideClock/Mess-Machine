plugins {
    id("com.android.application") version "8.9.1" apply false
    // Nov 23: 2.1.20 for the engine's common atomics (core/, Kotlin Multiplatform)
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.20" apply false
}
