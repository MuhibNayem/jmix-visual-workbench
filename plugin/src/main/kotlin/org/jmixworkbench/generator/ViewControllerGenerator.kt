package org.jmixworkbench.generator

import org.jmixworkbench.model.*

/**
 * Generates Jmix Flow UI view controller Java classes from a ViewModel.
 * Handles: @ViewController, @ViewDescriptor, @Route, @RoutePrefix,
 * component injection (@ViewComponent, @Autowired), event handlers
 * (@Subscribe, @Install, @Supply), lifecycle methods, data container
 * injection, dialog controllers, list/detail view patterns.
 */
object ViewControllerGenerator {

    fun generate(view: ViewModel): String {
        val b = JavaClassBuilder(view.controllerName)
        b.package_(view.packageName)

        // Core imports
        b.import_(
            "io.jmix.flowui.UiComponents",
            "io.jmix.flowui.component.UiComponentUtils",
            "io.jmix.flowui.view.*",
            "org.springframework.beans.factory.annotation.Autowired"
        )

        // Determine base class
        val baseClass = when (view.viewType) {
            ViewType.LIST_VIEW -> {
                b.import_("io.jmix.flowui.view.StandardListView")
                view.entityClass?.let { b.import_(it) }
                "StandardListView<${simpleName(view.entityClass ?: "Object")}>"
            }
            ViewType.DETAIL_VIEW -> {
                b.import_("io.jmix.flowui.view.StandardDetailView")
                view.entityClass?.let { b.import_(it) }
                "StandardDetailView<${simpleName(view.entityClass ?: "Object")}>"
            }
            ViewType.FRAGMENT -> {
                b.import_("io.jmix.flowui.view.Fragment")
                "Fragment"
            }
            ViewType.MAIN_VIEW -> {
                b.import_("io.jmix.flowui.view.MainView")
                "MainView"
            }
            else -> {
                b.import_("io.jmix.flowui.view.StandardView")
                "StandardView"
            }
        }
        b.extends_(baseClass)

        // @ViewController
        b.annotation {
            name = "ViewController"
            importPath = "io.jmix.flowui.view.ViewController"
            value("\"${view.viewId}\"")
        }

        // @ViewDescriptor
        b.annotation {
            name = "ViewDescriptor"
            importPath = "io.jmix.flowui.view.ViewDescriptor"
            value("\"${view.xmlFileName}\"")
        }

        // @Route
        b.annotation {
            name = "Route"
            importPath = "com.vaadin.flow.router.Route"
            value("\"${view.viewId}\"")
        }

        // @RoutePrefix (if package-based)
        b.annotation {
            name = "RoutePrefix"
            importPath = "com.vaadin.flow.router.RoutePrefix"
            value("\"\"")
        }

        // @DialogMode for detail views
        if (view.viewType == ViewType.DETAIL_VIEW && view.dialogConfig != null) {
            b.annotation {
                name = "DialogMode"
                importPath = "io.jmix.flowui.view.DialogMode"
                view.dialogConfig?.let { dlg ->
                    dlg.width?.let { param("width", "\"$it\"") }
                    dlg.height?.let { param("height", "\"$it\"") }
                    param("resizable", dlg.resizable.toString())
                    param("draggable", dlg.draggable.toString())
                    param("modal", dlg.modal.toString())
                }
            }
        }

        // @PrimaryDetailDialog for detail views opened as dialogs
        if (view.viewType == ViewType.DETAIL_VIEW) {
            b.annotation {
                name = "PrimaryDetailDialog"
                importPath = "io.jmix.flowui.view.PrimaryDetailDialog"
            }
        }

        // ── Injected components ──
        collectInjectedComponents(view).forEach { (componentId, componentType) ->
            b.field {
                name = componentId
                type = resolveComponentJavaType(componentType)
                visibility = JavaClassBuilder.Visibility.PROTECTED
                annotation {
                    name = "ViewComponent"
                    importPath = "io.jmix.flowui.view.ViewComponent"
                }
            }
        }

        // ── Injected data containers ──
        view.dataContainers.forEach { dc ->
            val containerType = when (dc.type) {
                DataContainerType.INSTANCE -> "InstanceContainer"
                DataContainerType.COLLECTION -> "CollectionContainer"
                DataContainerType.KEY_VALUE -> "KeyValueContainer"
            }
            b.import_("io.jmix.flowui.model.$containerType")
            b.field {
                name = dc.id
                type = "$containerType<${simpleName(dc.entityClass)}>"
                visibility = JavaClassBuilder.Visibility.PROTECTED
                annotation {
                    name = "ViewComponent"
                    importPath = "io.jmix.flowui.view.ViewComponent"
                }
            }
        }

        // ── Injected data loaders ──
        view.dataContainers.forEach { dc ->
            dc.loader?.let { loader ->
                b.import_("io.jmix.flowui.model.CollectionLoader")
                b.field {
                    name = loader.id
                    type = "CollectionLoader<${simpleName(dc.entityClass)}>"
                    visibility = JavaClassBuilder.Visibility.PROTECTED
                    annotation {
                        name = "ViewComponent"
                        importPath = "io.jmix.flowui.view.ViewComponent"
                    }
                }
            }
        }

        // ── Autowired services ──
        b.field {
            name = "uiComponents"
            type = "UiComponents"
            visibility = JavaClassBuilder.Visibility.PROTECTED
            annotation {
                name = "Autowired"
                importPath = "org.springframework.beans.factory.annotation.Autowired"
            }
        }

        // ── Lifecycle methods ──
        view.controllerMethods.forEach { method ->
            generateControllerMethod(b, method, view)
        }

        // ── Default lifecycle for list/detail views ──
        if (view.viewType == ViewType.LIST_VIEW && view.controllerMethods.none { it.eventType == ControllerEventType.ON_INIT }) {
            b.method {
                name = "onInit"
                returnType = "void"
                visibility = JavaClassBuilder.Visibility.PROTECTED
                annotation {
                    name = "Subscribe"
                    importPath = "io.jmix.flowui.view.Subscribe"
                }
                line("// Initialize list view")
            }
        }

        if (view.viewType == ViewType.DETAIL_VIEW && view.controllerMethods.none { it.eventType == ControllerEventType.ON_INIT_ENTITY }) {
            b.method {
                name = "onInitEntity"
                returnType = "void"
                visibility = JavaClassBuilder.Visibility.PROTECTED
                param("InitEntityEvent<${simpleName(view.entityClass ?: "Object")}>", "event")
                annotation {
                    name = "Subscribe"
                    importPath = "io.jmix.flowui.view.Subscribe"
                }
                line("// Initialize new entity")
            }
        }

        return b.build()
    }

