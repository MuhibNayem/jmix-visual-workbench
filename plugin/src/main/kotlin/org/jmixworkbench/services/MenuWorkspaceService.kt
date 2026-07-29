package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.SourceLocator
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class MenuWorkspaceResponse(
    val sources: List<MenuSourceSnapshot>,
    val warnings: List<String> = emptyList(),
)

data class MenuSourceSnapshot(
    val moduleId: String,
    val relativePath: String,
    val rootElement: String,
    val namespace: String?,
    val sourceLocator: SourceLocator,
    val nodes: List<MenuNodeSnapshot>,
    val nodeCount: Int,
    val maximumDepth: Int,
    val warnings: List<String> = emptyList(),
)

data class MenuNodeSnapshot(
    val id: String,
    val kind: String,
    val caption: String,
    val titleExpression: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val classNames: String? = null,
    val opened: Boolean = false,
    val viewId: String? = null,
    val shortcut: String? = null,
    val openedBy: String? = null,
    val bean: String? = null,
    val beanMethod: String? = null,
    val order: Int,
    val syntheticId: Boolean = false,
    val properties: Map<String, String> = emptyMap(),
    val routeParameters: Map<String, String> = emptyMap(),
    val urlQueryParameters: Map<String, String> = emptyMap(),
    val preservedAttributes: Map<String, String> = emptyMap(),
    val children: List<MenuNodeSnapshot> = emptyList(),
)

/**
 * Loads every indexed application menu from every IntelliJ module/content root.
 *
 * Parsing is namespace-aware, XXE-safe and intentionally operates on direct
 * children so repeated ids in separate branches never flatten the hierarchy.
 */
@Service(Service.Level.PROJECT)
class MenuWorkspaceService(private val project: Project) {

