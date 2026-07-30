pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Node plugin 7.1.0 adds its pinned Node.js distribution Ivy repository at project scope.
    // The isolated IntelliJ host builds remain strict with FAIL_ON_PROJECT_REPOS.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jmix-visual-workbench"

includeBuild("hosts/idea253") {
    name = "idea253"
}

includeBuild("hosts/idea262") {
    name = "idea262"
}
