package org.jmixworkbench.project

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JTable
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
                "Organization template catalogs",
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

    private fun Component.descendants(): Sequence<Component> = sequence {
        yield(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { child ->
                yieldAll(child.descendants())
            }
        }
    }
}