    fun load(): MenuWorkspaceResponse {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val menuArtifacts = graph.artifacts
            .filter { it.kind == ArtifactKind.MENU_SOURCE }
            .distinctBy { it.sourceLocator.relativePath }
            .sortedWith(compareBy({ it.owner.moduleId }, { it.sourceLocator.relativePath }))

        val warnings = mutableListOf<String>()
        val sources = menuArtifacts.mapNotNull { artifact ->
            val locator = artifact.sourceLocator
            val resolved = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath)?.file
            if (resolved == null || resolved.isDirectory) {
                warnings += "Indexed menu source no longer exists: ${locator.relativePath}"
                return@mapNotNull null
            }
            if (resolved.length > MAX_MENU_BYTES) {
                warnings += "Menu source exceeds the ${MAX_MENU_BYTES / 1024 / 1024} MB safety limit: ${locator.relativePath}"
                return@mapNotNull null
            }
            val xml = String(resolved.contentsToByteArray(false), resolved.charset)
            val document = parseXml(xml)
            if (document == null) {
                warnings += "Menu source is malformed and was not loaded: ${locator.relativePath}"
                return@mapNotNull null
            }
            val root = document.documentElement
            val rootName = root.localTag()
            if (rootName !in ROOT_ELEMENTS) {
                warnings += "Unsupported menu root <$rootName>: ${locator.relativePath}"
                return@mapNotNull null
            }
            val sourceWarnings = mutableListOf<String>()
            val counter = ParseCounter()
            val nodes = parseChildren(root, mutableListOf(), counter, sourceWarnings)
            MenuSourceSnapshot(
                moduleId = artifact.owner.moduleId,
                relativePath = locator.relativePath,
                rootElement = rootName,
                namespace = root.namespaceURI?.takeIf(String::isNotBlank),
                sourceLocator = locator,
                nodes = nodes,
                nodeCount = counter.count,
                maximumDepth = counter.maximumDepth,
                warnings = sourceWarnings.distinct(),
            )
        }
        return MenuWorkspaceResponse(sources = sources, warnings = warnings.distinct())
    }

    private fun parseChildren(
        parent: Element,
        ancestry: MutableList<Int>,
        counter: ParseCounter,
        warnings: MutableList<String>,
    ): List<MenuNodeSnapshot> {
        val nodes = mutableListOf<MenuNodeSnapshot>()
        val children = parent.childNodes
        var visualIndex = 0
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            val tag = element.localTag()
            if (tag !in NODE_ELEMENTS) {
                warnings += "Preserved unsupported <$tag> element under ${parent.attr("id").ifBlank { parent.localTag() }}."
                continue
            }
            val path = ancestry + visualIndex
            visualIndex += 1
            counter.count += 1
            counter.maximumDepth = maxOf(counter.maximumDepth, path.size)

            val declaredId = element.attr("id")
            val syntheticId = declaredId.isBlank()
            val id = declaredId.ifBlank { "__${tag}_${path.joinToString("_")}" }
            val title = element.attr("title").takeIf(String::isNotBlank)
            val view = element.attr("view").takeIf(String::isNotBlank)
            val bean = element.attr("bean").takeIf(String::isNotBlank)
            val kind = when {
                tag == "menu" -> "menu"
                tag == "separator" -> "separator"
                bean != null -> "bean"
                else -> "view"
            }
            val known = when (kind) {
                "menu" -> MENU_ATTRIBUTES
                "view" -> ITEM_ATTRIBUTES
                "bean" -> ITEM_ATTRIBUTES
                else -> emptySet()
            }
            val attributes = buildMap {
                val named = element.attributes
                for (attributeIndex in 0 until named.length) {
                    val attribute = named.item(attributeIndex)
                    val name = attribute.localName ?: attribute.nodeName
                    if (name !in known && !name.startsWith("xmlns")) {
                        put(attribute.nodeName, attribute.nodeValue)
                    }
                }
            }
            nodes += MenuNodeSnapshot(
                id = id,
                kind = kind,
                caption = title ?: declaredId.ifBlank {
                    if (tag == "separator") "Separator" else view ?: bean ?: "Unnamed menu node"
                },
                titleExpression = title,
                description = element.attr("description").takeIf(String::isNotBlank),
                icon = element.attr("icon").takeIf(String::isNotBlank),
                classNames = element.attr("classNames").ifBlank { element.attr("className") }.takeIf(String::isNotBlank),
                opened = element.attr("opened").equals("true", ignoreCase = true),
                viewId = view,
                shortcut = element.attr("shortcutCombination")
                    .ifBlank { element.attr("shortcut") }
                    .takeIf(String::isNotBlank),
                openedBy = element.attr("openedBy").takeIf(String::isNotBlank),
                bean = bean,
                beanMethod = element.attr("beanMethod").takeIf(String::isNotBlank),
                order = (visualIndex) * 10,
                syntheticId = syntheticId,
                properties = parameters(element, "properties", "property"),
                routeParameters = parameters(element, "routeParameters", "parameter"),
                urlQueryParameters = parameters(element, "urlQueryParameters", "parameter"),
                preservedAttributes = attributes,
                children = if (tag == "menu") parseChildren(element, path.toMutableList(), counter, warnings) else emptyList(),
            )
        }
        return nodes
    }

    private fun parameters(parent: Element, containerTag: String, itemTag: String): Map<String, String> {
        val container = parent.directChildren().firstOrNull { it.localTag() == containerTag } ?: return emptyMap()
        return container.directChildren()
            .filter { it.localTag() == itemTag }
            .mapNotNull { parameter ->
                parameter.attr("name").takeIf(String::isNotBlank)?.let { it to parameter.attr("value") }
            }
            .toMap(LinkedHashMap())
    }

    private fun parseXml(content: String): Document? =
        runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isXIncludeAware = false
            factory.setExpandEntityReferences(false)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(InputSource(StringReader(content)))
        }.getOrNull()

    private fun Element.attr(name: String): String = getAttribute(name).trim()
    private fun Element.localTag(): String = (localName ?: tagName.substringAfter(':')).trim()
    private fun Element.directChildren(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private data class ParseCounter(
        var count: Int = 0,
        var maximumDepth: Int = 0,
    )

    companion object {
        private const val MAX_MENU_BYTES = 8L * 1024L * 1024L
        private val ROOT_ELEMENTS = setOf("menu-config", "menu")
        private val NODE_ELEMENTS = setOf("menu", "item", "separator")
        private val MENU_ATTRIBUTES = setOf(
            "id", "title", "description", "icon", "classNames", "className", "opened",
        )
        private val ITEM_ATTRIBUTES = setOf(
            "id", "title", "description", "icon", "classNames", "className",
            "view", "bean", "beanMethod", "shortcutCombination", "shortcut", "openedBy",
        )

        fun getInstance(project: Project): MenuWorkspaceService =
            project.getService(MenuWorkspaceService::class.java)
    }
}
