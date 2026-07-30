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
val databaseId = providers.gradleProperty("certDbId").orElse("postgres")
val runtimeJava = providers.gradleProperty("certJavaVersion").orElse("17").map(String::toInt)
val compileJava = providers.gradleProperty("certCompileJavaVersion")
    .orElse(providers.gradleProperty("certJavaVersion"))
    .orElse("17")
    .map(String::toInt)
val eclipseLinkTargetDatabase = databaseId.map {
    if (it == "oracle") {
        "org.eclipse.persistence.platform.database.OraclePlatform"
    } else {
        "Auto"
    }
}

extensions.configure<io.jmix.gradle.JmixExtension>("jmix") {
    bomVersion = jmixVersion.get()
    entitiesEnhancing.enabled = true
}

java {
    toolchain {
        languageVersion.set(compileJava.map(JavaLanguageVersion::of))
    }
}

dependencies {
    implementation(platform("io.jmix.bom:jmix-bom:${jmixVersion.get()}"))
    implementation("io.jmix.core:jmix-core-starter")
    implementation("io.jmix.data:jmix-eclipselink-starter")

    runtimeOnly("org.postgresql:postgresql:42.7.7")
    runtimeOnly("com.mysql:mysql-connector-j:9.3.0")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc:12.10.1.jre11")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.8.0.25.04")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(compileJava)
    options.encoding = "UTF-8"
}

fun registerRuntimePhase(
    taskName: String,
    phase: String,
    previousPhase: TaskProvider<JavaExec>? = null,
): TaskProvider<JavaExec> = tasks.register<JavaExec>(taskName) {
    description = "Runs the isolated $phase phase of real Jmix database certification."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.named("classes"))
    previousPhase?.let {
        dependsOn(it)
        mustRunAfter(it)
    }
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jmixworkbench.certification.runtime.RuntimeCertificationApplication")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(runtimeJava.map(JavaLanguageVersion::of))
        },
    )
    systemProperty("cert.jmix.version", jmixVersion.get())
    systemProperty("cert.compile.java.version", compileJava.get())
    systemProperty("cert.eclipselink.target-database", eclipseLinkTargetDatabase.get())
    environment("CERT_PHASE", phase)
    mapOf(
        "CERT_DB_ID" to "certDbId",
        "CERT_DB_URL" to "certDbUrl",
        "CERT_DB_USERNAME" to "certDbUsername",
        "CERT_DB_PASSWORD" to "certDbPassword",
        "CERT_DB_DRIVER" to "certDbDriver",
        "CERT_DB_SCHEMA" to "certDbSchema",
        "CERT_EVIDENCE_FILE" to "certEvidenceFile",
    ).forEach { (name, propertyName) ->
        providers.gradleProperty(propertyName)
            .orElse(providers.environmentVariable(name))
            .orNull
            ?.let { value ->
                environment(name, value)
            }
    }
}

val certifyRuntimeForward = registerRuntimePhase(
    "certifyRuntimeForward",
    "forward",
)
val certifyRuntimeRollback = registerRuntimePhase(
    "certifyRuntimeRollback",
    "rollback",
    certifyRuntimeForward,
)
val certifyRuntimeReapply = registerRuntimePhase(
    "certifyRuntimeReapply",
    "reapply",
    certifyRuntimeRollback,
)

tasks.register("certifyRuntime") {
    description = "Runs isolated Jmix startup, persistence, Liquibase forward, rollback, and reapply phases."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(certifyRuntimeReapply)
}

tasks.register("printDriverClasspath") {
    description = "Prints the selected JDBC driver jar used by installed-plugin live reverse-engineering tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    doLast {
        val databaseId = providers.gradleProperty("certDbId").orNull
            ?: error("Supply -PcertDbId.")
        val marker = when (databaseId) {
            "postgres" -> Regex("""postgresql-[^/]+\.jar$""")
            "mysql" -> Regex("""mysql-connector-j-[^/]+\.jar$""")
            "mariadb" -> Regex("""mariadb-java-client-[^/]+\.jar$""")
            "mssql" -> Regex("""mssql-jdbc-[^/]+\.jar$""")
            "oracle" -> Regex("""ojdbc11-[^/]+\.jar$""")
            else -> error("Unsupported database id $databaseId")
        }
        val driver = sourceSets.main.get().runtimeClasspath.files.singleOrNull {
            marker.containsMatchIn(it.name)
        } ?: error("Resolved runtime classpath has no unique driver for $databaseId.")
        println(driver.absolutePath)
    }
}
