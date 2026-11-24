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
include(":app")
// the engine in common Kotlin, shared with iOS and the web (docs/KOTLIN-ENGINE.md)
include(":core")
