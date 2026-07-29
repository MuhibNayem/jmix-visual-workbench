package org.jmixworkbench.generator

import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.IdType
import org.jmixworkbench.model.QueryType

object KotlinDataRepositoryGenerator {
    fun generate(entity: EntityModel): String {
        val config = requireNotNull(entity.dataRepository)
        val name = config.interfaceName ?: "${entity.className}Repository"
        val imports = linkedSetOf(
            "io.jmix.core.repository.JmixDataRepository",
            "org.springframework.stereotype.Repository",
        )
        val idType = when (entity.id.type) {
            IdType.UUID -> "UUID".also { imports += "java.util.UUID" }
            IdType.LONG -> "Long"
            IdType.INTEGER -> "Int"
            IdType.STRING -> "String"
            IdType.EMBEDDED -> requireNotNull(entity.id.embeddedIdClass).also {
                if ('.' in it) imports += it
            }.substringAfterLast('.')
        }
        val methods = config.methods.joinToString("\n\n") { method ->
            val annotation = when {
                method.query == null || method.queryType == QueryType.DERIVED -> ""
                method.queryType == QueryType.NATIVE -> {
                    imports += "org.springframework.data.jpa.repository.Query"
                    "@Query(${quote(method.query)}, nativeQuery = true)\n    "
                }
                else -> {
                    imports += "io.jmix.core.repository.Query"
                    "@Query(${quote(method.query)})\n    "
                }
            }
            val parameters = method.parameters.joinToString { "${it.name}: ${it.type}" }
            "    ${annotation}fun ${method.name}($parameters): ${method.returnType}"
        }
        return buildString {
            append("package ").append(entity.packageName).append("\n\n")
            imports.sorted().forEach { append("import ").append(it).append('\n') }
            append("\n@Repository\n")
            append("interface ").append(name).append(" : JmixDataRepository<")
                .append(entity.className).append(", ").append(idType).append("> {")
            if (methods.isNotBlank()) append("\n").append(methods).append("\n")
            append("}\n")
        }
    }

    private fun quote(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
