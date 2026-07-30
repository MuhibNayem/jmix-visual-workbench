package org.jmixworkbench.project

import java.nio.file.Path
import java.util.Locale

enum class JmixProjectTemplateKind(
    val displayName: String,
) {
    APPLICATION("Jmix application"),
    ADDON("Jmix add-on"),
    COMPOSITE("Composite Jmix project"),
}

enum class JmixProjectLanguage(
    val displayName: String,
) {
    JAVA("Java"),
    KOTLIN("Kotlin"),
}

enum class JmixProjectUiKind(
    val displayName: String,
) {
    HEADLESS("Headless / service"),
    FLOW_UI("FlowUI web application"),
}

data class JmixProjectVersion(
    val jmixVersion: String,
    val supportedJavaVersions: Set<Int>,
    val gradleVersion: String,
    val gradleDistributionSha256: String,
    val kotlinVersion: String,
    val compileJavaVersion: Int? = null,
) {
    fun effectiveCompileJavaVersion(runtimeJavaVersion: Int): Int =
        compileJavaVersion ?: runtimeJavaVersion
}

data class JmixProjectTemplateRequest(
    val projectName: String,
    val groupId: String,
    val artifactId: String,
    val basePackage: String,
    val projectId: String,
    val jmixVersion: String,
    val javaVersion: Int,
    val templateKind: JmixProjectTemplateKind = JmixProjectTemplateKind.APPLICATION,
    val language: JmixProjectLanguage = JmixProjectLanguage.JAVA,
    val uiKind: JmixProjectUiKind = JmixProjectUiKind.HEADLESS,
    val locales: List<String> = listOf("en"),
    val useMavenLocal: Boolean = false,
    val additionalRepositories: List<String> = emptyList(),
)

data class GeneratedProjectFile(
    val relativePath: String,
    val content: String,
    val executable: Boolean = false,
)

data class GeneratedProjectResource(
    val relativePath: String,
    val classpathResource: String,
    val executable: Boolean = false,
)

class GeneratedProjectBinaryFile(
    val relativePath: String,
    content: ByteArray,
    val executable: Boolean = false,
) {
    private val bytes: ByteArray = content.copyOf()

    val content: ByteArray
        get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is GeneratedProjectBinaryFile &&
            relativePath == other.relativePath &&
            executable == other.executable &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = relativePath.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + executable.hashCode()
        return result
    }

    override fun toString(): String =
        "GeneratedProjectBinaryFile(relativePath=$relativePath, bytes=${bytes.size}, executable=$executable)"
}

data class GeneratedJmixProject(
    val files: List<GeneratedProjectFile>,
    val resources: List<GeneratedProjectResource>,
    val binaryFiles: List<GeneratedProjectBinaryFile> = emptyList(),
)

class JmixProjectTemplateValidationException(
    val issues: List<String>,
) : IllegalArgumentException(issues.joinToString(separator = "\n"))

/**
 * Produces deterministic, dependency-pinned project sources without touching the filesystem.
 *
 * The generator deliberately accepts only versions that have passed the repository's runtime
 * certification matrix. Unknown Jmix/Java combinations fail closed instead of producing a project
 * that merely looks plausible.
 */
object JmixProjectTemplateGenerator {
    val certifiedVersions: List<JmixProjectVersion> = listOf(
        JmixProjectVersion(
            jmixVersion = "2.8.2",
            supportedJavaVersions = setOf(17, 21),
            gradleVersion = "8.14.4",
            gradleDistributionSha256 =
                "f1771298a70f6db5a29daf62378c4e18a17fc33c9ba6b14362e0cdf40610380d",
            kotlinVersion = "2.4.0",
        ),
        JmixProjectVersion(
            jmixVersion = "3.0.0",
            supportedJavaVersions = setOf(21, 25),
            gradleVersion = "9.5.1",
            gradleDistributionSha256 =
                "bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f",
            kotlinVersion = "2.4.0",
            compileJavaVersion = 21,
        ),
    )

    private val projectNamePattern = Regex("""[A-Za-z][A-Za-z0-9_ -]{0,79}""")
    private val artifactPattern = Regex("""[a-z][a-z0-9-]{0,62}""")
    private val projectIdPattern = Regex("""[a-z][a-z0-9]{0,6}""")
    private val packageSegmentPattern = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val localePattern = Regex("""[a-z]{2,3}(?:[_-][A-Z]{2})?""")

    fun generate(request: JmixProjectTemplateRequest): GeneratedJmixProject {
        val normalized = normalize(request)
        validate(normalized)
        val version = certifiedVersions.single { it.jmixVersion == normalized.jmixVersion }
        val files = when (normalized.templateKind) {
            JmixProjectTemplateKind.APPLICATION -> applicationFiles(normalized, version)
            JmixProjectTemplateKind.ADDON -> addonFiles(normalized, version)
            JmixProjectTemplateKind.COMPOSITE -> compositeFiles(normalized, version)
        }
        validateGeneratedPaths(files.map(GeneratedProjectFile::relativePath) + wrapperResources.map {
            it.relativePath
        })
        return GeneratedJmixProject(
            files = files.sortedBy(GeneratedProjectFile::relativePath),
            resources = wrapperResources.sortedBy(GeneratedProjectResource::relativePath),
        )
    }

