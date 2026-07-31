package org.jmixworkbench.services

import org.jmixworkbench.model.DmnAuthoringStatus
import org.jmixworkbench.model.DmnCollectOperator
import org.jmixworkbench.model.DmnConditionModel
import org.jmixworkbench.model.DmnConditionOperator
import org.jmixworkbench.model.DmnDecisionModel
import org.jmixworkbench.model.DmnDecisionRuleModel
import org.jmixworkbench.model.DmnHitPolicy
import org.jmixworkbench.model.DmnInputModel
import org.jmixworkbench.model.DmnOutputModel
import org.jmixworkbench.model.DmnValueType
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Read-only parser for the closed, typed subset represented by the visual DMN
 * designer. Unsupported FEEL/JUEL expressions are reported instead of being
 * interpreted or silently rewritten.
 */
object DmnDecisionParser {
    fun parse(
        xml: String,
        destinationId: String,
        fileName: String,
    ): DmnDecisionParseResult {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrElse {
            return DmnDecisionParseResult(null, listOf("Malformed or unsafe XML: ${it.message.orEmpty()}"))
        }
        val root = document.documentElement
            ?: return DmnDecisionParseResult(null, listOf("The DMN document has no root element."))
        if (root.localTag() != "definitions") {
            return DmnDecisionParseResult(null, listOf("The XML root is not DMN definitions."))
        }
        val decisions = root.descendants("decision").filter { it.descendants("decisionTable").isNotEmpty() }
        if (decisions.size != 1) {
            return DmnDecisionParseResult(
                null,
                listOf("The visual editor supports one decision table per file; this file contains ${decisions.size}."),
            )
        }
        val unsupported = mutableListOf<String>()
        val decision = decisions.single()
        val table = decision.descendants("decisionTable").first()
        val key = decision.attr("id").ifBlank {
            unsupported += "Decision id is missing."
            "decision"
        }
        val hitPolicy = runCatching {
            DmnHitPolicy.valueOf(table.attr("hitPolicy").ifBlank { "UNIQUE" }.uppercase())
        }.getOrElse {
            unsupported += "Unsupported hit policy '${table.attr("hitPolicy")}'."
            DmnHitPolicy.UNIQUE
        }
        val aggregation = table.attr("aggregation")
        val collectOperator = if (aggregation.isBlank()) {
            DmnCollectOperator.NONE
        } else {
            runCatching { DmnCollectOperator.valueOf(aggregation.uppercase()) }.getOrElse {
                unsupported += "Unsupported collect aggregation '$aggregation'."
                DmnCollectOperator.NONE
            }
        }
        val inputs = table.directChildren("input").mapIndexed { index, element ->
            val expression = element.descendants("inputExpression").firstOrNull()
            val variable = expression?.descendants("text")?.firstOrNull()?.textContent?.trim().orEmpty()
            if (!VARIABLE.matches(variable)) {
                unsupported += "Input ${index + 1} uses an expression outside the safe variable subset: '$variable'."
            }
            DmnInputModel(
                id = element.attr("id").ifBlank { "input-${index + 1}" },
                label = element.attr("label").ifBlank { variable.ifBlank { "Input ${index + 1}" } },
                variable = variable.ifBlank { "input${index + 1}" },
                type = valueType(expression?.attr("typeRef").orEmpty(), unsupported),
            )
        }
        val outputs = table.directChildren("output").mapIndexed { index, element ->
            val id = element.attr("id").ifBlank { "output-${index + 1}" }
            val variable = element.attr("name").ifBlank { "output${index + 1}" }
            if (!VARIABLE.matches(variable)) unsupported += "Output ${index + 1} has an unsupported variable '$variable'."
            DmnOutputModel(
                id = id,
                label = element.attr("label").ifBlank { variable },
                variable = variable,
                type = valueType(element.attr("typeRef"), unsupported),
                predefinedValues = element.descendants("outputValues")
                    .firstOrNull()
                    ?.descendants("text")
                    ?.firstOrNull()
                    ?.textContent
                    ?.let(::parseList)
                    .orEmpty(),
            )
        }
        if (inputs.isEmpty()) unsupported += "The decision table has no input columns."
        if (outputs.isEmpty()) unsupported += "The decision table has no output columns."
        val rules = table.directChildren("rule").mapIndexed { ruleIndex, ruleElement ->
            val inputEntries = ruleElement.directChildren("inputEntry")
            val outputEntries = ruleElement.directChildren("outputEntry")
            if (inputEntries.size != inputs.size) {
                unsupported += "Rule ${ruleIndex + 1} does not contain exactly ${inputs.size} input cells."
            }
            if (outputEntries.size != outputs.size) {
                unsupported += "Rule ${ruleIndex + 1} does not contain exactly ${outputs.size} output cells."
            }
            DmnDecisionRuleModel(
                id = ruleElement.attr("id").ifBlank { "rule-${ruleIndex + 1}" },
                description = ruleElement.directChildren("description").firstOrNull()?.textContent?.trim().orEmpty(),
                enabled = true,
                inputEntries = inputs.mapIndexed { index, input ->
                    val raw = inputEntries.getOrNull(index)?.descendants("text")
                        ?.firstOrNull()?.textContent?.trim().orEmpty()
                    input.id to parseCondition(raw, input.type, unsupported, ruleIndex + 1)
                }.toMap(),
                outputEntries = outputs.mapIndexed { index, output ->
                    val raw = outputEntries.getOrNull(index)?.descendants("text")
                        ?.firstOrNull()?.textContent?.trim().orEmpty()
                    output.id to parseLiteral(raw, output.type)
                }.toMap(),
            )
        }
        val model = DmnDecisionModel(
            name = decision.attr("name").ifBlank { key },
            key = key,
            namespace = root.attr("namespace").ifBlank { "http://www.flowable.org/dmn" },
            destinationId = destinationId,
            fileName = fileName,
            hitPolicy = hitPolicy,
            collectOperator = collectOperator,
            inputs = inputs,
            outputs = outputs,
            rules = rules,
            authoringVersion = 1,
            authoringStatus = DmnAuthoringStatus.DRAFT,
            description = decision.directChildren("description").firstOrNull()?.textContent?.trim().orEmpty(),
        )
        return DmnDecisionParseResult(model, unsupported.distinct())
    }

