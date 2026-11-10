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
    }
    androidResources {
        // the sounds are already as small as they get; storing them raw lets the app read them faster
        noCompress += listOf("wav")
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
