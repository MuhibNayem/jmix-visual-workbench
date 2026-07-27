package org.jmixworkbench.generator

import org.jmixworkbench.model.*

/**
 * Generates Jmix Flow UI view XML descriptors from a ViewModel.
 * Handles: all component types, nested layouts, data containers, data loaders,
 * fetch plans, facets, actions, DataGrid columns, generic filters, dialogs,
 * fragments, URL sync, and full data binding.
 */
object ViewXmlGenerator {

    private const val NS_LAYOUT = "http://jmix.io/schema/flowui/layout"
    private const val NS_DATA = "http://jmix.io/schema/flowui/data"

    fun generate(view: ViewModel): String {
        val xml = XmlBuilder("view")
        xml.noDeclaration()

        xml.root {
            ns("", NS_LAYOUT)
            ns("data", NS_DATA)

            attr("xmlns", NS_LAYOUT)
            attr("xmlns:data", NS_DATA)
            attr("title", "msg://${view.viewName}.${view.viewId}")
            attr("focusComponent", inferFocusComponent(view))

            // ── Data section ──
            if (view.dataContainers.isNotEmpty()) {
                child("data") {
                    view.dataContainers.forEach { dc ->
                        generateDataContainer(this, dc)
                    }
                }
            }

            // ── Facets ──
            if (view.facets.isNotEmpty()) {
                child("facets") {
                    view.facets.forEach { facet ->
                        generateFacet(this, facet)
                    }
                }
            }

            // ── Actions ──
            if (view.actions.isNotEmpty()) {
                child("actions") {
                    view.actions.forEach { action ->
                        child("action") {
                            attr("id", action.id)
                            attr("type", action.type.xmlType)
                            action.caption?.let { attr("caption", it) }
                            action.icon?.let { attr("icon", it) }
                            action.targetComponent?.let { attr("target", it) }
                        }
                    }
                }
            }

            // ── Layout ──
            child("layout") {
                generateComponentTree(this, view.layout, view)
            }
        }

        return xml.build()
    }

    // ─── Data Containers ─────────────────────────────────────────────────────

    private fun generateDataContainer(parent: XmlBuilder.Element, dc: DataContainerModel) {
        parent.child(dc.type.xmlTag) {
            attr("id", dc.id)
            attr("class", dc.entityClass)

            dc.fetchPlan?.let { fp ->
                if (fp.name == "_base" || fp.name == "_local") {
                    attr("fetchPlan", fp.name)
                } else {
                    child("fetchPlan") {
                        attr("extends", fp.extendsFetchPlan ?: "_base")
                        fp.properties.forEach { prop ->
                            generateFetchPlanProperty(this, prop)
                        }
                    }
                }
            }

            dc.loader?.let { loader ->
                child("loader") {
                    attr("id", loader.id)
                    if (loader.cacheable) attr("cacheable", "true")
                    loader.firstResult?.let { attr("firstResult", it.toString()) }
                    loader.maxResults?.let { attr("maxResults", it.toString()) }

                    loader.query?.let { query ->
                        child("query") {
                            text(query)
                        }
                    }

                    if (loader.parameters.isNotEmpty()) {
                        loader.parameters.forEach { param ->
                            child("parameter") {
                                attr("name", param.name)
                                attr("value", param.value)
                            }
                        }
                    }
                }
            }

            dc.nestedContainers.forEach { nested ->
                generateDataContainer(this, nested)
            }
        }
    }

    private fun generateFetchPlanProperty(parent: XmlBuilder.Element, prop: FetchPlanProperty) {
        parent.child("property") {
            attr("name", prop.name)
            prop.fetchMode?.let { attr("fetchMode", it) }
            prop.fetchPlan?.let { nestedFp ->
                child("fetchPlan") {
                    nestedFp.extendsFetchPlan?.let { attr("extends", it) }
                    nestedFp.properties.forEach { nestedProp ->
                        generateFetchPlanProperty(this, nestedProp)
                    }
                }
            }
        }
    }

    // ─── Facets ──────────────────────────────────────────────────────────────

    private fun generateFacet(parent: XmlBuilder.Element, facet: FacetModel) {
        parent.child(facet.type.xmlTag) {
            facet.properties.forEach { (k, v) -> attr(k, v.toString()) }
            facet.children.forEach { childFacet ->
                generateFacet(this, childFacet)
            }
        }
    }

    // ─── Component Tree ──────────────────────────────────────────────────────

