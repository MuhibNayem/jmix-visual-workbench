package org.jmixworkbench.generator

import org.jmixworkbench.model.*

/**
 * Orchestrates full CRUD stack generation from a single EntityModel.
 * Produces: entity class, migration, list view (XML + controller),
 * detail view (XML + controller), menu entry, security role,
 * fetch plans, localization messages, and optional data repository.
 *
 * This is the "one-click CRUD" engine — the core automation feature.
 */
object CrudOrchestrator {

    data class CrudOutput(
        val entityFile: GeneratedFile,
        val migrationFile: GeneratedFile,
        val listViewXml: GeneratedFile,
        val listViewController: GeneratedFile,
        val detailViewXml: GeneratedFile,
        val detailViewController: GeneratedFile,
        val menuXml: GeneratedFile,
        val roleFile: GeneratedFile,
        val messagesFile: GeneratedFile,
        val dataRepositoryFile: GeneratedFile? = null,
        val fetchPlanFile: GeneratedFile? = null
    )

    data class GeneratedFile(
        val relativePath: String,
        val content: String,
        val description: String
    )

    data class CrudOptions(
        val generateMigration: Boolean = true,
        val generateDataRepository: Boolean = false,
        val generateFetchPlan: Boolean = true,
        val listViewType: ListViewStyle = ListViewStyle.DATA_GRID,
        val detailViewMode: DetailViewMode = DetailViewMode.FORM,
        val includeFilter: Boolean = true,
        val includePagination: Boolean = true,
        val includeActions: Boolean = true,
        val menuParentId: String? = null,
        val menuIcon: String? = null,
        val roleCode: String? = null,
        val dbType: DatabaseType = DatabaseType.POSTGRES
    )

    enum class ListViewStyle { DATA_GRID, TREE_DATA_GRID, VIRTUAL_LIST }
    enum class DetailViewMode { FORM, TABBED, SIDE_PANEL }

