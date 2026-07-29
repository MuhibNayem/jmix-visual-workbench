package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import org.jmixworkbench.model.MenuEntryModel
import org.jmixworkbench.model.MenuEntryType
import org.jmixworkbench.model.ProjectConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuWorkspaceServiceTest : HeavyPlatformTestCase() {
    fun testIndexedMenuPreservesDeepHierarchyAndAdvancedJmixAttributes() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            val module = ModuleManager.getInstance(project).modules.firstOrNull()
                ?: ModuleManager.getInstance(project).newModule(
                    "${root.path}/menu-test.iml",
                    ModuleType.EMPTY.id,
                )
            if (ModuleRootManager.getInstance(module).contentRoots.none { it == root }) {
                val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                rootModel.addContentEntry(root)
                rootModel.commit()
            }
            write(
                root,
                "loan/src/main/resources/com/acme/menu.xml",
                """
                <menu-config xmlns="http://jmix.io/schema/flowui/menu">
                  <menu id="application" title="msg://menu.application" opened="true" classNames="primary-menu">
                    <menu id="operations" description="Daily operations">
                      <menu id="customerOperations" icon="USERS">
                        <item id="customers" view="Customer.list" shortcutCombination="ALT-C" data-owner="loan">
                          <properties>
                            <property name="scope" value="active"/>
                          </properties>
                          <routeParameters>
                            <parameter name="tenant" value="current"/>
                          </routeParameters>
                          <urlQueryParameters>
                            <parameter name="sort" value="name"/>
                          </urlQueryParameters>
                        </item>
                        <item id="closeMonth" bean="menuBean" beanMethod="closeMonth"/>
                        <separator/>
                      </menu>
                    </menu>
                  </menu>
                </menu-config>
                """.trimIndent(),
            )
            write(
                root,
                "loan/src/main/resources/com/acme/view/customer-list-view.xml",
                """
                <view xmlns="http://jmix.io/schema/flowui/view" id="Customer.list">
                  <layout/>
                </view>
                """.trimIndent(),
            )
            write(
                root,
                "loan/src/main/java/com/acme/menu/PayrollMenu.java",
                """
                package com.acme.menu;

                import org.springframework.stereotype.Component;
                import java.util.Map;

                @Component("payrollMenu")
                public class PayrollMenu {
                    public void closeMonth() {
                    }

                    public void openReport(Map<String, Object> properties) {
                    }

                    private void internalOnly() {
                    }
                }
                """.trimIndent(),
            )
            val sourceRoot = requireNotNull(
                root.findFileByRelativePath("loan/src/main/java"),
            )
            val sourceModel = ModuleRootManager.getInstance(module).modifiableModel
            val contentEntry = sourceModel.contentEntries
                .firstOrNull { it.file == root }
                ?: sourceModel.addContentEntry(root)
            contentEntry.addSourceFolder(sourceRoot, false)
            sourceModel.commit()
        }

        IndexingTestUtil.waitUntilIndexesAreReady(project)
        ApplicationGraphService.getInstance(project).graph(forceRefresh = true)
        val workspace = MenuWorkspaceService.getInstance(project).load()
        assertTrue(workspace.warnings.isEmpty(), workspace.warnings.joinToString())
        val indexedBean = workspace.springBeans.single()
        assertEquals("payrollMenu", indexedBean.name)
        assertEquals("PayrollMenu", indexedBean.declarationName)
        assertEquals(
            "loan/src/main/java/com/acme/menu/PayrollMenu.java",
            indexedBean.sourcePath,
        )
        assertEquals("JAVA", indexedBean.language)
        assertFalse(indexedBean.ambiguous)
        assertEquals(
            listOf("closeMonth", "internalOnly", "openReport"),
            indexedBean.methods.map { it.name },
        )
        assertEquals(
            listOf("closeMonth", "openReport"),
            indexedBean.methods.filter { it.callable }.map { it.name },
        )
        assertEquals(
            "Menu bean method must be public",
            indexedBean.methods.single { it.name == "internalOnly" }.issue,
        )
        val source = workspace.sources.single()
        assertEquals("loan/src/main/resources/com/acme/menu.xml", source.relativePath)
        assertEquals(6, source.nodeCount)
        assertEquals(4, source.maximumDepth)

        val application = source.nodes.single()
        val operations = application.children.single()
        val customerOperations = operations.children.single()
        val customers = customerOperations.children[0]
        val bean = customerOperations.children[1]
        val separator = customerOperations.children[2]

        assertEquals("menu", application.kind)
        assertTrue(application.opened)
        assertEquals("primary-menu", application.classNames)
        assertEquals("Daily operations", operations.description)
        assertEquals("Customer.list", customers.viewId)
        assertEquals("ALT-C", customers.shortcut)
        assertEquals("active", customers.properties["scope"])
        assertEquals("current", customers.routeParameters["tenant"])
        assertEquals("name", customers.urlQueryParameters["sort"])
        assertEquals("loan", customers.preservedAttributes["data-owner"])
        assertEquals("bean", bean.kind)
        assertEquals("menuBean", bean.bean)
        assertEquals("closeMonth", bean.beanMethod)
        assertEquals("separator", separator.kind)
        assertTrue(separator.syntheticId)
        assertFalse(source.sourceLocator.revisionFingerprint.isBlank())

        val entries = listOf(
            menu("application", null, 10, MenuEntryType.MENU, title = "msg://menu.application"),
            menu("operations", "application", 10, MenuEntryType.MENU),
            menu("customerOperations", "operations", 10, MenuEntryType.MENU),
            menu("customers", "customerOperations", 10, MenuEntryType.VIEW, view = "CustomerV2.list"),
            menu("closeMonth", "customerOperations", 20, MenuEntryType.BEAN)
                .copy(bean = "menuBean", beanMethod = "closeMonth"),
            menu("__separator_0_0_0_2", "customerOperations", 30, MenuEntryType.SEPARATOR),
        )
        val generation = CodeGenerationService.getInstance(project)
        val config = ProjectConfig(requireNotNull(project.basePath), "com.acme")
        val stale = generation.generateMenu(entries, config, source.relativePath, "stale-revision")
        assertFalse(stale.success)
        assertTrue(stale.errors.single().contains("SOURCE-STALE"))

        val applied = generation.generateMenu(
            entries,
            config,
            source.relativePath,
            source.sourceLocator.revisionFingerprint,
        )
        assertTrue(applied.success, applied.errors.joinToString())
        assertEquals(listOf(source.relativePath), applied.filesWritten)
        val updated = String(
            requireNotNull(ProjectFileResolver.getInstance(project).resolveFile(source.relativePath))
                .file.contentsToByteArray(false),
        )
        assertTrue("""view="CustomerV2.list"""" in updated)
        assertTrue("""data-owner="loan"""" in updated)
        assertTrue("""name="scope"""" in updated)
    }

    private fun menu(
        id: String,
        parentId: String?,
        order: Int,
        type: MenuEntryType,
        view: String? = null,
        title: String? = null,
    ) = MenuEntryModel(
        id = id,
        caption = id,
        parentId = parentId,
        order = order,
        type = type,
        viewId = view,
        title = title,
    )

    private fun write(root: VirtualFile, path: String, content: String) {
        val parent = requireNotNull(VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')))
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }
}
