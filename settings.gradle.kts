pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // the web build (-Pmm.web, docs/WEB-CMP.md) downloads Node and Yarn through repositories the Kotlin plugin adds
    // to the project, so it lets project repositories through; every other build keeps failing on them
    repositoriesMode.set(if (providers.gradleProperty("mm.web").isPresent) RepositoriesMode.PREFER_PROJECT else RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