    fun generate(requestedEntity: EntityModel, config: ProjectConfig, options: CrudOptions = CrudOptions()): CrudOutput {
        val entity = requestedEntity.withProjectNaming(config.projectId)
        val entityPkg = entity.packageName
        val basePkg = entityPkg.removeSuffix(".entity")
        val viewPkg = "$basePkg.view"

        val entityName = entity.className
        val entityLower = entityName.replaceFirstChar { it.lowercase() }
        val viewIdBase = entityName.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

        // ── 1. Entity Java class ──
        val entityContent = EntityGenerator.generate(entity)
        val entityFile = GeneratedFile(
            relativePath = "${config.sourceRoot}/${config.packageToPath(entityPkg)}/$entityName.java",
            content = entityContent,
            description = "Entity class: $entityName"
        )

        // ── 2. Migration ──
        val migrationContent = if (options.generateMigration) {
            val migration = MigrationGenerator.generateFromEntity(entity, options.dbType)
            MigrationGenerator.generate(migration)
        } else ""
        val migrationFile = GeneratedFile(
            relativePath = "${config.changelogPath()}/001-${entity.resolvedTableName.lowercase()}.xml",
            content = migrationContent,
            description = "Liquibase migration for ${entity.resolvedTableName}"
        )

        // ── 3. List View XML ──
        val listView = buildListView(entity, viewPkg, viewIdBase, options)
        val listViewXmlContent = ViewXmlGenerator.generate(listView)
        val listViewXml = GeneratedFile(
            relativePath = "${config.resourceRoot}/${config.packageToPath(viewPkg)}/${entityName}ListView.xml",
            content = listViewXmlContent,
            description = "List view XML for $entityName"
        )

        // ── 4. List View Controller ──
        val listControllerContent = ViewControllerGenerator.generate(listView)
        val listViewController = GeneratedFile(
            relativePath = "${config.sourceRoot}/${config.packageToPath(viewPkg)}/${entityName}ListView.java",
            content = listControllerContent,
            description = "List view controller for $entityName"
        )

        // ── 5. Detail View XML ──
        val detailView = buildDetailView(entity, viewPkg, viewIdBase, options)
        val detailViewXmlContent = ViewXmlGenerator.generate(detailView)
        val detailViewXml = GeneratedFile(
            relativePath = "${config.resourceRoot}/${config.packageToPath(viewPkg)}/${entityName}DetailView.xml",
            content = detailViewXmlContent,
            description = "Detail view XML for $entityName"
        )

        // ── 6. Detail View Controller ──
        val detailControllerContent = ViewControllerGenerator.generate(detailView)
        val detailViewController = GeneratedFile(
            relativePath = "${config.sourceRoot}/${config.packageToPath(viewPkg)}/${entityName}DetailView.java",
            content = detailControllerContent,
            description = "Detail view controller for $entityName"
        )

        // ── 7. Menu XML ──
        val menuEntry = MenuEntryModel(
            id = "$viewIdBase-list",
            caption = entityName,
            parentId = options.menuParentId,
            icon = options.menuIcon ?: "vaadin:table",
            viewId = "$viewIdBase-list-view"
        )
        val menuContent = MenuGenerator.generate(listOf(menuEntry))
        val menuXml = GeneratedFile(
            relativePath = "${config.resourceRoot}/${config.packageToPath(basePkg)}/menu.xml",
            content = menuContent,
            description = "Menu entry for $entityName"
        )

        // ── 8. Security Role ──
        val roleCode = options.roleCode ?: "${entityLower}-role"
        val role = RoleModel(
            className = "${entityName}Role",
            packageName = "$basePkg.security",
            name = "$entityName full access",
            code = roleCode,
            description = "Full access to $entityName",
            entityPolicies = mutableListOf(
                EntityPolicyModel(
                    entityClass = entity.fullName,
                    allActions = true
                )
            ),
            menuPolicies = mutableListOf(
                MenuPolicyModel(menuId = "$viewIdBase-list")
            ),
            viewPolicies = mutableListOf(
                ViewPolicyModel(viewId = "$viewIdBase-list-view"),
                ViewPolicyModel(viewId = "$viewIdBase-detail-view")
            )
        )
        val roleContent = RoleGenerator.generate(role, "$basePkg.security")
        val roleFile = GeneratedFile(
            relativePath = "${config.sourceRoot}/${config.packageToPath(basePkg)}/security/${entityName}Role.java",
            content = roleContent,
            description = "Security role for $entityName"
        )

        // ── 9. Messages ──
        val messages = buildMessages(entity, viewIdBase)
        val messagesContent = messages.entries.joinToString("\n") { "${it.key}=${it.value}" }
        val messagesFile = GeneratedFile(
            relativePath = "${config.resourceRoot}/${config.packageToPath(basePkg)}/messages.properties",
            content = messagesContent,
            description = "Localization messages for $entityName"
        )

        // ── 10. Data Repository (optional) ──
        val dataRepositoryFile = if (options.generateDataRepository) {
            val repoContent = DataRepositoryGenerator.generate(entity)
            GeneratedFile(
                relativePath = "${config.sourceRoot}/${config.packageToPath(entityPkg)}/${entityName}Repository.java",
                content = repoContent,
                description = "Data repository for $entityName"
            )
        } else null

        // ── 11. Fetch Plan (optional) ──
        val fetchPlanFile = if (options.generateFetchPlan) {
            val fpContent = generateFetchPlanXml(entity, viewPkg)
            GeneratedFile(
                relativePath = "${config.resourceRoot}/${config.packageToPath(entityPkg)}/${entityName}-fetch-plans.xml",
                content = fpContent,
                description = "Fetch plans for $entityName"
            )
        } else null

        return CrudOutput(
            entityFile = entityFile,
            migrationFile = migrationFile,
            listViewXml = listViewXml,
            listViewController = listViewController,
            detailViewXml = detailViewXml,
            detailViewController = detailViewController,
            menuXml = menuXml,
            roleFile = roleFile,
            messagesFile = messagesFile,
            dataRepositoryFile = dataRepositoryFile,
            fetchPlanFile = fetchPlanFile
        )
    }

    // ─── List View Builder ───────────────────────────────────────────────────

