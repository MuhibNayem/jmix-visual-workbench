package org.jmixworkbench.project

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JmixProjectTemplateGeneratorTest {
    @Test
    fun `generates deterministic certified Jmix 2 application`() {
        val request = request()

        val first = JmixProjectTemplateGenerator.generate(request)
        val second = JmixProjectTemplateGenerator.generate(request)

        assertEquals(first, second)
        assertEquals(
            first.files.map { it.relativePath }.sorted(),
            first.files.map { it.relativePath },
        )
        assertTrue(first.resources.any { it.relativePath == "gradle/wrapper/gradle-wrapper.jar" })
        assertTrue(first.resources.any { it.relativePath == "gradlew" && it.executable })
        val build = first.text("build.gradle.kts")
        assertTrue(build.startsWith("import org.gradle.api.tasks.JavaExec\n"))
        assertContains(build, """classpath("io.jmix.gradle:jmix-gradle-plugin:2.8.2")""")
        assertContains(build, "JavaLanguageVersion.of(17)")
        assertContains(
            build,
            "\n        maven {\n" +
                "            url = uri(\"https://global.repo.jmix.io/repository/public\")\n" +
                "        }\n",
        )
        assertFalse("__BUILD_REPOSITORIES__" in build)
        assertContains(
            first.text("gradle/wrapper/gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.4-bin.zip",
        )
        val application = first.files.single {
            it.relativePath.endsWith("PayrollApplication.java")
        }.content
        assertContains(
            application,
            "org.springframework.boot.autoconfigure.jdbc.DataSourceProperties",
        )
        assertFalse(first.files.any { "password=admin" in it.content })
    }

    @Test
    fun `generates Jmix 3 Java 25 runtime with Java 21 bytecode`() {
        val generated = JmixProjectTemplateGenerator.generate(
            request(jmixVersion = "3.0.0", javaVersion = 25),
        )

        assertContains(generated.text("build.gradle.kts"), "JavaLanguageVersion.of(25)")
        assertContains(generated.text("build.gradle.kts"), "options.release.set(21)")
        assertContains(generated.text("gradle.properties"), "jmix.runtime.java=25")
        assertContains(
            generated.files.single { it.relativePath.endsWith("PayrollApplication.java") }.content,
            "org.springframework.boot.jdbc.autoconfigure.DataSourceProperties",
        )
        assertContains(
            generated.text("gradle/wrapper/gradle-wrapper.properties"),
            "distributionSha256Sum=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f",
        )
    }

    @Test
    fun `generates source-first add-on and connected composite project`() {
        val addon = JmixProjectTemplateGenerator.generate(
            request(templateKind = JmixProjectTemplateKind.ADDON),
        )
        assertContains(addon.text("build.gradle.kts"), "`maven-publish`")
        assertTrue(addon.files.any { it.relativePath.endsWith("PayrollModule.java") })

        val composite = JmixProjectTemplateGenerator.generate(
            request(templateKind = JmixProjectTemplateKind.COMPOSITE),
        )
        assertContains(composite.text("settings.gradle.kts"), """include("application", "shared")""")
        assertContains(
            composite.text("application/build.gradle.kts"),
            """implementation(project(":shared"))""",
        )
        assertTrue(composite.files.any { it.relativePath.startsWith("shared/src/main/java/") })
        assertEquals(
            1,
            composite.files.count { it.relativePath == "gradle/wrapper/gradle-wrapper.properties" },
        )
    }

    @Test
    fun `generates complete responsive Java FlowUI application without a fixed credential`() {
        val generated = JmixProjectTemplateGenerator.generate(
            request(uiKind = JmixProjectUiKind.FLOW_UI),
        )

        val build = generated.text("build.gradle.kts")
        assertContains(build, """implementation("io.jmix.flowui:jmix-flowui-starter")""")
        assertContains(build, """implementation("io.jmix.security:jmix-security-data-starter")""")
        assertContains(build, """tasks.named<BootRun>("bootRun")""")
        assertTrue(generated.files.any { it.relativePath.endsWith("/entity/User.java") })
        assertTrue(generated.files.any { it.relativePath.endsWith("/view/main/MainView.java") })
        assertTrue(generated.files.any { it.relativePath.endsWith("/view/login/LoginView.java") })
        assertTrue(generated.files.any { it.relativePath.endsWith("/view/welcome/WelcomeView.java") })
        assertContains(generated.singleEndingWith("/menu.xml"), """<menu id="application"""")
        assertContains(generated.singleEndingWith("/menu.xml"), """<menu id="workspace"""")
        assertContains(generated.singleEndingWith("/menu.xml"), """<menu id="getting-started"""")
        assertContains(generated.singleEndingWith("/menu.xml"), """<item id="welcome"""")
        assertContains(generated.singleEndingWith("/liquibase/changelog.xml"), "securitydata")
        assertContains(generated.text("src/main/frontend/themes/payroll/view/main-view.css"), "@media")
        assertTrue(generated.files.any { it.relativePath.endsWith("messages_bn.properties") })
        assertFalse(generated.files.any { "password=admin" in it.content })
        assertFalse(generated.files.any { "JMIX_DEV_ADMIN_PASSWORD=" in it.content })
        assertTrue(generated.files.any { "SecureRandom" in it.content })
    }

    @Test
    fun `generates native Kotlin applications addons and FlowUI sources`() {
        val headless = JmixProjectTemplateGenerator.generate(
            request(language = JmixProjectLanguage.KOTLIN),
        )
        assertContains(headless.text("build.gradle.kts"), """kotlin("jvm") version "2.4.0"""")
        assertContains(headless.text("build.gradle.kts"), "JvmTarget.JVM_17")
        assertTrue(headless.files.any { it.relativePath.endsWith("/PayrollApplication.kt") })
        assertFalse(headless.files.any { it.relativePath.endsWith(".java") })

        val addon = JmixProjectTemplateGenerator.generate(
            request(
                templateKind = JmixProjectTemplateKind.ADDON,
                language = JmixProjectLanguage.KOTLIN,
            ),
        )
        assertTrue(addon.files.any { it.relativePath.endsWith("/PayrollModule.kt") })

        val flowUi = JmixProjectTemplateGenerator.generate(
            request(
                jmixVersion = "3.0.0",
                javaVersion = 25,
                language = JmixProjectLanguage.KOTLIN,
                uiKind = JmixProjectUiKind.FLOW_UI,
            ),
        )
        assertTrue(flowUi.files.any { it.relativePath.endsWith("/entity/User.kt") })
        assertTrue(flowUi.files.any { it.relativePath.endsWith("/view/main/MainView.kt") })
        assertContains(flowUi.singleEndingWith("/security/UiMinimalRole.kt"), "UiMinimalPolicies")
        assertContains(flowUi.text("build.gradle.kts"), "JavaLanguageVersion.of(25)")
        assertContains(flowUi.text("build.gradle.kts"), "jvmToolchain(25)")
        assertContains(flowUi.text("build.gradle.kts"), "JvmTarget.JVM_21")
    }

    @Test
    fun `rejects uncertified Java combinations and unsafe repositories`() {
        val failure = assertFailsWith<JmixProjectTemplateValidationException> {
            JmixProjectTemplateGenerator.generate(
                request(
                    jmixVersion = "2.8.2",
                    javaVersion = 25,
                    additionalRepositories = listOf(
                        "http://repo.example.test",
                        "https://user:secret@repo.example.test/releases",
                    ),
                ),
            )
        }

        assertTrue(failure.issues.any { "Java 17/21" in it })
        assertEquals(2, failure.issues.count { "must be an HTTPS base URL" in it })
    }

    @Test
    fun `rejects source and coordinate injection`() {
        val failure = assertFailsWith<JmixProjectTemplateValidationException> {
            JmixProjectTemplateGenerator.generate(
                request(
                    groupId = "com.company; println(secret)",
                    basePackage = "com.company..payroll",
                    artifactId = "../payroll",
                    locales = listOf("../../secrets"),
                ),
            )
        }

        assertTrue(failure.issues.size >= 4)
    }

    private fun request(
        groupId: String = "com.acme",
        artifactId: String = "payroll",
        basePackage: String = "com.acme.payroll",
        jmixVersion: String = "2.8.2",
        javaVersion: Int = 17,
        templateKind: JmixProjectTemplateKind = JmixProjectTemplateKind.APPLICATION,
        language: JmixProjectLanguage = JmixProjectLanguage.JAVA,
        uiKind: JmixProjectUiKind = JmixProjectUiKind.HEADLESS,
        locales: List<String> = listOf("en", "bn"),
        additionalRepositories: List<String> = emptyList(),
    ): JmixProjectTemplateRequest = JmixProjectTemplateRequest(
        projectName = "Payroll",
        groupId = groupId,
        artifactId = artifactId,
        basePackage = basePackage,
        projectId = "payroll",
        jmixVersion = jmixVersion,
        javaVersion = javaVersion,
        templateKind = templateKind,
        language = language,
        uiKind = uiKind,
        locales = locales,
        additionalRepositories = additionalRepositories,
    )

    private fun GeneratedJmixProject.text(relativePath: String): String =
        files.single { it.relativePath == relativePath }.content

    private fun GeneratedJmixProject.singleEndingWith(suffix: String): String =
        files.single { it.relativePath.endsWith(suffix) }.content
}
