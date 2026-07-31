import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

buildscript {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://global.repo.jmix.io/repository/public")
        }
    }
    dependencies {
        val selectedJmixVersion = providers.gradleProperty("certJmixVersion")
            .orElse("2.8.2")
            .get()
        classpath("io.jmix.gradle:jmix-gradle-plugin:$selectedJmixVersion")
    }
}

plugins {
    java
}

apply(plugin = "io.jmix")

group = "org.jmixworkbench.certification"
version = "1.0.0"

val jmixVersion = providers.gradleProperty("certJmixVersion").orElse("2.8.2")
val jmixLine = providers.gradleProperty("certJmixLine").orElse("jmix28")
val springBootVersion = jmixLine.map {
    if (it == "jmix30") "4.0.6" else "3.5.14"
}
val compileJava = providers.gradleProperty("certJavaVersion").orElse("17").map(String::toInt)
val generatedRoot = providers.gradleProperty("certGeneratedRoot")
    .map { file(it) }
    .orElse(layout.projectDirectory.dir("../../plugin/build/compatibility/generated-sources").asFile)

extensions.configure<io.jmix.gradle.JmixExtension>("jmix") {
    bomVersion = jmixVersion.get()
    entitiesEnhancing.enabled = false
}

java {
    toolchain {
        languageVersion.set(compileJava.map(JavaLanguageVersion::of))
    }
}

sourceSets.main {
    java.srcDir(generatedRoot.map { it.resolve("common/java") })
    java.srcDir(generatedRoot.map { it.resolve("${jmixLine.get()}/java") })
    java.include(
        "org/jmixworkbench/certification/integration/**",
        "com/acme/cert/integration/**",
    )
    resources.srcDir(generatedRoot.map { it.resolve("${jmixLine.get()}/resources") })
}

dependencies {
    implementation(platform("io.jmix.bom:jmix-bom:${jmixVersion.get()}"))
    implementation("io.jmix.core:jmix-core-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    if (jmixLine.get() == "jmix30") {
        implementation("org.springframework.boot:spring-boot-starter-kafka:${springBootVersion.get()}")
    } else {
        implementation("org.springframework.kafka:spring-kafka")
    }
    implementation("org.springframework.integration:spring-integration-sftp")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.security:spring-security-oauth2-client")
    if (jmixLine.get() == "jmix30") {
        implementation("org.springframework.boot:spring-boot-http-client:${springBootVersion.get()}")
    }
    implementation(
        "org.springframework.boot:"
            + if (jmixLine.get() == "jmix30") {
                "spring-boot-starter-aspectj:${springBootVersion.get()}"
            } else {
                "spring-boot-starter-aop:${springBootVersion.get()}"
            },
    )
    implementation(
        if (jmixLine.get() == "jmix30") {
            "io.github.resilience4j:resilience4j-spring-boot4:2.4.0"
        } else {
            "io.github.resilience4j:resilience4j-spring-boot3:2.4.0"
        },
    )
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-observation")
    implementation("org.liquibase:liquibase-core")
    if (jmixLine.get() == "jmix30") {
        implementation("tools.jackson.core:jackson-databind")
    } else {
        implementation("com.fasterxml.jackson.core:jackson-databind")
    }
    runtimeOnly("org.postgresql:postgresql:42.7.7")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(compileJava)
    options.encoding = "UTF-8"
}

tasks.register<JavaExec>("certifyIntegrationRuntime") {
    description = "Runs generated connectors against real PostgreSQL, Kafka, and RabbitMQ services."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jmixworkbench.certification.integration.IntegrationRuntimeCertificationApplication")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(compileJava.map(JavaLanguageVersion::of))
        },
    )
    systemProperty("cert.jmix.version", jmixVersion.get())
    listOf(
        "CERT_DB_URL",
        "CERT_DB_USERNAME",
        "CERT_DB_PASSWORD",
        "CERT_KAFKA_BOOTSTRAP",
        "CERT_RABBIT_HOST",
        "CERT_RABBIT_PORT",
        "CERT_TOXIPROXY_URL",
        "CERT_SFTP_HOST",
        "CERT_SFTP_PORT",
        "CERT_SFTP_USERNAME",
        "CERT_SFTP_PASSWORD",
        "CERT_WIREMOCK_URL",
        "CERT_MTLS_URL",
        "CERT_MTLS_HOSTNAME_MISMATCH_URL",
        "CERT_TLS_DIR",
        "CERT_MTLS_PASSWORD",
        "CERT_OAUTH_CLIENT_ID",
        "CERT_OAUTH_CLIENT_SECRET",
        "CERT_EVIDENCE_FILE",
        "CERT_CELL_ID",
    ).forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}