    private fun buildListView(
        entity: EntityModel,
        viewPkg: String,
        viewIdBase: String,
        options: CrudOptions
    ): ViewModel {
        val gridType = when (options.listViewType) {
            ListViewStyle.DATA_GRID -> ComponentType.DATA_GRID
            ListViewStyle.TREE_DATA_GRID -> ComponentType.TREE_DATA_GRID
            ListViewStyle.VIRTUAL_LIST -> ComponentType.VIRTUAL_LIST
        }

        val columns = entity.attributes
            .filter { it.inBaseFetchPlan && !it.transientFlag }
            .filter { it.type != AttributeType.BYTE_ARRAY }
            .map { attr ->
                ColumnModel(
                    property = attr.name,
                    caption = attr.localizedCaption ?: attr.name.replaceFirstChar { c -> c.uppercase() },
                    sortable = true,
                    resizable = true
                )
            }.toMutableList()

        val gridActions = mutableListOf<ActionModel>()
        if (options.includeActions) {
            gridActions.addAll(listOf(
                ActionModel(id = "create", type = ActionType.LIST_CREATE),
                ActionModel(id = "edit", type = ActionType.LIST_EDIT),
                ActionModel(id = "remove", type = ActionType.LIST_REMOVE),
                ActionModel(id = "refresh", type = ActionType.LIST_REFRESH)
            ))
        }

        val layout = ComponentModel(id = "root", type = ComponentType.VBOX).apply {
            properties["expand"] = "dataGrid"
            properties["width"] = "100%"
            properties["height"] = "100%"
            properties["padding"] = "true"

            if (options.includeFilter) {
                children.add(ComponentModel(
                    id = "genericFilter",
                    type = ComponentType.GENERIC_FILTER,
                    dataBinding = "${entity.className.lowercase()}Dl"
                ))
            }

            children.add(ComponentModel(
                id = "dataGrid",
                type = gridType,
                dataBinding = "${entity.className.lowercase()}Dc",
                columns = columns,
                actions = gridActions,
                properties = mutableMapOf(
                    "width" to "100%",
                    "minHeight" to "20em"
                )
            ))

            if (options.includePagination) {
                children.add(ComponentModel(
                    id = "pagination",
                    type = ComponentType.SIMPLE_PAGINATION,
                    dataBinding = "${entity.className.lowercase()}Dl"
                ))
            }
        }

        val fetchPlanProps = entity.attributes
            .filter { it.inBaseFetchPlan && !it.transientFlag }
            .map { FetchPlanProperty(it.name) }
            .toMutableList()

        return ViewModel(
            viewName = "${entity.className}ListView",
            packageName = viewPkg,
            viewType = ViewType.LIST_VIEW,
            entityClass = entity.fullName,
            layout = layout,
            dataContainers = mutableListOf(
                DataContainerModel(
                    id = "${entity.className.lowercase()}Dc",
                    type = DataContainerType.COLLECTION,
                    entityClass = entity.fullName,
                    fetchPlan = FetchPlanModel(
                        name = "${entity.className.lowercase()}_fetch",
                        properties = fetchPlanProps
                    ),
                    loader = DataLoaderModel(
                        id = "${entity.className.lowercase()}Dl",
                        query = "select e from ${entity.resolvedEntityName} e",
                        cacheable = true
                    )
                )
            ),
            facets = mutableListOf(
                FacetModel(
                    type = FacetType.DATA_LOAD_COORDINATOR,
                    properties = mutableMapOf("auto" to "true")
                )
            ),
            menuEntry = MenuEntryModel(
                id = "$viewIdBase-list",
                caption = entity.className,
                viewId = "$viewIdBase-list-view"
            )
        )
    }

    // ─── Detail View Builder ─────────────────────────────────────────────────

    private fun buildDetailView(
        entity: EntityModel,
        viewPkg: String,
        viewIdBase: String,
        options: CrudOptions
    ): ViewModel {
        val formFields = entity.attributes
            .filter { !it.transientFlag && !it.systemLevel }
            .filter { it.type != AttributeType.BYTE_ARRAY }
            .map { attr ->
                val componentType = resolveFieldComponentType(attr)
                ComponentModel(
                    id = "${attr.name}Field",
                    type = componentType,
                    dataBinding = "${entity.className.lowercase()}Dc",
                    propertyBinding = attr.name,
                    properties = mutableMapOf(
                        "label" to "msg://${entity.className}DetailView.${attr.name}Field.label",
                        "width" to "100%"
                    )
                ).apply {
                    if (attr.mandatory) {
                        properties["required"] = "true"
                        properties["requiredMessage"] = "msg://validation.required"
                    }
                    if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                        attr.association?.let { assoc ->
                            properties["optionsContainer"] = "${attr.name}Dc"
                        }
                    }
                }
            }.toMutableList()

