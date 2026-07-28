package org.jmixworkbench.discovery.static

import org.jmixworkbench.discovery.model.EvidenceConfidence
import org.jmixworkbench.discovery.model.EvidenceSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleConfigParserTest {
    private val parser = GradleConfigParser(
        internalGroupPrefixes = setOf("com.mycorp", "org.private"),
    )

    @Test
    fun `parses Groovy literals includes coordinates and add-on classes`() {
        val result = parser.parse(
            listOf(
                GradleTextInput(
                    "settings.gradle",
                    """
                    include ':app', ':addon'
                    includeBuild 'build-logic'
                    """.trimIndent(),
                ),
                GradleTextInput(
                    "build.gradle",
                    """
                    plugins {
                        id 'io.jmix' version '2.8.7'
                        id 'com.mycorp.jmix-conventions'
                    }
                    java {
                        toolchain {
                            languageVersion = JavaLanguageVersion.of(17)
                        }
                    }
                    dependencies {
                        implementation 'io.jmix.core:jmix-core:2.8.7'
                        implementation 'io.jmix.bpm:jmix-bpm-starter:2.8.7'
                        implementation 'com.vendor:payments-addon:1.4.0'
                        implementation 'com.mycorp.secret:workflow-addon:9.9.0'
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals("2.8.7", result.jmixVersion.value)
        assertEquals(EvidenceConfidence.STRONG, result.jmixVersion.confidence)
        assertEquals(17, result.targetJdk.value)
        assertEquals(listOf(":addon", ":app"), result.includedProjects.map { it.value })
        assertEquals(listOf("build-logic"), result.includedBuilds.map { it.value })
        assertTrue(result.plugins.any { it.value == "io.jmix" })
        assertTrue(result.conventionPlugins.any { it.value == "com.mycorp.jmix-conventions" })
        assertTrue(result.addOns.any { it.kind == AddOnKind.PUBLIC && it.coordinate == "io.jmix.bpm:jmix-bpm-starter:2.8.7" })
        assertTrue(result.addOns.any { it.kind == AddOnKind.THIRD_PARTY && it.coordinate == "com.vendor:payments-addon:1.4.0" })
        val internal = result.addOns.single { it.kind == AddOnKind.INTERNAL }
        assertTrue(internal.coordinate.startsWith("internal-addon-"))
        assertFalse(internal.coordinate.contains("mycorp"))
        assertFalse(result.canonicalJson.contains("mycorp.secret"))
    }

    @Test
    fun `parses Kotlin DSL plugin coordinates and compatibility literals`() {
        val result = parser.parse(
            listOf(
                GradleTextInput(
                    "build.gradle.kts",
                    """
                    plugins {
                        id("io.jmix") version "3.0.1"
                        kotlin("jvm") version "2.4.0"
                    }
                    java {
                        sourceCompatibility = JavaVersion.VERSION_21
                        targetCompatibility = JavaVersion.VERSION_21
                        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
                    }
                    dependencies {
                        implementation("io.jmix.core:jmix-core:3.0.1")
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals("3.0.1", result.jmixVersion.value)
        assertEquals(21, result.targetJdk.value)
        assertTrue(result.plugins.any { it.value == "io.jmix" })
        assertTrue(result.coordinates.any { it.value == "io.jmix.core:jmix-core:3.0.1" })
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `resolves catalog aliases and reports unresolved aliases separately`() {
        val result = parser.parse(
            listOf(
                GradleTextInput(
                    "gradle/libs.versions.toml",
                    """
                    [versions]
                    jmix = "2.8.7"
                    [libraries]
                    jmix-core = { module = "io.jmix.core:jmix-core", version.ref = "jmix" }
                    [plugins]
                    jmix = { id = "io.jmix", version.ref = "jmix" }
                    """.trimIndent(),
                ),
                GradleTextInput(
                    "build.gradle.kts",
                    """
                    plugins {
                        alias(libs.plugins.jmix)
                        alias(libs.plugins.missing)
                    }
                    dependencies {
                        implementation(libs.jmix.core)
                        implementation(libs.private.unresolved)
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals("2.8.7", result.jmixVersion.value)
        assertEquals(EvidenceSourceKind.VERSION_CATALOG, result.jmixVersion.sourceKind)
        assertTrue(result.plugins.any { it.value == "io.jmix" })
        assertTrue(result.coordinates.any { it.value == "io.jmix.core:jmix-core:2.8.7" })
        assertEquals(2, result.diagnostics.count { it.reasonCode == "P2_ALIAS_UNRESOLVED" })
    }

    @Test
    fun `dynamic expressions remain unknown and never become defaults`() {
        val result = parser.parse(
            listOf(
                GradleTextInput(
                    "build.gradle",
                    """
                    def jmixVersion = providers.gradleProperty("jmixVersion").get()
                    plugins {
                        id 'io.jmix' version jmixVersion
                    }
                    java.toolchain.languageVersion = JavaLanguageVersion.of(project.findProperty("jdk"))
                    dependencies {
                        implementation "io.jmix.core:jmix-core:${'$'}jmixVersion"
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertNull(result.jmixVersion.value)
        assertNull(result.targetJdk.value)
        assertTrue(result.diagnostics.any { it.reasonCode == "P2_DYNAMIC_BUILD_LOGIC" })
        assertFalse(result.canonicalJson.contains("2.4.0"))
        assertFalse(result.canonicalJson.contains("17"))
    }

    @Test
    fun `conflicting imported and static facts remain conflicting`() {
        val result = parser.parse(
            inputs = listOf(
                GradleTextInput(
                    "build.gradle.kts",
                    """id("io.jmix") version "2.8.7"""",
                ),
            ),
            importedCoordinates = listOf(
                ImportedCoordinate("io.jmix.core:jmix-core:3.0.1", "imported-module"),
            ),
        )

        assertNull(result.jmixVersion.value)
        assertEquals(EvidenceConfidence.CONFLICTING, result.jmixVersion.confidence)
        assertEquals(listOf("2.8.7", "3.0.1"), result.jmixVersion.observedValues.sorted())
        assertTrue(result.diagnostics.any { it.reasonCode == "P2_EVIDENCE_CONFLICT" })
        assertTrue(result.coordinates.any { it.sourceKind == EvidenceSourceKind.IMPORTED_GRADLE_MODEL })
    }

    @Test
    fun `input permutations produce one deterministic profile digest`() {
        val catalog = GradleTextInput(
            "gradle/libs.versions.toml",
            """
            [versions]
            jmix = "3.0.1"
            [plugins]
            jmix = { id = "io.jmix", version.ref = "jmix" }
            """.trimIndent(),
        )
        val build = GradleTextInput(
            "build.gradle.kts",
            """
            plugins { alias(libs.plugins.jmix) }
            java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
            """.trimIndent(),
        )
        val settings = GradleTextInput("settings.gradle.kts", """include(":app")""")

        val forward = parser.parse(listOf(catalog, build, settings))
        val reverse = parser.parse(listOf(settings, build, catalog))

        assertEquals(forward.digest, reverse.digest)
        assertEquals(forward.canonicalJson, reverse.canonicalJson)
    }

    @Test
    fun `malformed input is tolerated with diagnostics`() {
        val result = parser.parse(
            listOf(
                GradleTextInput(
                    "build.gradle.kts",
                    """
                    plugins {
                        id("io.jmix") version
                    dependencies {
                        implementation(
                    """.trimIndent(),
                ),
            ),
        )

        assertNotNull(result)
        assertTrue(result.diagnostics.any { it.reasonCode == "P2_MALFORMED_BUILD_TEXT" })
        assertNull(result.jmixVersion.value)
        assertNull(result.targetJdk.value)
    }
}