    fun validate(request: JmixProjectTemplateRequest) {
        val issues = mutableListOf<String>()
        if (!projectNamePattern.matches(request.projectName)) {
            issues += "Project name must start with a Latin letter and contain at most 80 letters, digits, spaces, '_' or '-'."
        }
        if (!artifactPattern.matches(request.artifactId)) {
            issues += "Artifact ID must start with a lowercase letter and contain only lowercase letters, digits or '-'."
        }
        if (!isValidPackage(request.groupId)) {
            issues += "Group ID must be a valid Java package."
        }
        if (!isValidPackage(request.basePackage)) {
            issues += "Base package must be a valid Java package."
        }
        if (!projectIdPattern.matches(request.projectId)) {
            issues += "Project ID must be 1-7 lowercase letters or digits and start with a letter."
        }
        val version = certifiedVersions.singleOrNull { it.jmixVersion == request.jmixVersion }
        if (version == null) {
            issues += "Jmix ${request.jmixVersion} is not in the certified project-generation matrix."
        } else if (request.javaVersion !in version.supportedJavaVersions) {
            issues += "Jmix ${request.jmixVersion} is certified only for Java ${version.supportedJavaVersions.sorted().joinToString("/")}."
        }
        if (request.locales.isEmpty()) {
            issues += "At least one application locale is required."
        }
        request.locales.forEach { locale ->
            if (!localePattern.matches(locale)) {
                issues += "Invalid locale '$locale'; use forms such as en, bn or en_US."
            }
        }
        request.additionalRepositories.forEach { repository ->
            val uri = runCatching { java.net.URI(repository) }.getOrNull()
            if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
                uri.userInfo != null || uri.query != null || uri.fragment != null
            ) {
                issues += "Repository '$repository' must be an HTTPS base URL without credentials, query or fragment."
            }
        }
        if (issues.isNotEmpty()) {
            throw JmixProjectTemplateValidationException(issues.distinct())
        }
    }

    private fun normalize(request: JmixProjectTemplateRequest): JmixProjectTemplateRequest =
        request.copy(
            projectName = request.projectName.trim(),
            groupId = request.groupId.trim(),
            artifactId = request.artifactId.trim(),
            basePackage = request.basePackage.trim(),
            projectId = request.projectId.trim(),
            jmixVersion = request.jmixVersion.trim(),
            locales = request.locales.map(String::trim).filter(String::isNotEmpty).distinct(),
            additionalRepositories = request.additionalRepositories
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct(),
        )

    private fun applicationFiles(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        prefix: String = "",
    ): List<GeneratedProjectFile> {
        val packagePath = request.basePackage.replace('.', '/')
        val classStem = request.projectName.toJavaClassStem()
        val applicationClass = if (classStem.endsWith("Application")) {
            classStem
        } else {
            "${classStem}Application"
        }
        val moduleId = "${request.basePackage}.${request.projectId}"
        val compileJava = version.effectiveCompileJavaVersion(request.javaVersion)
        val commonFiles = listOf(
            file(prefix, ".gitignore", commonGitignore()),
            file(prefix, "README.md", applicationReadme(request, version, applicationClass)),
            file(prefix, "settings.gradle.kts", settingsFile(request.projectName)),
            file(prefix, "gradle.properties", gradleProperties(request.javaVersion)),
            file(prefix, "build.gradle.kts", applicationBuild(request, version, applicationClass, compileJava)),
            wrapperProperties(prefix, version),
        )
        val applicationFiles = when (request.uiKind) {
            JmixProjectUiKind.HEADLESS -> {
                val sourceExtension = request.language.sourceExtension
                val sourceRoot = request.language.sourceRoot
                listOf(
                    file(
                        prefix,
                        "src/main/$sourceRoot/$packagePath/$applicationClass.$sourceExtension",
                        applicationSource(
                            request = request,
                            version = version,
                            applicationClass = applicationClass,
                            moduleId = moduleId,
                        ),
                    ),
                    file(
                        prefix,
                        "src/main/resources/application.properties",
                        applicationProperties(request),
                    ),
                    file(
                        prefix,
                        "src/main/resources/$packagePath/liquibase/changelog.xml",
                        liquibaseChangelog(request),
                    ),
                    file(
                        prefix,
                        "src/test/$sourceRoot/$packagePath/${applicationClass}Test.$sourceExtension",
                        applicationTestSource(
                            request = request,
                            applicationClass = applicationClass,
                        ),
                    ),
                )
            }
            JmixProjectUiKind.FLOW_UI -> JmixFlowUiProjectTemplate.files(
                request = request,
                version = version,
                applicationClass = applicationClass,
                prefix = prefix,
            )
        }
        return (commonFiles + applicationFiles).sortedBy(GeneratedProjectFile::relativePath)
    }

    private fun addonFiles(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
    ): List<GeneratedProjectFile> {
        val packagePath = request.basePackage.replace('.', '/')
        val classStem = request.projectName.toJavaClassStem()
        val moduleClass = "${classStem}Module"
        val moduleId = "${request.basePackage}.${request.projectId}"
        val compileJava = version.effectiveCompileJavaVersion(request.javaVersion)
        val sourceRoot = request.language.sourceRoot
        val sourceExtension = request.language.sourceExtension
        return listOf(
            GeneratedProjectFile(".gitignore", commonGitignore()),
            GeneratedProjectFile("README.md", addonReadme(request, version)),
            GeneratedProjectFile("settings.gradle.kts", settingsFile(request.projectName)),
            GeneratedProjectFile("gradle.properties", gradleProperties(request.javaVersion)),
            GeneratedProjectFile("build.gradle.kts", addonBuild(request, version, compileJava)),
            GeneratedProjectFile(
                "src/main/$sourceRoot/$packagePath/$moduleClass.$sourceExtension",
                addonModuleSource(request, moduleClass, moduleId),
            ),
            GeneratedProjectFile(
                "src/test/$sourceRoot/$packagePath/${moduleClass}Test.$sourceExtension",
                addonModuleTestSource(request, moduleClass),
            ),
            wrapperProperties("", version),
        ).sortedBy(GeneratedProjectFile::relativePath)
    }

    private fun compositeFiles(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
    ): List<GeneratedProjectFile> {
        val applicationRequest = request.copy(
            projectName = "${request.projectName} Application",
            artifactId = "${request.artifactId}-app",
            projectId = request.projectId.take(6) + "a",
            templateKind = JmixProjectTemplateKind.APPLICATION,
        )
        val addonRequest = request.copy(
            projectName = "${request.projectName} Shared",
            artifactId = "${request.artifactId}-shared",
            basePackage = "${request.basePackage}.shared",
            projectId = request.projectId.take(6) + "s",
            templateKind = JmixProjectTemplateKind.ADDON,
            uiKind = JmixProjectUiKind.HEADLESS,
        )
        val root = listOf(
            GeneratedProjectFile(".gitignore", commonGitignore()),
            GeneratedProjectFile("README.md", compositeReadme(request, version)),
            GeneratedProjectFile(
                "settings.gradle.kts",
                """
                rootProject.name = "${escapeKotlin(request.projectName)}"
                include("application", "shared")
                """.trimIndent() + "\n",
            ),
            GeneratedProjectFile(
                "build.gradle.kts",
                """
                plugins {
                    base
                }

                extra["jmixCompositeProjectRoot"] = true
                """.trimIndent() + "\n",
            ),
            GeneratedProjectFile("gradle.properties", gradleProperties(request.javaVersion)),
            wrapperProperties("", version),
        )
        val application = applicationFiles(applicationRequest, version, "application/")
            .filterNot {
                it.relativePath.endsWith("gradle-wrapper.properties") ||
                    it.relativePath.endsWith(".gitignore") ||
                    it.relativePath.endsWith("settings.gradle.kts") ||
                    it.relativePath.endsWith("gradle.properties")
            }
            .map { generated ->
                if (generated.relativePath == "application/build.gradle.kts") {
                    generated.copy(
                        content = connectSharedModule(generated.content),
                    )
                } else {
                    generated
                }
            }
        val addon = addonFiles(addonRequest, version)
            .filterNot {
                it.relativePath in setOf(
                    ".gitignore",
                    "README.md",
                    "settings.gradle.kts",
                    "gradle.properties",
                    "gradle/wrapper/gradle-wrapper.properties",
                )
            }
            .map { it.copy(relativePath = "shared/${it.relativePath}") }
        return (root + application + addon).sortedBy(GeneratedProjectFile::relativePath)
    }

    private fun applicationBuild(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        applicationClass: String,
        compileJava: Int,
    ): String = when (request.uiKind) {
        JmixProjectUiKind.HEADLESS ->
            headlessApplicationBuild(request, version, applicationClass, compileJava)
        JmixProjectUiKind.FLOW_UI ->
            flowUiApplicationBuild(request, version, applicationClass, compileJava)
    }

    private fun headlessApplicationBuild(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        applicationClass: String,
        compileJava: Int,
    ): String = """
        import org.gradle.api.tasks.JavaExec
        import org.gradle.api.tasks.compile.JavaCompile
        import org.gradle.jvm.toolchain.JavaLanguageVersion

        buildscript {
            repositories {
                mavenCentral()
                __BUILD_REPOSITORIES__
            }
            dependencies {
                classpath("io.jmix.gradle:jmix-gradle-plugin:${version.jmixVersion}")
            }
        }

        plugins {
            java
            application
        __LANGUAGE_PLUGINS__
        }

        apply(plugin = "io.jmix")

        group = "${escapeKotlin(request.groupId)}"
        version = "1.0.0-SNAPSHOT"

        repositories {
            mavenCentral()
            __PROJECT_REPOSITORIES__
        }

        extensions.configure<io.jmix.gradle.JmixExtension>("jmix") {
            bomVersion = "${version.jmixVersion}"
            entitiesEnhancing.enabled = true
        }

        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(${request.javaVersion}))
            }
        }

        application {
            mainClass.set("${request.basePackage}.$applicationClass")
        }

        dependencies {
            implementation(platform("io.jmix.bom:jmix-bom:${version.jmixVersion}"))
            implementation("io.jmix.core:jmix-core-starter")
            implementation("io.jmix.data:jmix-eclipselink-starter")
            runtimeOnly("org.hsqldb:hsqldb")
        __LANGUAGE_DEPENDENCIES__

            testImplementation("org.junit.jupiter:junit-jupiter")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set($compileJava)
            options.encoding = "UTF-8"
        }

        __LANGUAGE_CONFIGURATION__

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        tasks.named<JavaExec>("run") {
            systemProperty("spring.profiles.active", "dev")
        }
    """.trimIndent()
        .replace(
            "        __BUILD_REPOSITORIES__",
            repositoryBlocks(request, indent = "        "),
        )
        .replace(
            "    __PROJECT_REPOSITORIES__",
            repositoryBlocks(request, indent = "    "),
        )
        .replace(
            "__LANGUAGE_PLUGINS__",
            languagePlugins(request, version, indent = "    "),
        )
        .replace(
            "__LANGUAGE_DEPENDENCIES__",
            languageDependencies(request, indent = "    "),
        )
        .replace(
            "__LANGUAGE_CONFIGURATION__",
            languageConfiguration(request, request.javaVersion, compileJava),
        ) + "\n"

    private fun flowUiApplicationBuild(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        applicationClass: String,
        compileJava: Int,
    ): String = """
        import org.gradle.api.tasks.compile.JavaCompile
        import org.gradle.jvm.toolchain.JavaLanguageVersion
        import org.springframework.boot.gradle.tasks.run.BootRun

        buildscript {
            repositories {
                mavenCentral()
                __BUILD_REPOSITORIES__
            }
            dependencies {
                classpath("io.jmix.gradle:jmix-gradle-plugin:${version.jmixVersion}")
            }
        }

        plugins {
            java
        __LANGUAGE_PLUGINS__
        }

        apply(plugin = "io.jmix")
        apply(plugin = "org.springframework.boot")
        apply(plugin = "com.vaadin")

        group = "${escapeKotlin(request.groupId)}"
        version = "1.0.0-SNAPSHOT"

        repositories {
            mavenCentral()
            __PROJECT_REPOSITORIES__
        }

        extensions.configure<io.jmix.gradle.JmixExtension>("jmix") {
            bomVersion = "${version.jmixVersion}"
            entitiesEnhancing.enabled = true
        }

        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(${request.javaVersion}))
            }
        }

        dependencies {
            implementation(platform("io.jmix.bom:jmix-bom:${version.jmixVersion}"))
            implementation("io.jmix.core:jmix-core-starter")
            implementation("io.jmix.data:jmix-eclipselink-starter")
            implementation("io.jmix.security:jmix-security-starter")
            implementation("io.jmix.security:jmix-security-flowui-starter")
            implementation("io.jmix.security:jmix-security-data-starter")
            implementation("io.jmix.flowui:jmix-flowui-starter")
            implementation("io.jmix.flowui:jmix-flowui-data-starter")
            implementation("io.jmix.flowui:jmix-flowui-themes")
            implementation("io.jmix.datatools:jmix-datatools-starter")
            implementation("io.jmix.datatools:jmix-datatools-flowui-starter")
            implementation("org.springframework.boot:spring-boot-starter-web")
            implementation("com.vaadin:vaadin-dev")
            runtimeOnly("org.hsqldb:hsqldb")
        __LANGUAGE_DEPENDENCIES__

            testImplementation("org.springframework.boot:spring-boot-starter-test")
            testImplementation("io.jmix.flowui:jmix-flowui-test-assist")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }

        configurations.named("implementation") {
            exclude(group = "com.vaadin", module = "hilla")
            exclude(group = "com.vaadin", module = "hilla-dev")
            exclude(group = "com.vaadin", module = "copilot")
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set($compileJava)
            options.encoding = "UTF-8"
        }

        __LANGUAGE_CONFIGURATION__

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        tasks.named<BootRun>("bootRun") {
            mainClass.set("${request.basePackage}.$applicationClass")
            systemProperty("spring.profiles.active", "dev")
            if (providers.gradleProperty("jvw.certifyStartup").orNull == "true") {
                systemProperty("jvw.certify.startup", "true")
                systemProperty("server.port", "0")
            }
        }
    """.trimIndent()
        .replace(
            "        __BUILD_REPOSITORIES__",
            repositoryBlocks(request, indent = "        "),
        )
        .replace(
            "    __PROJECT_REPOSITORIES__",
            repositoryBlocks(request, indent = "    "),
        )
        .replace(
            "__LANGUAGE_PLUGINS__",
            languagePlugins(request, version, indent = "    "),
        )
        .replace(
            "__LANGUAGE_DEPENDENCIES__",
            languageDependencies(request, indent = "    "),
        )
        .replace(
            "__LANGUAGE_CONFIGURATION__",
            languageConfiguration(request, request.javaVersion, compileJava),
        ) + "\n"

    private fun addonBuild(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        compileJava: Int,
    ): String = """
        import org.gradle.api.tasks.compile.JavaCompile
        import org.gradle.jvm.toolchain.JavaLanguageVersion

        buildscript {
            repositories {
                mavenCentral()
                __BUILD_REPOSITORIES__
            }
            dependencies {
                classpath("io.jmix.gradle:jmix-gradle-plugin:${version.jmixVersion}")
            }
        }

        plugins {
            `java-library`
            `maven-publish`
        __LANGUAGE_PLUGINS__
        }

        apply(plugin = "io.jmix")

        group = "${escapeKotlin(request.groupId)}"
        version = "1.0.0-SNAPSHOT"

        repositories {
            mavenCentral()
            __PROJECT_REPOSITORIES__
        }

        extensions.configure<io.jmix.gradle.JmixExtension>("jmix") {
            bomVersion = "${version.jmixVersion}"
        }

        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(${request.javaVersion}))
            }
            withSourcesJar()
            withJavadocJar()
        }

        dependencies {
            api(platform("io.jmix.bom:jmix-bom:${version.jmixVersion}"))
            api("io.jmix.core:jmix-core")
            implementation("org.springframework:spring-context")
        __LANGUAGE_DEPENDENCIES__
            testImplementation("org.junit.jupiter:junit-jupiter")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set($compileJava)
            options.encoding = "UTF-8"
        }

        __LANGUAGE_CONFIGURATION__

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        publishing {
            publications {
                create<MavenPublication>("addon") {
                    from(components["java"])
                }
            }
        }
    """.trimIndent()
        .replace(
            "        __BUILD_REPOSITORIES__",
            repositoryBlocks(request, indent = "        "),
        )
        .replace(
            "    __PROJECT_REPOSITORIES__",
            repositoryBlocks(request, indent = "    "),
        )
        .replace(
            "__LANGUAGE_PLUGINS__",
            languagePlugins(request, version, indent = "    "),
        )
        .replace(
            "__LANGUAGE_DEPENDENCIES__",
            languageDependencies(request, indent = "    "),
        )
        .replace(
            "__LANGUAGE_CONFIGURATION__",
            languageConfiguration(request, request.javaVersion, compileJava),
        ) + "\n"

    private fun applicationSource(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        applicationClass: String,
        moduleId: String,
    ): String = when (request.language) {
        JmixProjectLanguage.JAVA ->
            javaHeadlessApplicationSource(request, version, applicationClass, moduleId)
        JmixProjectLanguage.KOTLIN ->
            kotlinHeadlessApplicationSource(request, version, applicationClass, moduleId)
    }

    private fun javaHeadlessApplicationSource(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        applicationClass: String,
        moduleId: String,
    ): String {
        val dataSourcePropertiesImport = if (version.jmixVersion.startsWith("3.")) {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceProperties"
        } else {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceProperties"
        }
        return """
        package ${request.basePackage};

        import io.jmix.core.JmixModules;
        import io.jmix.core.Resources;
        import io.jmix.core.annotation.JmixModule;
        import io.jmix.data.impl.JmixEntityManagerFactoryBean;
        import io.jmix.data.persistence.DbmsSpecifics;
        import org.springframework.boot.WebApplicationType;
        import org.springframework.boot.autoconfigure.SpringBootApplication;
        import org.springframework.boot.builder.SpringApplicationBuilder;
        import $dataSourcePropertiesImport;
        import org.springframework.boot.context.properties.ConfigurationProperties;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.ConfigurableApplicationContext;
        import org.springframework.context.annotation.Primary;
        import org.springframework.orm.jpa.JpaVendorAdapter;
        import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

        import javax.sql.DataSource;

        @SpringBootApplication
        @JmixModule(id = "$moduleId")
        public class $applicationClass {

            @Bean
            @Primary
            @ConfigurationProperties("main.datasource")
            DataSourceProperties dataSourceProperties() {
                return new DataSourceProperties();
            }

            @Bean
            @Primary
            DataSource dataSource(DataSourceProperties properties) {
                return properties.initializeDataSourceBuilder().build();
            }

            @Bean
            LocalContainerEntityManagerFactoryBean entityManagerFactory(
                    DataSource dataSource,
                    JpaVendorAdapter jpaVendorAdapter,
                    DbmsSpecifics dbmsSpecifics,
                    JmixModules jmixModules,
                    Resources resources
            ) {
                return new JmixEntityManagerFactoryBean(
                        "main",
                        dataSource,
                        jpaVendorAdapter,
                        dbmsSpecifics,
                        jmixModules,
                        resources);
            }

            public static void main(String[] args) {
                try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder($applicationClass.class)
                        .web(WebApplicationType.NONE)
                        .run(args)) {
                    // Successful startup is enough for a headless starter project.
                }
            }
        }
        """.trimIndent() + "\n"
    }

    private fun kotlinHeadlessApplicationSource(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        applicationClass: String,
        moduleId: String,
    ): String {
        val dataSourcePropertiesImport = if (version.jmixVersion.startsWith("3.")) {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceProperties"
        } else {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceProperties"
        }
        return """
        package ${request.basePackage}

        import io.jmix.core.JmixModules
        import io.jmix.core.Resources
        import io.jmix.core.annotation.JmixModule
        import io.jmix.data.impl.JmixEntityManagerFactoryBean
        import io.jmix.data.persistence.DbmsSpecifics
        import org.springframework.boot.WebApplicationType
        import org.springframework.boot.autoconfigure.SpringBootApplication
        import org.springframework.boot.builder.SpringApplicationBuilder
        import $dataSourcePropertiesImport
        import org.springframework.boot.context.properties.ConfigurationProperties
        import org.springframework.context.ConfigurableApplicationContext
        import org.springframework.context.annotation.Bean
        import org.springframework.context.annotation.Primary
        import org.springframework.orm.jpa.JpaVendorAdapter
        import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
        import javax.sql.DataSource

        @SpringBootApplication
        @JmixModule(id = "$moduleId")
        class $applicationClass {

            @Bean
            @Primary
            @ConfigurationProperties("main.datasource")
            fun dataSourceProperties(): DataSourceProperties = DataSourceProperties()

            @Bean
            @Primary
            fun dataSource(properties: DataSourceProperties): DataSource =
                properties.initializeDataSourceBuilder().build()

            @Bean
            fun entityManagerFactory(
                dataSource: DataSource,
                jpaVendorAdapter: JpaVendorAdapter,
                dbmsSpecifics: DbmsSpecifics,
                jmixModules: JmixModules,
                resources: Resources,
            ): LocalContainerEntityManagerFactoryBean =
                JmixEntityManagerFactoryBean(
                    "main",
                    dataSource,
                    jpaVendorAdapter,
                    dbmsSpecifics,
                    jmixModules,
                    resources,
                )

            companion object {
                @JvmStatic
                fun main(args: Array<String>) {
                    SpringApplicationBuilder($applicationClass::class.java)
                        .web(WebApplicationType.NONE)
                        .run(*args)
                        .use(ConfigurableApplicationContext::close)
                }
            }
        }
        """.trimIndent() + "\n"
    }

    private fun applicationTestSource(
        request: JmixProjectTemplateRequest,
        applicationClass: String,
    ): String = when (request.language) {
        JmixProjectLanguage.JAVA -> """
        package ${request.basePackage};

        import org.junit.jupiter.api.Test;

        import static org.junit.jupiter.api.Assertions.assertNotNull;

        class ${applicationClass}Test {
            @Test
            void applicationTypeIsAvailable() {
                assertNotNull($applicationClass.class);
            }
        }
        """.trimIndent() + "\n"
        JmixProjectLanguage.KOTLIN -> """
        package ${request.basePackage}

        import kotlin.test.Test
        import kotlin.test.assertNotNull

        class ${applicationClass}Test {
            @Test
            fun applicationTypeIsAvailable() {
                assertNotNull($applicationClass::class.java)
            }
        }
        """.trimIndent() + "\n"
    }

    private fun addonModuleSource(
        request: JmixProjectTemplateRequest,
        moduleClass: String,
        moduleId: String,
    ): String = when (request.language) {
        JmixProjectLanguage.JAVA -> """
        package ${request.basePackage};

        import io.jmix.core.CoreConfiguration;
        import io.jmix.core.annotation.JmixModule;
        import org.springframework.context.annotation.Configuration;

        @Configuration
        @JmixModule(id = "$moduleId", dependsOn = CoreConfiguration.class)
        public class $moduleClass {
        }
        """.trimIndent() + "\n"
        JmixProjectLanguage.KOTLIN -> """
        package ${request.basePackage}

        import io.jmix.core.CoreConfiguration
        import io.jmix.core.annotation.JmixModule
        import org.springframework.context.annotation.Configuration

        @Configuration(proxyBeanMethods = false)
        @JmixModule(id = "$moduleId", dependsOn = [CoreConfiguration::class])
        class $moduleClass
        """.trimIndent() + "\n"
    }

    private fun addonModuleTestSource(
        request: JmixProjectTemplateRequest,
        moduleClass: String,
    ): String = when (request.language) {
        JmixProjectLanguage.JAVA -> """
        package ${request.basePackage};

        import org.junit.jupiter.api.Test;

        import static org.junit.jupiter.api.Assertions.assertNotNull;

        class ${moduleClass}Test {
            @Test
            void moduleTypeIsAvailable() {
                assertNotNull($moduleClass.class);
            }
        }
        """.trimIndent() + "\n"
        JmixProjectLanguage.KOTLIN -> """
        package ${request.basePackage}

        import kotlin.test.Test
        import kotlin.test.assertNotNull

        class ${moduleClass}Test {
            @Test
            fun moduleTypeIsAvailable() {
                assertNotNull($moduleClass::class.java)
            }
        }
        """.trimIndent() + "\n"
    }

    private fun applicationProperties(request: JmixProjectTemplateRequest): String = """
        spring.main.banner-mode=off
        spring.jmx.enabled=false

        main.datasource.url=jdbc:hsqldb:file:./.jmix/data/${request.artifactId}
        main.datasource.username=sa
        main.datasource.password=
        main.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver
        main.liquibase.change-log=${request.basePackage.replace('.', '/')}/liquibase/changelog.xml

        jmix.core.available-locales=${request.locales.joinToString(",")}

        logging.level.root=INFO
        logging.level.io.jmix=INFO
    """.trimIndent() + "\n"

    private fun liquibaseChangelog(request: JmixProjectTemplateRequest): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <databaseChangeLog
                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.31.xsd">
            <!-- Add versioned application changesets here. -->
        </databaseChangeLog>
    """.trimIndent() + "\n"

    private fun settingsFile(projectName: String): String =
        "rootProject.name = \"${escapeKotlin(projectName)}\"\n"

    private fun gradleProperties(runtimeJava: Int): String = """
        org.gradle.caching=true
        org.gradle.configuration-cache=true
        org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
        jmix.runtime.java=$runtimeJava
    """.trimIndent() + "\n"

    private fun wrapperProperties(
        prefix: String,
        version: JmixProjectVersion,
    ): GeneratedProjectFile = file(
        prefix,
        "gradle/wrapper/gradle-wrapper.properties",
        """
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=https\://services.gradle.org/distributions/gradle-${version.gradleVersion}-bin.zip
        distributionSha256Sum=${version.gradleDistributionSha256}
        networkTimeout=10000
        validateDistributionUrl=true
        zipStoreBase=GRADLE_USER_HOME
        zipStorePath=wrapper/dists
        """.trimIndent() + "\n",
    )

    private fun repositoryBlocks(
        request: JmixProjectTemplateRequest,
        indent: String,
    ): String {
        val blocks = mutableListOf<String>()
        if (request.useMavenLocal) {
            blocks += "${indent}mavenLocal()"
        }
        blocks += "${indent}maven {\n" +
            "${indent}    url = uri(\"https://global.repo.jmix.io/repository/public\")\n" +
            "${indent}}"
        request.additionalRepositories.forEach { repository ->
            blocks += "${indent}maven {\n" +
                "${indent}    url = uri(\"${escapeKotlin(repository)}\")\n" +
                "${indent}}"
        }
        return blocks.joinToString("\n")
    }

    private fun languagePlugins(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        indent: String,
    ): String = when (request.language) {
        JmixProjectLanguage.JAVA -> ""
        JmixProjectLanguage.KOTLIN -> listOf(
            """${indent}kotlin("jvm") version "${version.kotlinVersion}"""",
            """${indent}kotlin("plugin.spring") version "${version.kotlinVersion}"""",
            """${indent}kotlin("plugin.jpa") version "${version.kotlinVersion}"""",
        ).joinToString("\n")
    }

    private fun languageDependencies(
        request: JmixProjectTemplateRequest,
        indent: String,
    ): String = when (request.language) {
        JmixProjectLanguage.JAVA -> ""
        JmixProjectLanguage.KOTLIN -> listOf(
            """${indent}implementation(kotlin("reflect"))""",
            """${indent}testImplementation(kotlin("test"))""",
        ).joinToString("\n")
    }

    private fun languageConfiguration(
        request: JmixProjectTemplateRequest,
        runtimeJava: Int,
        compileJava: Int,
    ): String = when (request.language) {
        JmixProjectLanguage.JAVA -> ""
        JmixProjectLanguage.KOTLIN -> {
            val target = when (compileJava) {
                17 -> "JVM_17"
                21 -> "JVM_21"
                else -> error("No certified Kotlin JVM target for Java $compileJava.")
            }
            """
            kotlin {
                jvmToolchain($runtimeJava)
            }

            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.$target)
                    javaParameters.set(true)
                }
            }
            """.trimIndent()
        }
    }

    private fun commonGitignore(): String = """
        .gradle/
        .idea/
        build/
        out/
        *.iml
        .jmix/
        local.properties
    """.trimIndent() + "\n"

    private fun applicationReadme(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        applicationClass: String,
    ): String = """
        # ${request.projectName}

        Generated by Jmix Visual Workbench from a certified Jmix ${version.jmixVersion} / Java
        ${request.javaVersion} ${request.language.displayName} ${request.uiKind.displayName} template.
        The Gradle distribution and Jmix dependency graph are pinned.

        ## Run

        `${if (request.uiKind == JmixProjectUiKind.FLOW_UI) "./gradlew bootRun" else "./gradlew run"}`

        Main class: `${request.basePackage}.$applicationClass`

        The included HSQLDB configuration is for local development. Supply production datasource
        credentials through an external profile or environment-specific secret store; do not commit
        production credentials.
    """.trimIndent() + "\n"

    private fun addonReadme(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
    ): String = """
        # ${request.projectName}

        Source-first Jmix ${version.jmixVersion} add-on generated by Jmix Visual Workbench.

        ## Verify

        `./gradlew test`

        ## Publish locally

        `./gradlew publishToMavenLocal`
    """.trimIndent() + "\n"

    private fun compositeReadme(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
    ): String = """
        # ${request.projectName}

        Composite Jmix ${version.jmixVersion} workspace with an application module and a reusable
        shared add-on module. Run
        `${if (request.uiKind == JmixProjectUiKind.FLOW_UI) "./gradlew :application:bootRun" else "./gradlew :application:run"}`
        or verify all modules with `./gradlew test`.
    """.trimIndent() + "\n"

    private fun file(
        prefix: String,
        relativePath: String,
        content: String,
        executable: Boolean = false,
    ): GeneratedProjectFile = GeneratedProjectFile(
        relativePath = "$prefix$relativePath",
        content = content,
        executable = executable,
    )

    private fun connectSharedModule(buildFile: String): String {
        val platformDependency = buildFile.indexOf("implementation(platform(")
        require(platformDependency >= 0) {
            "Application template has no Jmix platform dependency."
        }
        val dependencyBlock = buildFile.lastIndexOf("dependencies {", platformDependency)
        require(dependencyBlock >= 0) {
            "Application template has no main dependency block."
        }
        val lineStart = buildFile.lastIndexOf('\n', dependencyBlock).let { it + 1 }
        val indent = buildFile.substring(lineStart, dependencyBlock)
        val insertion = dependencyBlock + "dependencies {".length
        return buildFile.substring(0, insertion) +
            "\n${indent}    implementation(project(\":shared\"))" +
            buildFile.substring(insertion)
    }

    private fun validateGeneratedPaths(paths: List<String>) {
        val seen = mutableSetOf<String>()
        paths.forEach { relativePath ->
            val path = Path.of(relativePath)
            require(
                relativePath.isNotBlank() &&
                    !path.isAbsolute &&
                    '\\' !in relativePath &&
                    path.normalize().toString().replace('\\', '/') == relativePath &&
                    path.none { it.toString() == ".." } &&
                    seen.add(relativePath)
            ) {
                "Unsafe or duplicate generated path: $relativePath"
            }
        }
    }

    private fun isValidPackage(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= 180 &&
            value.split('.').all(packageSegmentPattern::matches) &&
            value.split('.').none { it.lowercase(Locale.ROOT) in javaKeywords }

    private fun String.toJavaClassStem(): String {
        val parts = split(Regex("""[^A-Za-z0-9]+""")).filter(String::isNotBlank)
        val stem = parts.joinToString("") { part ->
            part.replaceFirstChar { character -> character.uppercaseChar() }
        }.ifBlank { "Jmix" }
        return if (stem.first().isDigit()) "Jmix$stem" else stem
    }

    private fun escapeKotlin(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")

    private val JmixProjectLanguage.sourceRoot: String
        get() = if (this == JmixProjectLanguage.JAVA) "java" else "kotlin"

    private val JmixProjectLanguage.sourceExtension: String
        get() = if (this == JmixProjectLanguage.JAVA) "java" else "kt"

    private val wrapperResources = listOf(
        GeneratedProjectResource(
            relativePath = "gradle/wrapper/gradle-wrapper.jar",
            classpathResource = "/project-template/gradle/wrapper/gradle-wrapper.jar",
        ),
        GeneratedProjectResource(
            relativePath = "gradlew",
            classpathResource = "/project-template/gradlew",
            executable = true,
        ),
        GeneratedProjectResource(
            relativePath = "gradlew.bat",
            classpathResource = "/project-template/gradlew.bat",
        ),
    )

    private val javaKeywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "record", "sealed",
        "permits", "var", "yield",
    )
}