        val layout = ComponentModel(id = "root", type = ComponentType.VBOX).apply {
            properties["width"] = "100%"
            properties["padding"] = "true"
            properties["spacing"] = "true"

            when (options.detailViewMode) {
                DetailViewMode.FORM -> {
                    children.add(ComponentModel(
                        id = "form",
                        type = ComponentType.FORM_LAYOUT,
                        dataBinding = "${entity.className.lowercase()}Dc",
                        children = formFields,
                        properties = mutableMapOf("width" to "100%")
                    ))
                }
                DetailViewMode.TABBED -> {
                    val tabSheet = ComponentModel(
                        id = "tabSheet",
                        type = ComponentType.TAB_SHEET,
                        properties = mutableMapOf("width" to "100%")
                    )
                    val generalTab = ComponentModel(
                        id = "generalTab",
                        type = ComponentType.FORM_LAYOUT,
                        dataBinding = "${entity.className.lowercase()}Dc",
                        children = formFields,
                        properties = mutableMapOf("label" to "General")
                    )
                    tabSheet.children.add(generalTab)
                    children.add(tabSheet)
                }
                DetailViewMode.SIDE_PANEL -> {
                    val sidePanel = ComponentModel(
                        id = "sidePanel",
                        type = ComponentType.SIDE_PANEL_LAYOUT,
                        properties = mutableMapOf("width" to "100%")
                    )
                    sidePanel.children.add(ComponentModel(
                        id = "form",
                        type = ComponentType.FORM_LAYOUT,
                        dataBinding = "${entity.className.lowercase()}Dc",
                        children = formFields
                    ))
                    children.add(sidePanel)
                }
            }

            // Action buttons
            children.add(ComponentModel(
                id = "detailActions",
                type = ComponentType.HBOX,
                properties = mutableMapOf("spacing" to "true"),
                children = mutableListOf(
                    ComponentModel(
                        id = "saveBtn",
                        type = ComponentType.BUTTON,
                        properties = mutableMapOf(
                            "action" to "saveAction",
                            "themeNames" to "primary"
                        )
                    ),
                    ComponentModel(
                        id = "closeBtn",
                        type = ComponentType.BUTTON,
                        properties = mutableMapOf("action" to "closeAction")
                    )
                )
            ))
        }

