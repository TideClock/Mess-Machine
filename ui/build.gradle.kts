// The screens and the app logic in shared Kotlin (Compose Multiplatform), so the iPhone shows the same UI.
// docs/CMP-PLAN.md. Step 1 (Nov 24 2026): the module exists and is wired into the app; nothing has moved into it yet.
// The folder is ui/ because shared/ already holds the platform contract (PARITY.md, MESS-FORMAT.md).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val onMac = System.getProperty("os.name").startsWith("Mac")
val wantIos = onMac || providers.gradleProperty("mm.ios").isPresent

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (wantIos) {
        iosArm64(); iosSimulatorArm64()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
        }
    }
}

android {
    // not the app's namespace: the app keeps its own R class
    namespace = "com.tideclock.messmachine.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

composeCompiler {
    // the same as the app (app/build.gradle.kts): screens that take the one AppState must not skip their redraw
    @Suppress("DEPRECATION")
    enableStrongSkippingMode.set(false)
}
