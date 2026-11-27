plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tideclock.messmachine"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.shotgunstudio.messmachine"   // Nov 24 2026: the permanent store ID (the code keeps its com.tideclock package)
        minSdk = 26
        targetSdk = 36
        // CI passes the run number so every build installs over the last one
        // MM_BUILD_NUMBER / MM_BUILD_SHA: the cmp branch's big test pins both, so its screens can be compared run to run
        fun env(vararg names: String) = names.firstNotNullOfOrNull { n -> System.getenv(n)?.takeIf { it.isNotBlank() } }
        versionCode = (env("MM_BUILD_NUMBER", "GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (env("MM_BUILD_NUMBER", "GITHUB_RUN_NUMBER") ?: "0")
        buildConfigField("String", "GIT_SHA", "\"" + (env("MM_BUILD_SHA", "GITHUB_SHA") ?: "local").take(7) + "\"")
        // accounts (docs/ACCOUNTS-API.md s5): empty by default, and then the app shows no account feature at all
        fun cloudSetting(env: String, prop: String): String =
            (System.getenv(env)?.takeIf { it.isNotBlank() } ?: (project.findProperty(prop) as String?) ?: "").trim()
        buildConfigField("String", "CLOUD_URL", "\"" + cloudSetting("MM_CLOUD_URL", "mm.cloudUrl") + "\"")
        buildConfigField("String", "CLOUD_KEY", "\"" + cloudSetting("MM_CLOUD_KEY", "mm.cloudKey") + "\"")
        buildConfigField("String", "SITE_HOST", "\"" + cloudSetting("MM_SITE_HOST", "mm.siteHost") + "\"")
        manifestPlaceholders["siteHost"] = cloudSetting("MM_SITE_HOST", "mm.siteHost").ifEmpty { "unset.invalid" }
        // Nov 21 (L4): the AAudio output is a small C library; phones (arm) and the phone test's emulator (x86_64)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    // use the newest NDK the build machine already has, so CI never waits on a download
    (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let { File(it, "ndk").listFiles() }
        ?.map { it.name }?.filter { n -> n.firstOrNull()?.isDigit() == true }
        ?.maxByOrNull { n -> n.split('.').map { it.toLongOrNull() ?: 0L }.fold(0L) { a, b -> a * 100000L + b } }
        ?.let { ndkVersion = it }

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
        // FLAC is already packed (MM-8); storing it as is lets the app read it faster
        noCompress += listOf("wav", "flac")
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

// Nov 24 (CMP step 1): Compose Multiplatform 1.7.0 asks for Android Compose 1.7.1. Hold every Compose library at the
// 1.7.0 the app was tested on (BOM 2024.09.00), so the shared module changes nothing on the phone. Material 3 has
// its own numbers and is left alone.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("androidx.compose.") && requested.group != "androidx.compose.material3" &&
            requested.version?.startsWith("1.7.") == true) useVersion("1.7.0")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    // accounts, Slice C: only for the live websocket to the server (SupabaseApi); everything else is HttpURLConnection
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
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
