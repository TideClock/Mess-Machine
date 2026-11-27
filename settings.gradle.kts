pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MessMachine"
// -Pmm.coreOnly builds just the engine (the iOS app's Mac, with no Android tools)
if (!providers.gradleProperty("mm.coreOnly").isPresent) {
    include(":app")
    // the shared screens (Compose Multiplatform, docs/CMP-PLAN.md)
    include(":ui")
}
// the engine in common Kotlin, shared with iOS and the web (docs/KOTLIN-ENGINE.md)
include(":core")
