plugins {
    `java-gradle-plugin`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
