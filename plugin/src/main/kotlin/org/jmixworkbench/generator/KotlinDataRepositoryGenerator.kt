package org.jmixworkbench.generator

import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.QueryType

object KotlinDataRepositoryGenerator {

    fun generate(entity: EntityModel): String {
        val config = requireNotNull(entity.dataRepository)
        val repositoryName = config.interfaceName?.trim().orEmpty()
            .ifBlank { "${entity.className}Repository" }
        RepositoryContract.validate(entity, config, repositoryName)

        val imports = linkedSetOf(
            "io.jmix.core.repository.JmixDataRepository",
            "org.springframework.stereotype.Repository",
        )
        val idType = RepositoryContract.idType(entity, kotlin = true, imports)
        config.methods.forEach { method ->
            RepositoryContract.collectMethodImports(method, imports, kotlin = true)
        }
        if (!config.applyConstraints || config.methods.any { it.applyConstraints != null }) {
            imports += "io.jmix.core.repository.ApplyConstraints"
        }

        return buildString {
            append("package ").append(entity.packageName).append("\n\n")
            imports.sorted().forEach { append("import ").append(it).append('\n') }
            append("\n@Repository\n")
            if (!config.applyConstraints) append("@ApplyConstraints(false)\n")
            append("interface ").append(repositoryName)
                .append(" : JmixDataRepository<")
                .append(entity.className).append(", ").append(idType).append("> {\n")
            config.methods.forEach { method ->
                append('\n')
                method.description?.trim()?.takeIf(String::isNotEmpty)?.let { description ->
                    append("    /**\n")
                    description.replace("*/", "* /").lines().forEach {
                        append("     * ").append(it.trimEnd()).append('\n')
                    }
                    append("     */\n")
                }
                if (method.queryType == QueryType.JPQL) {
                    append("    @Query(")
                    if (method.queryProperties.isEmpty()) {
                        append(RepositoryContract.kotlinString(requireNotNull(method.query).trim()))
                    } else {
                        append("value = ")
                            .append(RepositoryContract.kotlinString(requireNotNull(method.query).trim()))
                            .append(", properties = [")
                            .append(method.queryProperties.joinToString(", ") {
                                RepositoryContract.kotlinString(it.trim())
                            })
                            .append("]")
                    }
                    append(")\n")
                }
                method.fetchPlan?.trim()?.takeIf(String::isNotEmpty)?.let {
                    append("    @FetchPlan(")
                        .append(RepositoryContract.kotlinString(it))
                        .append(")\n")
                }
                method.applyConstraints?.let {
                    append("    @ApplyConstraints(").append(it).append(")\n")
                }
                if (method.queryHints.isNotEmpty()) {
                    append("    @QueryHints(value = [")
                    append(method.queryHints.joinToString(", ") {
                        "QueryHint(name = ${RepositoryContract.kotlinString(it.name.trim())}, " +
                            "value = ${RepositoryContract.kotlinString(it.value.trim())})"
                    })
                    append("])\n")
                }
                append("    fun ").append(method.name.trim()).append('(')
                append(method.parameters.joinToString(", ") { parameter ->
                    buildString {
                        val binding = RepositoryContract.bindingFor(method, parameter, config)
                        if (binding != null) {
                            append("@Param(")
                                .append(RepositoryContract.kotlinString(binding))
                                .append(") ")
                        }
                        append(parameter.name.trim()).append(": ")
                        append(RepositoryContract.parameterType(parameter, kotlin = true))
                        if (parameter.nullable) append('?')
                    }
                })
                append("): ")
                    .append(RepositoryContract.kotlinType(method.returnType))
                    .append('\n')
            }
            append("}\n")
        }
    }
}