    private fun generateComponentTree(
        parent: XmlBuilder.Element,
        component: ComponentModel,
        view: ViewModel
    ) {
        if (component.id == "root") {
            component.children.forEach { child ->
                generateComponentTree(parent, child, view)
            }
            return
        }

        parent.child(component.type.xmlTag) {
            attr("id", component.id)

            // Common properties
            component.width?.let { attr("width", it) }
            component.height?.let { attr("height", it) }
            if (!component.visible) attr("visible", "false")
            if (!component.enabled) attr("enabled", "false")
            component.styleName?.let { attr("themeNames", it) }
            if (component.cssClasses.isNotEmpty()) {
                attr("classNames", component.cssClasses.joinToString(" "))
            }
            component.expand?.let { attr("expand", it) }
            component.colspan?.let { attr("colspan", it.toString()) }
            component.rowspan?.let { attr("rowspan", it.toString()) }

            // Data binding
            component.dataBinding?.let { attr("dataContainer", it) }
            component.propertyBinding?.let { attr("property", it) }

            // Custom properties
            component.properties.forEach { (k, v) ->
                attr(k, v.toString())
            }

            // DataGrid columns
            if (component.type == ComponentType.DATA_GRID || component.type == ComponentType.TREE_DATA_GRID) {
                generateDataGridContent(this, component, view)
            }

            // Generic filter
            if (component.type == ComponentType.GENERIC_FILTER) {
                component.dataBinding?.let { attr("dataLoader", it) }
            }

            // Actions on components
            if (component.actions.isNotEmpty()) {
                child("actions") {
                    component.actions.forEach { action ->
                        child("action") {
                            attr("id", action.id)
                            attr("type", action.type.xmlType)
                            action.caption?.let { attr("caption", it) }
                            action.icon?.let { attr("icon", it) }
                            action.shortcut?.let { attr("shortcutCombination", it) }
                            action.variant?.let { attr("variant", it) }
                        }
                    }
                }
            }

            // Children (for layouts)
            component.children.forEach { childComp ->
                generateComponentTree(this, childComp, view)
            }
        }
    }

    private fun generateDataGridContents(
        parent: XmlBuilder.Element,
        component: ComponentModel,
        view: ViewModel
    ) {
        // Columns
        if (component.columns.isNotEmpty()) {
            child("columns") {
                component.columns.forEach { col ->
                    child("column") {
                        attr("property", col.property)
                        col.caption?.let { attr("header", it) }
                        if (!col.sortable) attr("sortable", "false")
                        if (!col.resizable) attr("resizable", "false")
                        if (col.frozen) attr("frozen", "true")
                        if (!col.autoWidth) attr("autoWidth", "false")
                        col.width?.let { attr("width", it) }
                        col.flexGrow?.let { attr("flexGrow", it.toString()) }
                        col.textAlign?.let { attr("textAlign", it) }
                        col.renderer?.let { attr("renderer", it) }
                    }
                }
            }
        }

        // Actions toolbar
        val listActions = component.actions.filter {
            it.type in listOf(
                ActionType.LIST_CREATE, ActionType.LIST_EDIT, ActionType.LIST_REMOVE,
                ActionType.LIST_REFRESH, ActionType.LIST_IMPORT, ActionType.LIST_EXPORT
            )
        }
        if (listActions.isNotEmpty()) {
            // Actions already generated above
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun inferFocusComponent(view: ViewModel): String? {
        return when (view.viewType) {
            ViewType.LIST_VIEW -> view.layout.children
                .firstOrNull { it.type == ComponentType.DATA_GRID || it.type == ComponentType.TREE_DATA_GRID }
                ?.id
            ViewType.DETAIL_VIEW -> view.layout.children
                .firstOrNull { it.type == ComponentType.FORM_LAYOUT }
                ?.id
            else -> null
        }
    }

    // ─── Fragment XML ────────────────────────────────────────────────────────

    fun generateFragment(fragment: FragmentModel): String {
        val xml = XmlBuilder("fragment")
        xml.noDeclaration()

        xml.root {
            ns("", NS_LAYOUT)
            ns("data", NS_DATA)
            attr("xmlns", NS_LAYOUT)
            attr("xmlns:data", NS_DATA)

            if (fragment.dataContainers.isNotEmpty()) {
                child("data") {
                    fragment.dataContainers.forEach { dc ->
                        generateDataContainer(this, dc)
                    }
                }
            }

            child("layout") {
                generateComponentTree(this, fragment.layout, ViewModel(
                    viewName = fragment.name,
                    packageName = fragment.packageName
                ))
            }
        }

        return xml.build()
    }
}
