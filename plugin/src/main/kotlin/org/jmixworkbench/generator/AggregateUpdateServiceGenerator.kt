package org.jmixworkbench.generator

import org.jmixworkbench.model.EntitySourceLanguage

/**
 * Generates a transaction-explicit, security-constrained aggregate update
 * service. The incoming SaveContext is passed intact to DataManager so Jmix
 * retains fetch plans, access constraints, hints, composition saves, removals,
 * optimistic locking and returned-instance normalization.
 */
object AggregateUpdateServiceGenerator {
    fun generate(model: AggregateUpdateServiceModel): String =
        when (model.sourceLanguage) {
            EntitySourceLanguage.JAVA -> generateJava(model)
            EntitySourceLanguage.KOTLIN -> generateKotlin(model)
        }

    private fun generateJava(model: AggregateUpdateServiceModel): String {
        val entitySimpleName = model.entityQualifiedName.substringAfterLast('.')
        val transaction = transactionalAnnotation(model.transactionManagerBean)
        val implementsClause = if (model.platformDelegates) {
            " implements SaveDelegate<$entitySimpleName>, RemoveDelegate<$entitySimpleName>"
        } else {
            ""
        }
        return buildString {
            append("package ").append(model.packageName).append(";\n\n")
            append("import ").append(model.entityQualifiedName).append(";\n")
            append("import io.jmix.core.DataManager;\n")
            if (model.platformDelegates) {
                append("import io.jmix.core.RemoveDelegate;\n")
            }
            append("import io.jmix.core.SaveContext;\n")
            if (model.platformDelegates) {
                append("import io.jmix.core.SaveDelegate;\n")
            }
            append("import org.springframework.stereotype.Component;\n")
            append("import org.springframework.transaction.annotation.Transactional;\n\n")
            append("import java.util.Objects;\n")
            append("import java.util.Set;\n\n")
            append("@Component\n")
            append("public class ").append(model.className).append(implementsClause).append(" {\n\n")
            append("    private final DataManager dataManager;\n\n")
            append("    public ").append(model.className)
                .append("(final DataManager dataManager) {\n")
            append("        this.dataManager = dataManager;\n")
            append("    }\n\n")
            append("    /**\n")
            append("     * Persists the complete DataContext change set in one transaction.\n")
            append("     * Uses constrained DataManager; never replace it with unconstrained()\n")
            append("     * for user-originated aggregate changes.\n")
            append("     */\n")
            append("    ").append(transaction).append('\n')
            append("    public Set<Object> saveChanges(final SaveContext saveContext) {\n")
            append("        return dataManager.save(Objects.requireNonNull(saveContext, \"saveContext\"));\n")
            append("    }\n")
            if (model.platformDelegates) {
                append("\n")
                append("    @Override\n")
                append("    ").append(transaction).append('\n')
                append("    public ").append(entitySimpleName).append(" save(\n")
                append("            final ").append(entitySimpleName).append(" entity,\n")
                append("            final SaveContext saveContext\n")
                append("    ) {\n")
                append("        Objects.requireNonNull(entity, \"entity\");\n")
                append("        Objects.requireNonNull(saveContext, \"saveContext\");\n")
                append("        return dataManager.save(saveContext).get(entity);\n")
                append("    }\n\n")
                append("    @Override\n")
                append("    ").append(transaction).append('\n')
                append("    public void remove(final ").append(entitySimpleName).append(" entity) {\n")
                append("        dataManager.remove(Objects.requireNonNull(entity, \"entity\"));\n")
                append("    }\n")
            }
            append("}\n")
        }
    }

    private fun generateKotlin(model: AggregateUpdateServiceModel): String {
        val entitySimpleName = model.entityQualifiedName.substringAfterLast('.')
        val transaction = transactionalAnnotation(model.transactionManagerBean)
        val interfaces = if (model.platformDelegates) {
            " : SaveDelegate<$entitySimpleName>, RemoveDelegate<$entitySimpleName>"
        } else {
            ""
        }
        return buildString {
            append("package ").append(model.packageName).append("\n\n")
            append("import ").append(model.entityQualifiedName).append('\n')
            append("import io.jmix.core.DataManager\n")
            if (model.platformDelegates) {
                append("import io.jmix.core.RemoveDelegate\n")
            }
            append("import io.jmix.core.SaveContext\n")
            if (model.platformDelegates) {
                append("import io.jmix.core.SaveDelegate\n")
            }
            append("import org.springframework.stereotype.Component\n")
            append("import org.springframework.transaction.annotation.Transactional\n\n")
            append("@Component\n")
            append("class ").append(model.className).append("(\n")
            append("    private val dataManager: DataManager,\n")
            append(")").append(interfaces).append(" {\n\n")
            append("    /**\n")
            append("     * Persists the complete DataContext change set in one transaction.\n")
            append("     * Uses constrained DataManager; never replace it with unconstrained()\n")
            append("     * for user-originated aggregate changes.\n")
            append("     */\n")
            append("    ").append(transaction).append('\n')
            append("    fun saveChanges(saveContext: SaveContext): Set<Any> =\n")
            append("        dataManager.save(saveContext)\n")
            if (model.platformDelegates) {
                append("\n")
                append("    ").append(transaction).append('\n')
                append("    override fun save(\n")
                append("        entity: ").append(entitySimpleName).append(",\n")
                append("        saveContext: SaveContext,\n")
                append("    ): ").append(entitySimpleName)
                    .append(" = dataManager.save(saveContext).get(entity)\n\n")
                append("    ").append(transaction).append('\n')
                append("    override fun remove(entity: ").append(entitySimpleName).append(") {\n")
                append("        dataManager.remove(entity)\n")
                append("    }\n")
            }
            append("}\n")
        }
    }

    private fun transactionalAnnotation(transactionManagerBean: String?): String =
        transactionManagerBean
            ?.let { """@Transactional("${escapeString(it)}")""" }
            ?: "@Transactional"

    private fun escapeString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

data class AggregateUpdateServiceModel(
    val className: String,
    val packageName: String,
    val entityQualifiedName: String,
    val sourceLanguage: EntitySourceLanguage,
    val transactionManagerBean: String?,
    val platformDelegates: Boolean,
)
