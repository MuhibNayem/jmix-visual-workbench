package org.jmixworkbench.ide

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JmixNativeEventListenerInspectionTest :
    LightJavaCodeInsightFixtureTestCase() {

    fun testValidJavaEntityAndApplicationEventContractsRemainClean() {
        addEventFrameworkStubs()
        addLoanEntity()
        myFixture.enableInspections(JmixJavaEventListenerInspection())
        myFixture.configureByText(
            "LoanEventListener.java",
            """
            package com.company.payroll;

            import io.jmix.core.DataManager;
            import io.jmix.core.event.EntityChangedEvent;
            import io.jmix.core.event.EntitySavingEvent;
            import org.springframework.context.event.EventListener;
            import org.springframework.stereotype.Component;
            import org.springframework.transaction.annotation.Propagation;
            import org.springframework.transaction.annotation.Transactional;
            import org.springframework.transaction.event.TransactionalEventListener;

            @Component
            public class LoanEventListener {
                private final DataManager dataManager = null;

                @EventListener
                void onSaving(EntitySavingEvent<Loan> event) {
                }

                @TransactionalEventListener
                @Transactional(propagation = Propagation.REQUIRES_NEW)
                void onChanged(EntityChangedEvent<Loan> event) {
                    dataManager.load();
                }

                @EventListener(CustomEvent.class)
                void onCustomEvent() {
                }
            }

            class CustomEvent {
            }
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting()
            .mapNotNull { it.description }
            .filter { "event listener" in it.lowercase() || "Entity" in it }
        assertTrue(descriptions.joinToString("\n"), descriptions.isEmpty())
    }

    fun testJavaInspectionRejectsInvalidEntityEventAndTransactionContracts() {
        addEventFrameworkStubs()
        addLoanEntity()
        myFixture.enableInspections(JmixJavaEventListenerInspection())
        myFixture.configureByText(
            "BrokenLoanEventListener.java",
            """
            package com.company.payroll;

            import io.jmix.core.DataManager;
            import io.jmix.core.event.EntityChangedEvent;
            import io.jmix.core.event.EntityLoadingEvent;
            import io.jmix.core.event.EntitySavingEvent;
            import org.springframework.context.event.EventListener;
            import org.springframework.stereotype.Component;
            import org.springframework.transaction.event.TransactionalEventListener;

            @Component
            public class BrokenLoanEventListener {
                private final DataManager dataManager = null;

                @EventListener
                static void staticListener(EntityChangedEvent<Loan> event) {
                }

                @EventListener
                void raw(EntityChangedEvent event) {
                }

                @EventListener
                void wrongEntity(EntityChangedEvent<String> event) {
                }

                @EventListener
                void tooMany(EntityChangedEvent<Loan> event, Object second) {
                }

                @EventListener
                void noEventType() {
                }

                @TransactionalEventListener
                void saving(EntitySavingEvent<Loan> event) {
                }

                @TransactionalEventListener
                void loading(EntityLoadingEvent<Loan> event) {
                }

                @TransactionalEventListener
                void changed(EntityChangedEvent<Loan> event) {
                    dataManager.load();
                }
            }

            class NotABean {
                @EventListener
                void listener(EntityChangedEvent<Loan> event) {
                }
            }
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting()
            .mapNotNull { it.description }
        assertTrue(descriptions.any { "must belong to a Spring bean" in it })
        assertTrue(descriptions.any { "must be an instance method" in it })
        assertTrue(descriptions.any { "must declare an exact Jmix entity" in it })
        assertTrue(descriptions.any { "generic type must resolve to a Jmix entity" in it })
        assertTrue(descriptions.any { "at most one event parameter" in it })
        assertTrue(descriptions.any { "must declare event classes" in it })
        assertEquals(
            2,
            descriptions.count { "must use @EventListener" in it },
        )
        assertTrue(
            descriptions.any {
                "After-commit EntityChangedEvent data access requires a new transaction" in it
            },
        )
    }

    fun testKotlinEntityEventContractsUseSameFailClosedRules() {
        addEventFrameworkStubs()
        addLoanEntity()
        myFixture.enableInspections(JmixKotlinEventListenerInspection())
        myFixture.configureByText(
            "LoanEventListener.kt",
            """
            package com.company.payroll

            import io.jmix.core.event.EntityChangedEvent
            import io.jmix.core.event.EntitySavingEvent
            import org.springframework.context.event.EventListener
            import org.springframework.stereotype.Component
            import org.springframework.transaction.annotation.Propagation
            import org.springframework.transaction.annotation.Transactional
            import org.springframework.transaction.event.TransactionalEventListener

            @Component
            class LoanEventListener {
                private val loanRepository = LoanRepository()

                @EventListener
                fun onSaving(event: EntitySavingEvent<Loan>) {
                }

                @TransactionalEventListener
                @Transactional(propagation = Propagation.REQUIRES_NEW)
                fun onChanged(event: EntityChangedEvent<Loan>) {
                    loanRepository.load()
                }

                @EventListener
                fun raw(event: EntityChangedEvent) {
                }

                @TransactionalEventListener
                fun unsafe(event: EntityChangedEvent<Loan>) {
                    loanRepository.load()
                }
            }

            class LoanRepository {
                fun load() {
                }
            }
            """.trimIndent(),
        )

        val descriptions = myFixture.doHighlighting()
            .mapNotNull { it.description }
        assertEquals(
            1,
            descriptions.count { "must declare an exact Jmix entity" in it },
        )
        assertEquals(
            1,
            descriptions.count {
                "After-commit EntityChangedEvent data access requires a new transaction" in it
            },
        )
        assertTrue(descriptions.none { "must belong to a Spring bean" in it })
    }

    private fun addLoanEntity() {
        myFixture.addClass(
            """
            package com.company.payroll;
            import io.jmix.core.metamodel.annotation.JmixEntity;
            @JmixEntity
            public class Loan {
            }
            """.trimIndent(),
        )
    }

    private fun addEventFrameworkStubs() {
        myFixture.addClass(
            """
            package io.jmix.core.metamodel.annotation;
            public @interface JmixEntity {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.stereotype;
            public @interface Component {
                String value() default "";
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.context.event;
            public @interface EventListener {
                Class<?>[] value() default {};
                Class<?>[] classes() default {};
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.transaction.event;
            public enum TransactionPhase {
                BEFORE_COMMIT, AFTER_COMMIT, AFTER_ROLLBACK, AFTER_COMPLETION
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.transaction.event;
            public @interface TransactionalEventListener {
                TransactionPhase phase() default TransactionPhase.AFTER_COMMIT;
                Class<?>[] value() default {};
                Class<?>[] classes() default {};
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.transaction.annotation;
            public enum Propagation {
                REQUIRED, REQUIRES_NEW
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.transaction.annotation;
            public @interface Transactional {
                Propagation propagation() default Propagation.REQUIRED;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.core;
            public interface DataManager {
                Object load();
            }
            """.trimIndent(),
        )
        listOf(
            "EntityChangedEvent",
            "EntitySavingEvent",
            "EntityLoadingEvent",
        ).forEach { name ->
            myFixture.addClass(
                """
                package io.jmix.core.event;
                public class $name<E> {
                }
                """.trimIndent(),
            )
        }
    }
}