    private fun parseCondition(
        source: String,
        type: DmnValueType,
        unsupported: MutableList<String>,
        ruleIndex: Int,
    ): DmnConditionModel {
        val value = source.trim()
        if (value.isBlank() || value == "-") return DmnConditionModel(DmnConditionOperator.ANY)
        RANGE.matchEntire(value)?.let { match ->
            return DmnConditionModel(
                DmnConditionOperator.BETWEEN,
                parseLiteral(match.groupValues[1], type),
                parseLiteral(match.groupValues[2], type),
            )
        }
        OPERATORS.firstOrNull { value.startsWith(it.first) }?.let { (prefix, operator) ->
            return DmnConditionModel(operator, parseLiteral(value.removePrefix(prefix).trim(), type))
        }
        unsupported += "Rule $ruleIndex contains an unsupported input expression: '$value'."
        return DmnConditionModel(DmnConditionOperator.ANY)
    }

    private fun parseLiteral(source: String, type: DmnValueType): String {
        val value = source.trim()
        return if (type in setOf(DmnValueType.STRING, DmnValueType.DATE) &&
            value.length >= 2 && value.first() == '"' && value.last() == '"'
        ) {
            value.substring(1, value.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            value
        }
    }

    private fun parseList(source: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        source.forEach { character ->
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }
                character == '\\' && quoted -> escaped = true
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    current.toString().trim().takeIf(String::isNotBlank)?.let(values::add)
                    current.setLength(0)
                }
                else -> current.append(character)
            }
        }
        current.toString().trim().takeIf(String::isNotBlank)?.let(values::add)
        return values
    }

    private fun valueType(source: String, unsupported: MutableList<String>): DmnValueType =
        when (source.substringAfter(':').lowercase()) {
            "string", "" -> DmnValueType.STRING
            "number", "integer", "long", "double", "decimal" -> DmnValueType.NUMBER
            "boolean" -> DmnValueType.BOOLEAN
            "date" -> DmnValueType.DATE
            else -> {
                unsupported += "Unsupported DMN type '$source'."
                DmnValueType.STRING
            }
        }

    private fun Element.attr(name: String): String {
        val direct = getAttribute(name)
        if (direct.isNotBlank()) return direct
        val local = name.substringAfter(':')
        val attributes = attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.localName == local || attribute.nodeName.substringAfter(':') == local) {
                return attribute.nodeValue.orEmpty()
            }
        }
        return ""
    }

    private fun Element.localTag(): String = localName ?: tagName.substringAfter(':')

    private fun Element.directChildren(tag: String): List<Element> =
        childNodes.asSequence()
            .filterIsInstance<Element>()
            .filter { it.localTag() == tag }
            .toList()

    private fun Element.descendants(tag: String): List<Element> =
        getElementsByTagNameNS("*", tag).asSequence().filterIsInstance<Element>().toList()

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<Node> =
        (0 until length).asSequence().map(::item)

    private val VARIABLE = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val RANGE = Regex("""^>=\s*(.+?)\s*&&\s*<=\s*(.+)$""")
    private val OPERATORS = listOf(
        ">=" to DmnConditionOperator.GREATER_THAN_OR_EQUAL,
        "<=" to DmnConditionOperator.LESS_THAN_OR_EQUAL,
        "!=" to DmnConditionOperator.NOT_EQUALS,
        "==" to DmnConditionOperator.EQUALS,
        ">" to DmnConditionOperator.GREATER_THAN,
        "<" to DmnConditionOperator.LESS_THAN,
    )
}

data class DmnDecisionParseResult(
    val model: DmnDecisionModel?,
    val unsupported: List<String>,
)