    private fun generateControllerMethod(
        b: JavaClassBuilder,
        method: ControllerMethodModel,
        view: ViewModel
    ) {
        require(method.body.isNotBlank() || method.returnType == "void") {
            "Controller method '${method.name}' returns ${method.returnType} and requires an explicit implementation body."
        }
        b.method {
            name = method.name
            returnType = method.returnType
            visibility = JavaClassBuilder.Visibility.PROTECTED

            method.annotation?.let { ann ->
                annotation {
                    name = ann.removePrefix("@")
                    importPath = "io.jmix.flowui.view.${ann.removePrefix("@")}"
                }
            }

            method.eventType?.let { eventType ->
                when (eventType) {
                    ControllerEventType.ON_INIT -> {
                        annotation {
                            name = "Subscribe"
                            importPath = "io.jmix.flowui.view.Subscribe"
                        }
                    }
                    ControllerEventType.ON_BEFORE_SHOW -> {
                        annotation {
                            name = "Subscribe"
                            importPath = "io.jmix.flowui.view.Subscribe"
                        }
                    }
                    ControllerEventType.ON_AFTER_SHOW -> {
                        annotation {
                            name = "Subscribe"
                            importPath = "io.jmix.flowui.view.Subscribe"
                        }
                    }
                    ControllerEventType.ON_INIT_ENTITY -> {
                        annotation {
                            name = "Subscribe"
                            importPath = "io.jmix.flowui.view.Subscribe"
                        }
                        param("InitEntityEvent<${simpleName(view.entityClass ?: "Object")}>", "event")
                    }
                    ControllerEventType.ON_BEFORE_SAVE -> {
                        annotation {
                            name = "Subscribe"
                            importPath = "io.jmix.flowui.view.Subscribe"
                        }
                        param("BeforeSaveEvent<${simpleName(view.entityClass ?: "Object")}>", "event")
                    }
                    ControllerEventType.ON_AFTER_SAVE -> {
                        annotation {
                            name = "Subscribe"
                            importPath = "io.jmix.flowui.view.Subscribe"
                        }
                        param("AfterSaveEvent<${simpleName(view.entityClass ?: "Object")}>", "event")
                    }
                    ControllerEventType.SUBSCRIBE -> {
                        annotation {
                            name = "Subscribe"
                            importPath = "io.jmix.flowui.view.Subscribe"
                        }
                        method.targetComponent?.let { param("target", "\"$it\"") }
                    }
                    ControllerEventType.INSTALL -> {
                        annotation {
                            name = "Install"
                            importPath = "io.jmix.flowui.view.Install"
                        }
                        method.targetComponent?.let { param("target", "\"$it\"") }
                    }
                    ControllerEventType.SUPPLY -> {
                        annotation {
                            name = "Supply"
                            importPath = "io.jmix.flowui.view.Supply"
                        }
                        method.targetComponent?.let { param("target", "\"$it\"") }
                    }
                    else -> {}
                }
            }

            method.parameters.forEach { p ->
                param(p.type, p.name)
            }

            if (method.body.isNotEmpty()) {
                method.body.lines().forEach { line(it) }
            } else {
                line("// Intentionally empty event hook; add reviewed behavior explicitly.")
            }
        }
    }

