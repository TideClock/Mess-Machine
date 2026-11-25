plugins {
    id("com.android.application") version "8.9.1" apply false
    id("com.android.library") version "8.9.1" apply false
    // Nov 23: 2.1.20 for the engine's common atomics (core/, Kotlin Multiplatform)
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.20" apply false
    // Nov 24: Compose Multiplatform for the shared screens (ui/, docs/CMP-PLAN.md). 1.7.0 is built on Compose 1.7,
    // the Compose the app uses (BOM 2024.09.00); app/build.gradle.kts holds the Android side at exactly 1.7.0.
    id("org.jetbrains.compose") version "1.7.0" apply false
}
