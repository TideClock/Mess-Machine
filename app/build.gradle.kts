plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tideclock.messmachine"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tideclock.messmachine"
        minSdk = 26
        targetSdk = 34
        // CI passes the run number so every build installs over the last one
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        buildConfigField("String", "GIT_SHA", "\"" + (System.getenv("GITHUB_SHA") ?: "local").take(7) + "\"")
    }

    signingConfigs {
        // One fixed key in the repo, so a new build installs over the old one without losing your messes.
        // Fine for sideloading to your own phone. Swap in a private key before any store release.
        create("tide") {
            storeFile = file("messmachine-sideload.jks")
            storePassword = "messmachine"
            keyAlias = "messmachine"
            keyPassword = "messmachine"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("tide")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("tide")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // the sounds are already as small as they get; storing them raw lets the app read them faster
        noCompress += listOf("wav")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                // each test class in its own JVM: a crash in one (the random run) cannot leave Compose broken for the other
                it.forkEvery = 1
                // DESIGN_CHECK=report makes the design check (BT-5) report without failing
                it.environment("DESIGN_CHECK", System.getenv("DESIGN_CHECK") ?: "")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

composeCompiler {
    // Every screen takes the one AppState. With strong skipping, a screen whose only input is that
    // same object skips its redraw after a change to the mess (note #6: pads kept showing the old sound).
    @Suppress("DEPRECATION")
    enableStrongSkippingMode.set(false)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    // installs the Baseline Profile (MM-4) when the APK is sideloaded, not only from the Play Store
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // the big test: every screen run on the JVM with real drawing, plus screenshots
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