    private fun collectInjectedComponents(view: ViewModel): List<Pair<String, ComponentType>> {
        val result = mutableListOf<Pair<String, ComponentType>>()
        fun walk(comp: ComponentModel) {
            if (comp.id != "root") {
                result.add(comp.id to comp.type)
            }
            comp.children.forEach { walk(it) }
        }
        walk(view.layout)
        return result
    }

    private fun resolveComponentJavaType(type: ComponentType): String {
        return when (type) {
            ComponentType.DATA_GRID -> "DataGrid"
            ComponentType.TREE_DATA_GRID -> "TreeDataGrid"
            ComponentType.TEXT_FIELD -> "TypedTextField"
            ComponentType.TEXT_AREA -> "TypedTextArea"
            ComponentType.INTEGER_FIELD -> "TypedTextField<Integer>"
            ComponentType.NUMBER_FIELD -> "TypedTextField<Double>"
            ComponentType.BIG_DECIMAL_FIELD -> "TypedTextField<BigDecimal>"
            ComponentType.CHECKBOX -> "JmixCheckbox"
            ComponentType.DATE_PICKER -> "DatePicker"
            ComponentType.DATE_TIME_PICKER -> "DateTimePicker"
            ComponentType.COMBO_BOX -> "JmixComboBox"
            ComponentType.ENTITY_COMBO_BOX -> "EntityComboBox"
            ComponentType.ENTITY_PICKER -> "EntityPicker"
            ComponentType.GENERIC_FILTER -> "GenericFilter"
            ComponentType.BUTTON -> "JmixButton"
            ComponentType.FORM_LAYOUT -> "JmixFormLayout"
            ComponentType.VBOX -> "JmixVerticalLayout"
            ComponentType.HBOX -> "JmixHorizontalLayout"
            else -> "JmixComponent"
        }
    }

    private fun simpleName(fqcn: String): String = fqcn.substringAfterLast('.')
}
