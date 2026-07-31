pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://global.repo.jmix.io/repository/public")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven {
            url = uri("https://global.repo.jmix.io/repository/public")
        }
    }
}

rootProject.name = "jmix-integration-runtime-certification"
