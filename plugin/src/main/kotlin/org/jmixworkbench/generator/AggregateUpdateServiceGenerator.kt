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
        val delegateImports = if (model.platformDelegates) {
            """
            import io.jmix.core.RemoveDelegate;
            import io.jmix.core.SaveDelegate;
            """.trimIndent() + "\n"
        } else {
            ""
        }
        val implementsClause = if (model.platformDelegates) {
            " implements SaveDelegate<$entitySimpleName>, RemoveDelegate<$entitySimpleName>"
        } else {
            ""
        }
        val delegateMethods = if (model.platformDelegates) {
            """

                @Override
                $transaction
                public $entitySimpleName save(
                        final $entitySimpleName entity,
                        final SaveContext saveContext
                ) {
                    Objects.requireNonNull(entity, "entity");
                    Objects.requireNonNull(saveContext, "saveContext");
                    return dataManager.save(saveContext).get(entity);
                }

                @Override
                $transaction
                public void remove(final $entitySimpleName entity) {
                    dataManager.remove(Objects.requireNonNull(entity, "entity"));
                }
            """.trimIndent().prependIndent("    ")
        } else {
            ""
        }
        return """
            package ${model.packageName};

            import ${model.entityQualifiedName};
            import io.jmix.core.DataManager;
            import io.jmix.core.SaveContext;
            $delegateImports import org.springframework.stereotype.Component;
            import org.springframework.transaction.annotation.Transactional;

            import java.util.Objects;
            import java.util.Set;

            @Component
            public class ${model.className}$implementsClause {

                private final DataManager dataManager;

                public ${model.className}(final DataManager dataManager) {
                    this.dataManager = dataManager;
                }

                /**
                 * Persists the complete DataContext change set in one transaction.
                 * Uses constrained DataManager; never replace it with unconstrained()
                 * for user-originated aggregate changes.
                 */
                $transaction
                public Set<Object> saveChanges(final SaveContext saveContext) {
                    return dataManager.save(Objects.requireNonNull(saveContext, "saveContext"));
                }
            $delegateMethods
            }
        """.trimIndent()
            .replace("\n             import", "\nimport")
            .replace("\n            \n", "\n\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trimEnd() + "\n"
    }

    private fun generateKotlin(model: AggregateUpdateServiceModel): String {
        val entitySimpleName = model.entityQualifiedName.substringAfterLast('.')
        val transaction = transactionalAnnotation(model.transactionManagerBean)
        val interfaces = if (model.platformDelegates) {
            " : SaveDelegate<$entitySimpleName>, RemoveDelegate<$entitySimpleName>"
        } else {
            ""
        }
        val delegateImports = if (model.platformDelegates) {
            """
            import io.jmix.core.RemoveDelegate
            import io.jmix.core.SaveDelegate
            """.trimIndent() + "\n"
        } else {
            ""
        }
        val delegateMethods = if (model.platformDelegates) {
            """

                $transaction
                override fun save(
                    entity: $entitySimpleName,
                    saveContext: SaveContext,
                ): $entitySimpleName = dataManager.save(saveContext).get(entity)

                $transaction
                override fun remove(entity: $entitySimpleName) {
                    dataManager.remove(entity)
                }
            """.trimIndent().prependIndent("    ")
        } else {
            ""
        }
        return """
            package ${model.packageName}

            import ${model.entityQualifiedName}
            import io.jmix.core.DataManager
            import io.jmix.core.SaveContext
            $delegateImports import org.springframework.stereotype.Component
            import org.springframework.transaction.annotation.Transactional

            @Component
            class ${model.className}(
                private val dataManager: DataManager,
            )$interfaces {

                /**
                 * Persists the complete DataContext change set in one transaction.
                 * Uses constrained DataManager; never replace it with unconstrained()
                 * for user-originated aggregate changes.
                 */
                $transaction
                fun saveChanges(saveContext: SaveContext): Set<Any> =
                    dataManager.save(saveContext)
            $delegateMethods
            }
        """.trimIndent()
            .replace("\n             import", "\nimport")
            .replace("\n            \n", "\n\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trimEnd() + "\n"
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
