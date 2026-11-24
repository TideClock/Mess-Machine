import org.teavm.gradle.api.JSModuleType
import org.teavm.gradle.api.OptimizationLevel

plugins {
    java
    id("org.teavm") version "0.17.0"
}

dependencies {
    implementation(teavm.libs.jsoApis)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The app's engine sources, copied in by build.sh (with the one change TeaVM needs), plus WebEngine.
sourceSets { main { java { setSrcDirs(listOf("src/main/java", "gen")) } } }

teavm.js {
    mainClass = "com.tideclock.messmachine.engine.WebEngine"
    moduleType = JSModuleType.ES2015
    optimization = OptimizationLevel.AGGRESSIVE
    obfuscated = true
    targetFileName = "engine.js"
}
