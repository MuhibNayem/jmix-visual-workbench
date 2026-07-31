package org.jmixworkbench.generator

import org.jmixworkbench.model.EntitySourceLanguage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class EventListenerGeneratorTest {
    @Test
    fun `java listener uses current Jmix events and explicit after commit transaction`() {
        val source = EventListenerGenerator.generate(
            model(EntitySourceLanguage.JAVA, afterCommitTransaction = true),
        )

        assertContains(source, "EntitySavingEvent<LoanApp> event")
        assertContains(source, "EntityLoadingEvent<LoanApp> event")
        assertContains(source, "EntityChangedEvent<LoanApp> event")
        assertContains(source, "@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)")
        assertContains(source, "@Transactional(propagation = Propagation.REQUIRES_NEW)")
        assertContains(source, "@Component(\"loan_LoanAppEventListener\")")
        assertFalse(source.contains("BeforeInsertEntityListener"))
        assertFalse(source.contains("io.jmix.core.entity.EntityListener"))
    }

    @Test
    fun `kotlin listener omits a transaction when after commit data access is disabled`() {
        val source = EventListenerGenerator.generate(
            model(EntitySourceLanguage.KOTLIN, afterCommitTransaction = false),
        )

        assertContains(source, "fun onLoanAppSaving(event: EntitySavingEvent<LoanApp>)")
        assertContains(source, "@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)")
        assertFalse(source.contains("Propagation.REQUIRES_NEW"))
        assertFalse(source.contains("import org.springframework.transaction.annotation.Transactional"))
    }

    private fun model(
        language: EntitySourceLanguage,
        afterCommitTransaction: Boolean,
    ) = EventListenerGenerator.ListenerModel(
        entityClassName = "LoanApp",
        entityQualifiedName = "com.acme.loan.entity.LoanApp",
        listenerClassName = "LoanAppEventListener",
        packageName = "com.acme.loan.listener",
        beanName = "loan_LoanAppEventListener",
        sourceLanguage = language,
        events = EventListenerGenerator.ListenerEvent.entries,
        afterCommitRequiresNewTransaction = afterCommitTransaction,
    )
}
