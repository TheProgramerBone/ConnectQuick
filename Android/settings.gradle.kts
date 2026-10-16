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

rootProject.name = "ConnectQuickController"
// Platform-independent protocol/crypto/session/feature logic, built and unit-tested on plain JVM.
includeBuild("controller-core")
include(":app")
