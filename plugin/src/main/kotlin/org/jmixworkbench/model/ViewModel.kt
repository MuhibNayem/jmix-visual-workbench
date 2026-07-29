package org.jmixworkbench.model

import com.google.gson.annotations.SerializedName

// ─── View ────────────────────────────────────────────────────────────────────

data class ViewModel(
    val viewName: String,
    val packageName: String,
    val viewType: ViewType = ViewType.STANDARD,
    val entityClass: String? = null,
    val controllerClass: String? = null,
    val layout: ComponentModel = ComponentModel(id = "root", type = ComponentType.VBOX),
    val dataContainers: MutableList<DataContainerModel> = mutableListOf(),
    val facets: MutableList<FacetModel> = mutableListOf(),
    val actions: MutableList<ViewActionModel> = mutableListOf(),
    val menuEntry: MenuEntryModel? = null,
    val roles: MutableList<String> = mutableListOf(),
    val urlParameters: MutableList<UrlParameterModel> = mutableListOf(),
    val controllerMethods: MutableList<ControllerMethodModel> = mutableListOf(),
    val fragments: MutableList<FragmentModel> = mutableListOf(),
    val dialogConfig: DialogConfig? = null,
    val messages: MutableMap<String, String> = mutableMapOf()
) {
    val viewId: String get() = viewName.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
    val xmlFileName: String get() = "$viewName.xml"
    val controllerName: String get() = controllerClass ?: "${viewName}Controller"
}

enum class ViewType {
    @SerializedName("standard") STANDARD,
    @SerializedName("listView") LIST_VIEW,
    @SerializedName("detailView") DETAIL_VIEW,
    @SerializedName("blankView") BLANK_VIEW,
    @SerializedName("fragment") FRAGMENT,
    @SerializedName("loginView") LOGIN_VIEW,
    @SerializedName("mainView") MAIN_VIEW
}

// ─── Components ──────────────────────────────────────────────────────────────

data class ComponentModel(
    val id: String,
    val type: ComponentType,
    val properties: MutableMap<String, Any> = mutableMapOf(),
    val children: MutableList<ComponentModel> = mutableListOf(),
    val dataBinding: String? = null,
    val propertyBinding: String? = null,
    val actions: MutableList<ActionModel> = mutableListOf(),
    val columns: MutableList<ColumnModel> = mutableListOf(),
    val cssClasses: MutableList<String> = mutableListOf(),
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val styleName: String? = null,
    val width: String? = null,
    val height: String? = null,
    val colspan: Int? = null,
    val rowspan: Int? = null,
    val expand: String? = null
) {
    fun prop(key: String, value: Any): ComponentModel {
        properties[key] = value
        return this
    }
}

enum class ComponentType(val xmlTag: String, val category: ComponentCategory) {
    // Layouts
    VBOX("vbox", ComponentCategory.LAYOUT),
    HBOX("hbox", ComponentCategory.LAYOUT),
    FORM_LAYOUT("formLayout", ComponentCategory.LAYOUT),
    GRID_LAYOUT("gridLayout", ComponentCategory.LAYOUT),
    SPLIT("split", ComponentCategory.LAYOUT),
    TAB_SHEET("tabSheet", ComponentCategory.LAYOUT),
    ACCORDION("accordion", ComponentCategory.LAYOUT),
    SCROLLER("scroller", ComponentCategory.LAYOUT),
    DETAILS("details", ComponentCategory.LAYOUT),
    CARD("card", ComponentCategory.LAYOUT),
    SIDE_PANEL_LAYOUT("sidePanelLayout", ComponentCategory.LAYOUT),
    FLEX_LAYOUT("flexLayout", ComponentCategory.LAYOUT),
    INITIAL_LAYOUT("initialLayout", ComponentCategory.LAYOUT),

