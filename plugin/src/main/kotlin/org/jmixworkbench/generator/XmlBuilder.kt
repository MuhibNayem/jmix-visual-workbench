package org.jmixworkbench.generator

/**
 * Generic XML document builder with fluent API.
 * Handles namespaces, attributes, nested elements, text content, and comments.
 */
class XmlBuilder(private val rootTag: String) {

    private val root = Element(rootTag)
    private var xmlDeclaration = true
    private var indentSize = 4

    fun noDeclaration() = apply { xmlDeclaration = false }
    fun indent(size: Int) = apply { indentSize = size }

    fun root(block: Element.() -> Unit) = apply { root.apply(block) }

    fun build(): String {
        val sb = StringBuilder()
        if (xmlDeclaration) {
            sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        }
        sb.append(root.toXml(0, indentSize))
        return sb.toString()
    }

    class Element(val tag: String) {
        val attributes = linkedMapOf<String, String>()
        val children = mutableListOf<Any>() // Element or String (text)
        val namespaces = linkedMapOf<String, String>() // prefix -> uri

        fun attr(name: String, value: String) = apply { attributes[name] = value }
        fun attr(name: String, value: Any?) = apply { value?.let { attributes[name] = it.toString() } }
        fun attrIf(name: String, condition: Boolean, value: String) = apply {
            if (condition) attributes[name] = value
        }

        fun ns(prefix: String, uri: String) = apply { namespaces[prefix] = uri }

        fun child(tag: String, block: (Element.() -> Unit)? = null) = apply {
            val el = Element(tag)
            block?.invoke(el)
            children.add(el)
        }

        fun text(content: String) = apply { children.add(content) }

        fun comment(content: String) = apply { children.add("<!-- $content -->") }

        fun children_(tag: String, items: List<Any>, block: Element.(Any) -> Unit) = apply {
            items.forEach { item ->
                val el = Element(tag)
                el.block(item)
                children.add(el)
            }
        }

        fun toXml(depth: Int, indentSize: Int): String {
            val pad = " ".repeat(depth * indentSize)
            val sb = StringBuilder()

            sb.append("$pad<$tag")

            namespaces.forEach { (prefix, uri) ->
                sb.append(""" xmlns:$prefix="$uri"""")
            }
            attributes.forEach { (k, v) ->
                sb.append(""" $k="${escapeXml(v)}"""")
            }

            if (children.isEmpty()) {
                sb.appendLine("/>")
            } else {
                sb.appendLine(">")
                children.forEach { child ->
                    when (child) {
                        is Element -> sb.append(child.toXml(depth + 1, indentSize))
                        is String -> {
                            if (child.startsWith("<!--")) {
                                sb.appendLine("$pad    $child")
                            } else {
                                sb.appendLine("$pad    ${escapeXml(child)}")
                            }
                        }
                    }
                }
                sb.appendLine("$pad</$tag>")
            }
            return sb.toString()
        }

        private fun escapeXml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
