plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    jvmToolchain(17)
}

intellij {
    version.set(providers.gradleProperty("platformVersion"))
    type.set(providers.gradleProperty("platformType"))
    plugins.set(listOf())
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("251.*")
    }

    buildSearchableOptions {
        enabled = false
    }

    processResources {
        dependsOn(":copyWebUi")
    }
}

tasks.register<Copy>("copyWebUi") {
    from(layout.projectDirectory.dir("../webui/dist"))
    into(layout.buildDirectory.dir("resources/main/webui"))
}