    // Fields
    TEXT_FIELD("textField", ComponentCategory.FIELD),
    TEXT_AREA("textArea", ComponentCategory.FIELD),
    INTEGER_FIELD("integerField", ComponentCategory.FIELD),
    NUMBER_FIELD("numberField", ComponentCategory.FIELD),
    BIG_DECIMAL_FIELD("bigDecimalField", ComponentCategory.FIELD),
    CHECKBOX("checkbox", ComponentCategory.FIELD),
    DATE_PICKER("datePicker", ComponentCategory.FIELD),
    DATE_TIME_PICKER("dateTimePicker", ComponentCategory.FIELD),
    TIME_PICKER("timePicker", ComponentCategory.FIELD),
    COMBO_BOX("comboBox", ComponentCategory.FIELD),
    ENTITY_COMBO_BOX("entityComboBox", ComponentCategory.FIELD),
    ENTITY_PICKER("entityPicker", ComponentCategory.FIELD),
    VALUE_PICKER("valuePicker", ComponentCategory.FIELD),
    MULTI_SELECT_COMBO_BOX("multiSelectComboBox", ComponentCategory.FIELD),
    MULTI_SELECT_COMBO_BOX_PICKER("multiSelectComboBoxPicker", ComponentCategory.FIELD),
    MULTI_SELECT_LIST_BOX("multiSelectListBox", ComponentCategory.FIELD),
    MULTI_VALUE_PICKER("multiValuePicker", ComponentCategory.FIELD),
    RADIO_BUTTON_GROUP("radioButtonGroup", ComponentCategory.FIELD),
    CHECKBOX_GROUP("checkboxGroup", ComponentCategory.FIELD),
    PASSWORD_FIELD("passwordField", ComponentCategory.FIELD),
    EMAIL_FIELD("emailField", ComponentCategory.FIELD),
    CODE_EDITOR("codeEditor", ComponentCategory.FIELD),
    RICH_TEXT_EDITOR("richTextEditor", ComponentCategory.FIELD),
    FILE_UPLOAD_FIELD("fileUploadField", ComponentCategory.FIELD),
    FILE_STORAGE_UPLOAD_FIELD("fileStorageUploadField", ComponentCategory.FIELD),
    SWITCH("switch", ComponentCategory.FIELD),
    SELECT("select", ComponentCategory.FIELD),
    LIST_BOX("listBox", ComponentCategory.FIELD),
    TWIN_COLUMN("twinColumn", ComponentCategory.FIELD),
    PROGRESS_BAR("progressBar", ComponentCategory.FIELD),

    // Data display
    DATA_GRID("dataGrid", ComponentCategory.DATA_DISPLAY),
    TREE_DATA_GRID("treeDataGrid", ComponentCategory.DATA_DISPLAY),
    VIRTUAL_LIST("virtualList", ComponentCategory.DATA_DISPLAY),
    DATA_PAGER("dataPager", ComponentCategory.DATA_DISPLAY),

    // Filter
    GENERIC_FILTER("genericFilter", ComponentCategory.FILTER),
    PROPERTY_FILTER("propertyFilter", ComponentCategory.FILTER),
    MENU_FILTER_FIELD("menuFilterField", ComponentCategory.FILTER),

    // Navigation
    HORIZONTAL_MENU("horizontalMenu", ComponentCategory.NAVIGATION),
    LIST_MENU("listMenu", ComponentCategory.NAVIGATION),
    DRAWER_TOGGLE("drawerToggle", ComponentCategory.NAVIGATION),
    USER_MENU("userMenu", ComponentCategory.NAVIGATION),
    SIMPLE_PAGINATION("simplePagination", ComponentCategory.NAVIGATION),

    // Display
    BUTTON("button", ComponentCategory.DISPLAY),
    LABEL("label", ComponentCategory.DISPLAY),
    SPAN("span", ComponentCategory.DISPLAY),
    H1("h1", ComponentCategory.DISPLAY),
    H2("h2", ComponentCategory.DISPLAY),
    H3("h3", ComponentCategory.DISPLAY),
    H4("h4", ComponentCategory.DISPLAY),
    H5("h5", ComponentCategory.DISPLAY),
    H6("h6", ComponentCategory.DISPLAY),
    IMAGE("image", ComponentCategory.DISPLAY),
    ICON("icon", ComponentCategory.DISPLAY),
    HTML("html", ComponentCategory.DISPLAY),
    IFRAME("iframe", ComponentCategory.DISPLAY),
    NATIVE_LABEL("nativeLabel", ComponentCategory.DISPLAY),
    DIV("div", ComponentCategory.DISPLAY),
    FIELD_SET("fieldSet", ComponentCategory.DISPLAY),
    AVATAR("avatar", ComponentCategory.DISPLAY),
    TOOLTIP("tooltip", ComponentCategory.DISPLAY),
    MARKDOWN("markdown", ComponentCategory.DISPLAY),
    MARKDOWN_EDITOR("markdownEditor", ComponentCategory.DISPLAY),