        val fetchPlanProps = entity.attributes
            .filter { !it.transientFlag }
            .map { attr ->
                if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                    FetchPlanProperty(attr.name, FetchPlanModel(name = "_base"))
                } else {
                    FetchPlanProperty(attr.name)
                }
            }.toMutableList()

        val detailActions = mutableListOf<ViewActionModel>()
        if (options.includeActions) {
            detailActions.addAll(listOf(
                ViewActionModel(id = "saveAction", type = ActionType.DETAIL_SAVE, caption = "Save"),
                ViewActionModel(id = "closeAction", type = ActionType.DETAIL_CLOSE, caption = "Close")
            ))
        }

        return ViewModel(
            viewName = "${entity.className}DetailView",
            packageName = viewPkg,
            viewType = ViewType.DETAIL_VIEW,
            entityClass = entity.fullName,
            layout = layout,
            dataContainers = mutableListOf(
                DataContainerModel(
                    id = "${entity.className.lowercase()}Dc",
                    type = DataContainerType.INSTANCE,
                    entityClass = entity.fullName,
                    fetchPlan = FetchPlanModel(
                        name = "${entity.className.lowercase()}_fetch",
                        properties = fetchPlanProps
                    )
                )
            ),
            actions = detailActions,
            dialogConfig = DialogConfig(
                width = "50em",
                resizable = true,
                draggable = true
            )
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun resolveFieldComponentType(attr: AttributeModel): ComponentType {
        return when (attr.type) {
            AttributeType.STRING -> {
                if ((attr.length ?: 0) > 255) ComponentType.TEXT_AREA
                else ComponentType.TEXT_FIELD
            }
            AttributeType.CHARACTER -> ComponentType.TEXT_FIELD
            AttributeType.INTEGER -> ComponentType.INTEGER_FIELD
            AttributeType.LONG -> ComponentType.INTEGER_FIELD
            AttributeType.DOUBLE -> ComponentType.NUMBER_FIELD
            AttributeType.BIG_DECIMAL -> ComponentType.BIG_DECIMAL_FIELD
            AttributeType.BOOLEAN -> ComponentType.CHECKBOX
            AttributeType.DATE -> ComponentType.DATE_PICKER
            AttributeType.LOCAL_DATE -> ComponentType.DATE_PICKER
            AttributeType.LOCAL_DATE_TIME -> ComponentType.DATE_TIME_PICKER
            AttributeType.LOCAL_TIME -> ComponentType.TIME_PICKER
            AttributeType.OFFSET_TIME -> ComponentType.TIME_PICKER
            AttributeType.OFFSET_DATE_TIME -> ComponentType.DATE_TIME_PICKER
            AttributeType.SQL_DATE -> ComponentType.DATE_PICKER
            AttributeType.SQL_TIME -> ComponentType.TIME_PICKER
            AttributeType.UUID -> ComponentType.TEXT_FIELD
            AttributeType.URI -> ComponentType.TEXT_FIELD
            AttributeType.ENUM -> ComponentType.COMBO_BOX
            AttributeType.ASSOCIATION -> {
                attr.association?.let { assoc ->
                    when (assoc.associationType) {
                        AssociationType.MANY_TO_ONE, AssociationType.ONE_TO_ONE ->
                            ComponentType.ENTITY_PICKER
                        AssociationType.MANY_TO_MANY ->
                            ComponentType.MULTI_SELECT_COMBO_BOX
                        AssociationType.ONE_TO_MANY ->
                            ComponentType.DATA_GRID
                    }
                } ?: ComponentType.ENTITY_PICKER
            }
            AttributeType.COMPOSITION -> ComponentType.ENTITY_PICKER
            AttributeType.EMBEDDED -> ComponentType.FORM_LAYOUT
            AttributeType.BYTE_ARRAY -> ComponentType.FILE_UPLOAD_FIELD
            AttributeType.FILE_REF -> ComponentType.FILE_UPLOAD_FIELD
            AttributeType.CUSTOM -> ComponentType.TEXT_FIELD
        }
    }

    private fun buildMessages(entity: EntityModel, viewIdBase: String): MutableMap<String, String> {
        val messages = mutableMapOf<String, String>()

        // Entity messages
        messages["${entity.className}"] = entity.className
        entity.attributes.forEach { attr ->
            val caption = attr.localizedCaption
                ?: attr.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                    .replaceFirstChar { it.uppercase() }
            messages["${entity.className}.${attr.name}"] = caption
        }

        // List view messages
        messages["$viewIdBase-list-view.title"] = "${entity.className} List"
        messages["${entity.className}ListView.title"] = "${entity.className} List"

        // Detail view messages
        messages["$viewIdBase-detail-view.title"] = "${entity.className} Detail"
        messages["${entity.className}DetailView.title"] = "${entity.className} Detail"
        entity.attributes.forEach { attr ->
            val caption = attr.localizedCaption
                ?: attr.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                    .replaceFirstChar { it.uppercase() }
            messages["${entity.className}DetailView.${attr.name}Field.label"] = caption
        }

        // Menu messages
        messages["menu.$viewIdBase-list"] = entity.className

        // Validation
        messages["validation.required"] = "Required field"

        return messages
    }

    private fun generateFetchPlanXml(entity: EntityModel, pkg: String): String {
        val xml = XmlBuilder("fetchPlans")
        xml.noDeclaration()

        xml.root {
            attr("xmlns", "http://jmix.io/schema/core/fetch-plans")
            attr("entity", entity.fullName)

            child("fetchPlan") {
                attr("name", "_base")
                attr("extends", "_local")

                entity.attributes
                    .filter { it.inBaseFetchPlan && !it.transientFlag }
                    .forEach { attr ->
                        child("property") {
                            attr("name", attr.name)
                            if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                                attr("fetchPlan", "_base")
                            }
                        }
                    }
            }

            child("fetchPlan") {
                attr("name", "_full")
                attr("extends", "_base")

                entity.attributes
                    .filter { !it.inBaseFetchPlan && !it.transientFlag }
                    .forEach { attr ->
                        child("property") {
                            attr("name", attr.name)
                            if (attr.type == AttributeType.ASSOCIATION || attr.type == AttributeType.COMPOSITION) {
                                attr("fetchPlan", "_base")
                            }
                        }
                    }
            }
        }

        return xml.build()
    }
}
