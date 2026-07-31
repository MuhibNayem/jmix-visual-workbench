package org.jmixworkbench.project

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JmixTemplateCatalogConfigurableTest : BasePlatformTestCase() {
    fun testNativeSettingsAreKeyboardDiscoverableResettableAndSideEffectFreeOnOpen() {
        val settings = JmixTemplateCatalogSettings.getInstance()
        val previousOffline = settings.state.offlineMode
        val previousCatalogs = settings.state.catalogs.map {
            it.copyState()
        }
        val configurable = JmixTemplateCatalogConfigurable()
        try {
            settings.replace(offlineMode = false, catalogs = emptyList())
            val component = configurable.createComponent()
            configurable.reset()

            assertFalse(configurable.isModified())
            val descendants = component.descendants().toList()
            val table = descendants.filterIsInstance<JTable>().singleOrNull()
            assertNotNull(table)
            assertEquals(
                "Organization project and connector catalogs",
                requireNotNull(table).accessibleContext.accessibleName,
            )
            val buttons = descendants.filterIsInstance<AbstractButton>()
                .associateBy { it.text }
            listOf(
                "Add…",
                "Edit…",
                "Remove",
                "Import Signed Bundle…",
                "Refresh Selected",
                "Create Signed Bundle…",
                "Create Signed Connector Catalog…",
            ).forEach { label ->
                val button = buttons[label]
                assertNotNull(button, "Missing native settings action '$label'.")
                assertTrue(requireNotNull(button).isFocusable)
            }
            val offline = descendants.filterIsInstance<JCheckBox>().single {
                it.text.startsWith("Work offline")
            }
            offline.doClick()
            assertTrue(configurable.isModified())
            configurable.reset()
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            settings.replace(previousOffline, previousCatalogs)
        }
    }

    fun testNativeAuthoringAndSideBySideReviewSurfacesAreDiscoverable() {
        val clock = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC)
        val authoring = JmixTemplateCatalogAuthoringDialog(clock)
        val authoringComponent = authoring.createCenterPanel()
        assertEquals(
            "Signed Jmix project template authoring form",
            authoringComponent.accessibleContext.accessibleName,
        )
        assertTrue(
            authoringComponent.descendants()
                .filterIsInstance<AbstractButton>()
                .filter { it.text == "Choose…" }
                .all(AbstractButton::isFocusable),
        )
        val connectorAuthoring = JmixConnectorCatalogAuthoringDialog(clock)
        val connectorAuthoringComponent = connectorAuthoring.createCenterPanel()
        assertEquals(
            "Signed Jmix connector catalog authoring form",
            connectorAuthoringComponent.accessibleContext.accessibleName,
        )
        assertTrue(
            connectorAuthoringComponent.descendants()
                .filterIsInstance<AbstractButton>()
                .filter { it.text == "Choose…" }
                .all(AbstractButton::isFocusable),
        )

        val root = createTempDirectory("jmix-native-authoring-preview-")
        try {
            val request = JmixProjectTemplateRequest(
                projectName = "Payroll",
                groupId = "com.acme",
                artifactId = "payroll",
                basePackage = "com.acme.payroll",
                projectId = "payroll",
                jmixVersion = "2.8.2",
                javaVersion = 17,
                uiKind = JmixProjectUiKind.FLOW_UI,
            )
            JmixProjectInstaller.install(root, JmixProjectTemplateGenerator.generate(request))
            root.resolve("README.md").writeText("# Governed Payroll\n")
            root.resolve("policy").createDirectories()
            root.resolve("policy/rules.txt").writeText("reviewed=true\n")
            val preview = JmixTemplateOverlayPreviewDialog(
                JmixTemplateOverlayPlanner.plan(root, request),
            )
            val component = preview.createCenterPanel()
            val descendants = component.descendants().toList()
            val table = descendants.filterIsInstance<JTable>().single()
            assertEquals("Project template file changes", table.accessibleContext.accessibleName)
            assertTrue(descendants.filterIsInstance<JSplitPane>().size >= 2)
            assertTrue(descendants.filterIsInstance<JScrollPane>().size >= 3)
        } finally {
            deleteTree(root)
        }
    }

    private fun Component.descendants(): Sequence<Component> = sequence {
        yield(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { child ->
                yieldAll(child.descendants())
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