    // Dropdown
    DROPDOWN_BUTTON("dropdownButton", ComponentCategory.DROPDOWN),
    COMBO_BUTTON("comboButton", ComponentCategory.DROPDOWN),

    // Login
    LOGIN_FORM("loginForm", ComponentCategory.LOGIN),

    // Calendar
    CALENDAR("calendar", ComponentCategory.CALENDAR),

    // Charts
    CHART("chart", ComponentCategory.CHART);

    companion object {
        fun fromXmlTag(tag: String): ComponentType? = entries.find { it.xmlTag == tag }
    }
}

enum class ComponentCategory {
    LAYOUT, FIELD, DATA_DISPLAY, FILTER, NAVIGATION, DISPLAY, DROPDOWN, LOGIN, CALENDAR, CHART
}

// ─── Actions ─────────────────────────────────────────────────────────────────

data class ActionModel(
    val id: String,
    val type: ActionType,
    val caption: String? = null,
    val icon: String? = null,
    val shortcut: String? = null,
    val variant: String? = null
)

data class ViewActionModel(
    val id: String,
    val type: ActionType,
    val targetComponent: String? = null,
    val caption: String? = null,
    val icon: String? = null,
    val handler: String? = null
)

enum class ActionType(val xmlType: String) {
    LIST_CREATE("list_create"),
    LIST_EDIT("list_edit"),
    LIST_REMOVE("list_remove"),
    LIST_REFRESH("list_refresh"),
    LIST_IMPORT("list_import"),
    LIST_EXPORT("list_export"),
    DETAIL_SAVE("detail_save"),
    DETAIL_CLOSE("detail_close"),
    DETAIL_SAVE_CLOSE("detail_saveClose"),
    ENTITY_LOOKUP("entity_lookup"),
    ENTITY_CLEAR("entity_clear"),
    ENTITY_OPEN("entity_open"),
    ENTITY_OPEN_COMPOSITION("entity_openComposition"),
    CREATE("create"),
    EDIT("edit"),
    REMOVE("remove"),
    REFRESH("refresh"),
    EXCEL_EXPORT("excel_export"),
    CUSTOM("custom");

    companion object {
        fun fromXmlType(xml: String): ActionType? = entries.find { it.xmlType == xml }
    }
}

// ─── DataGrid Columns ────────────────────────────────────────────────────────

data class ColumnModel(
    val property: String,
    val caption: String? = null,
    val sortable: Boolean = true,
    val resizable: Boolean = true,
    val frozen: Boolean = false,
    val autoWidth: Boolean = true,
    val width: String? = null,
    val flexGrow: Int? = null,
    val textAlign: String? = null,
    val renderer: String? = null
)

// ─── Data Containers & Loaders ───────────────────────────────────────────────

data class DataContainerModel(
    val id: String,
    val type: DataContainerType,
    val entityClass: String,
    val fetchPlan: FetchPlanModel? = null,
    val loader: DataLoaderModel? = null,
    val nestedContainers: MutableList<DataContainerModel> = mutableListOf()
)

enum class DataContainerType(val xmlTag: String) {
    INSTANCE("instanceContainer"),
    COLLECTION("collectionContainer"),
    KEY_VALUE("keyValueCollectionContainer")
}

data class DataLoaderModel(
    val id: String,
    val query: String? = null,
    val fetchPlan: String? = null,
    val firstResult: Int? = null,
    val maxResults: Int? = null,
    val cacheable: Boolean = false,
    val parameters: MutableList<LoaderParameterModel> = mutableListOf()
)

