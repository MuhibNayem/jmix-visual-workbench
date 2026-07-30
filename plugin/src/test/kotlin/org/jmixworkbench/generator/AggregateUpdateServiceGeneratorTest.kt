package org.jmixworkbench.generator

import org.jmixworkbench.model.EntitySourceLanguage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AggregateUpdateServiceGeneratorTest {
    @Test
    fun `java service preserves the complete save context and registers platform delegates`() {
        val source = AggregateUpdateServiceGenerator.generate(
            model(EntitySourceLanguage.JAVA, platformDelegates = true),
        )

        assertContains(source, "implements SaveDelegate<LoanApp>, RemoveDelegate<LoanApp>")
        assertContains(source, "public Set<Object> saveChanges(final SaveContext saveContext)")
        assertContains(source, "return dataManager.save(Objects.requireNonNull(saveContext, \"saveContext\"));")
        assertContains(source, "public LoanApp save(")
        assertContains(source, "return dataManager.save(saveContext).get(entity);")
        assertContains(source, "public void remove(final LoanApp entity)")
        assertContains(source, "dataManager.remove(Objects.requireNonNull(entity, \"entity\"));")
        assertContains(source, "@Transactional")
        assertFalse(source.contains("dataManager.unconstrained"))
    }

    @Test
    fun `kotlin service uses the entity store transaction manager and remains view compatible`() {
        val source = AggregateUpdateServiceGenerator.generate(
            model(
                EntitySourceLanguage.KOTLIN,
                platformDelegates = false,
                transactionManagerBean = "payrollTransactionManager",
            ),
        )

        assertContains(source, "class LoanAppUpdateService(")
        assertContains(source, """@Transactional("payrollTransactionManager")""")
        assertContains(source, "fun saveChanges(saveContext: SaveContext): Set<Any>")
        assertContains(source, "dataManager.save(saveContext)")
        assertFalse(source.contains("SaveDelegate<LoanApp>"))
        assertFalse(source.contains("RemoveDelegate<LoanApp>"))
        assertFalse(source.contains("dataManager.unconstrained"))
    }

    private fun model(
        language: EntitySourceLanguage,
        platformDelegates: Boolean,
        transactionManagerBean: String? = null,
    ) = AggregateUpdateServiceModel(
        className = "LoanAppUpdateService",
        packageName = "com.acme.loan.service",
        entityQualifiedName = "com.acme.loan.entity.LoanApp",
        sourceLanguage = language,
        transactionManagerBean = transactionManagerBean,
        platformDelegates = platformDelegates,
    )
}
