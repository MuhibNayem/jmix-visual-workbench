package org.jmixworkbench.generator

import org.jmixworkbench.model.MenuEntryModel

/**
 * Generates Jmix menu XML configuration.
 * Handles: nested menu items, icons, shortcuts, view references, ordering.
 */
object MenuGenerator {

    private const val NS_MENU = "http://jmix.io/schema/flowui/menu"

    fun generate(entries: List<MenuEntryModel>): String {
        val xml = XmlBuilder("menu-config")
        xml.noDeclaration()

        xml.root {
            attr("xmlns", NS_MENU)

            // Group entries by parent
            val rootEntries = entries.filter { it.parentId == null }.sortedBy { it.order }
            val childMap = entries.filter { it.parentId != null }.groupBy { it.parentId }

            rootEntries.forEach { entry ->
                generateMenuEntry(this, entry, childMap)
            }
        }

        return xml.build()
    }

    private fun generateMenuEntry(
        parent: XmlBuilder.Element,
        entry: MenuEntryModel,
        childMap: Map<String?, List<MenuEntryModel>>
    ) {
        val children = childMap[entry.id]?.sortedBy { it.order } ?: emptyList()

        if (children.isNotEmpty()) {
            parent.child("menu") {
                attr("id", entry.id)
                attr("title", "msg://menu.${entry.id}")
                entry.icon?.let { attr("icon", it) }
                entry.shortcut?.let { attr("shortcut", it) }

                children.forEach { child ->
                    generateMenuEntry(this, child, childMap)
                }
            }
        } else {
            parent.child("item") {
                attr("id", entry.id)
                attr("title", "msg://menu.${entry.id}")
                entry.viewId?.let { attr("view", it) }
                entry.icon?.let { attr("icon", it) }
                entry.shortcut?.let { attr("shortcut", it) }
                entry.openedBy?.let { attr("openedBy", it) }
            }
        }
    }

    /**
     * Generates a single menu item entry for appending to an existing menu.xml.
     */
    fun generateSingleEntry(entry: MenuEntryModel): String {
        val xml = XmlBuilder("item")
        xml.noDeclaration()
        xml.root {
            attr("id", entry.id)
            attr("title", "msg://menu.${entry.id}")
            entry.viewId?.let { attr("view", it) }
            entry.icon?.let { attr("icon", it) }
            entry.shortcut?.let { attr("shortcut", it) }
        }
        return xml.build()
    }
}