data class LoaderParameterModel(
    val name: String,
    val value: String,
    val type: String = "string"
)

// ─── Fetch Plans ─────────────────────────────────────────────────────────────

data class FetchPlanModel(
    val name: String = "_base",
    val entityClass: String? = null,
    val properties: MutableList<FetchPlanProperty> = mutableListOf(),
    val extendsFetchPlan: String? = null
)

data class FetchPlanProperty(
    val name: String,
    val fetchPlan: FetchPlanModel? = null,
    val fetchMode: String? = null
)

// ─── Facets ──────────────────────────────────────────────────────────────────

data class FacetModel(
    val type: FacetType,
    val properties: MutableMap<String, Any> = mutableMapOf(),
    val children: MutableList<FacetModel> = mutableListOf()
)

enum class FacetType(val xmlTag: String) {
    DATA_LOAD_COORDINATOR("dataLoadCoordinator"),
    DIALOG("dialog"),
    SCREEN("screen"),
    NOTIFICATION("notification"),
    MESSAGE_DIALOG("messageDialog"),
    OPTION_DIALOG("optionDialog"),
    INPUT_DIALOG("inputDialog"),
    TIMER("timer"),
    CLIPBOARD_TRIGGER("clipboardTrigger"),
    KEY_VALUE_CONTAINER("keyValueContainer"),
    PRESENTATIONS("presentations"),
    SETTINGS("settings"),
    URL_SYNC("urlSync")
}

// ─── Dialog Config ───────────────────────────────────────────────────────────

data class DialogConfig(
    val width: String? = null,
    val height: String? = null,
    val resizable: Boolean = false,
    val draggable: Boolean = false,
    val modal: Boolean = true,
    val closeOnClickOutside: Boolean = false
)

// ─── URL Parameters ──────────────────────────────────────────────────────────

data class UrlParameterModel(
    val name: String,
    val type: String = "string",
    val required: Boolean = false,
    val defaultValue: String? = null
)

// ─── Controller Methods ──────────────────────────────────────────────────────

data class ControllerMethodModel(
    val name: String,
    val annotation: String? = null,
    val returnType: String = "void",
    val parameters: MutableList<MethodParameter> = mutableListOf(),
    val body: String = "",
    val eventType: ControllerEventType? = null,
    val targetComponent: String? = null
)

enum class ControllerEventType {
    @SerializedName("onInit") ON_INIT,
    @SerializedName("onBeforeShow") ON_BEFORE_SHOW,
    @SerializedName("onAfterShow") ON_AFTER_SHOW,
    @SerializedName("onBeforeClose") ON_BEFORE_CLOSE,
    @SerializedName("onAfterClose") ON_AFTER_CLOSE,
    @SerializedName("onInitEntity") ON_INIT_ENTITY,
    @SerializedName("onBeforeSave") ON_BEFORE_SAVE,
    @SerializedName("onAfterSave") ON_AFTER_SAVE,
    @SerializedName("subscribe") SUBSCRIBE,
    @SerializedName("install") INSTALL,
    @SerializedName("supply") SUPPLY
}

// ─── Fragments ───────────────────────────────────────────────────────────────

data class FragmentModel(
    val name: String,
    val packageName: String,
    val layout: ComponentModel = ComponentModel(id = "root", type = ComponentType.VBOX),
    val dataContainers: MutableList<DataContainerModel> = mutableListOf()
)

// ─── Menu ────────────────────────────────────────────────────────────────────

data class MenuEntryModel(
    val id: String,
    val caption: String,
    val parentId: String? = null,
    val icon: String? = null,
    val order: Int = 100,
    val viewId: String? = null,
    val shortcut: String? = null,
    val openedBy: String? = null,
    val type: MenuEntryType? = null,
    val description: String? = null,
    val classNames: String? = null,
    val opened: Boolean = false,
    val bean: String? = null,
    val beanMethod: String? = null,
    val title: String? = null,
)

enum class MenuEntryType {
    MENU,
    VIEW,
    BEAN,
    SEPARATOR,
}
