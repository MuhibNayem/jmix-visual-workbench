package org.jmixworkbench.generator

import org.jmixworkbench.model.MenuEntryModel
import org.jmixworkbench.model.MenuEntryType

/**
 * Generates Jmix menu XML configuration.
 * Handles: nested menu items, icons, shortcuts, view references, ordering.
 */
object MenuGenerator {

    private const val NS_MENU = "http://jmix.io/schema/flowui/menu"

    fun generate(entries: List<MenuEntryModel>): String {
        validate(entries)
        val xml = XmlBuilder("menu-config")
        xml.noDeclaration()

        xml.root {
            attr("xmlns", NS_MENU)

            val ids = entries.map(MenuEntryModel::id).toSet()
            val rootEntries = entries
                .filter { it.parentId == null || it.parentId !in ids }
                .sortedWith(compareBy(MenuEntryModel::order, MenuEntryModel::id))
            val childMap = entries
                .filter { it.parentId in ids }
                .groupBy { it.parentId }

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
        val children = childMap[entry.id]
            ?.sortedWith(compareBy(MenuEntryModel::order, MenuEntryModel::id))
            ?: emptyList()
        val entryType = entry.type ?: if (children.isNotEmpty()) MenuEntryType.MENU else MenuEntryType.VIEW

        when (entryType) {
            MenuEntryType.MENU -> parent.child("menu") {
                commonAttributes(entry, includeShortcut = false)
                if (entry.opened) attr("opened", "true")

                children.forEach { child ->
                    generateMenuEntry(this, child, childMap)
                }
            }

            MenuEntryType.VIEW -> parent.child("item") {
                commonAttributes(entry)
                entry.viewId?.takeIf(String::isNotBlank)?.let { attr("view", it) }
                entry.openedBy?.takeIf(String::isNotBlank)?.let { attr("openedBy", it) }
            }

            MenuEntryType.BEAN -> parent.child("item") {
                commonAttributes(entry)
                entry.bean?.takeIf(String::isNotBlank)?.let { attr("bean", it) }
                entry.beanMethod?.takeIf(String::isNotBlank)?.let { attr("beanMethod", it) }
            }

            MenuEntryType.SEPARATOR -> parent.child("separator") {}
        }
    }

    private fun XmlBuilder.Element.commonAttributes(
        entry: MenuEntryModel,
        includeShortcut: Boolean = true,
    ) {
        attr("id", entry.id)
        attr("title", entry.title?.takeIf(String::isNotBlank) ?: "msg://menu.${entry.id}")
        entry.description?.takeIf(String::isNotBlank)?.let { attr("description", it) }
        entry.icon?.takeIf(String::isNotBlank)?.let { attr("icon", it) }
        entry.classNames?.takeIf(String::isNotBlank)?.let { attr("classNames", it) }
        if (includeShortcut) {
            entry.shortcut?.takeIf(String::isNotBlank)?.let { attr("shortcutCombination", it) }
        }
    }

    private fun validate(entries: List<MenuEntryModel>) {
        require(entries.isNotEmpty()) { "Add at least one menu node." }
        require(entries.all { it.id.isNotBlank() }) { "Every menu node requires an id." }
        val duplicates = entries.groupingBy(MenuEntryModel::id).eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate menu ids: ${duplicates.sorted().joinToString()}." }
        val ids = entries.map(MenuEntryModel::id).toSet()
        require(entries.none { it.parentId == it.id }) { "A menu node cannot be its own parent." }

        entries.forEach { entry ->
            val seen = mutableSetOf(entry.id)
            var parentId = entry.parentId
            while (parentId != null && parentId in ids) {
                require(seen.add(parentId)) { "Menu hierarchy contains a cycle at '$parentId'." }
                parentId = entries.first { it.id == parentId }.parentId
            }
        }

        val childParents = entries.mapNotNull(MenuEntryModel::parentId).toSet()
        require(entries.filter { it.id in childParents }.all {
            (it.type ?: MenuEntryType.MENU) == MenuEntryType.MENU
        }) { "Only menu nodes can contain nested children." }
        require(entries.filter { it.type == MenuEntryType.BEAN }.all {
            !it.bean.isNullOrBlank() && !it.beanMethod.isNullOrBlank()
        }) { "Bean menu items require both bean and beanMethod." }
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
            entry.shortcut?.let { attr("shortcutCombination", it) }
        }
        return xml.build()
    }
}
