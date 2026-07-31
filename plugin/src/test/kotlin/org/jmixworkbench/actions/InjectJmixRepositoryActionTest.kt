package org.jmixworkbench.actions

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jmixworkbench.services.WorkspaceMutationPhase
import org.jmixworkbench.services.WorkspaceMutationProbe
import java.nio.file.Files
import java.nio.file.Path

class InjectJmixRepositoryActionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package io.jmix.core.repository;
            public interface JmixDataRepository<E, ID> {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package io.jmix.core.repository;
            public @interface ApplyConstraints {
                boolean value() default true;
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.data.repository;
            public @interface NoRepositoryBean {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package org.springframework.beans.factory.annotation;
            public @interface Autowired {
            }
            """.trimIndent(),
        )
    }

    fun testDiscoversCustomGenericHierarchyAndInjectsJavaLosslessly() {
        addEntityAndGenericRepository()
        myFixture.configureByText(
            "PayrollController.java",
            """
            package com.company.payroll.view;

            public class PayrollController {
                // Handwritten lifecycle logic must survive repository injection.
                private final String auditMarker = "preserve-me";
                <caret>
            }
            """.trimIndent(),
        )

        val service = NativeRepositoryInjectionService(project)
        val target = requireNotNull(service.target(myFixture.file, myFixture.caretOffset))
        assertEquals(NativeInjectionLanguage.JAVA, target.language)
        val candidate = service.candidates(target).single()
        assertEquals(
            "com.company.payroll.repository.EmployeeRepository",
            candidate.qualifiedName,
        )
        assertEquals("com.company.payroll.entity.Employee", candidate.entityQualifiedName)
        assertEquals(true, candidate.constraintsApplied)

        val first = service.inject(target, candidate)
        assertTrue(first.message, first.accepted)
        assertTrue(myFixture.file.text.contains("private EmployeeRepository employeeRepository;"))
        assertTrue(myFixture.file.text.contains("@Autowired"))
        assertTrue(myFixture.file.text.contains("preserve-me"))
        assertTrue(myFixture.file.text.contains("Handwritten lifecycle logic"))

        val second = service.inject(target, candidate)
        assertTrue(second.message, second.accepted)
        assertEquals(1, Regex("""\bEmployeeRepository\s+employeeRepository\b""")
            .findAll(myFixture.file.text).count())

        myFixture.configureByText(
            "ConstructorInjectedController.java",
            """
            package com.company.payroll.view;

            import com.company.payroll.repository.EmployeeRepository;

            public class ConstructorInjectedController {
                public ConstructorInjectedController(EmployeeRepository employeeRepository) {
                }
                <caret>
            }
            """.trimIndent(),
        )
        val constructorTarget = requireNotNull(
            service.target(myFixture.file, myFixture.caretOffset),
        )
        val constructorCandidate = service.candidates(constructorTarget).single()
        val existing = service.inject(constructorTarget, constructorCandidate)
        assertTrue(existing.message, existing.accepted)
        assertTrue(existing.message.contains("already injected"))
        assertEquals(1, Regex("""EmployeeRepository\s+employeeRepository\b""")
            .findAll(myFixture.file.text).count())
        assertFalse(myFixture.file.text.contains("@Autowired"))
    }

    fun testInjectsKotlinWithPsiAndRecognizesExistingConstructorInjection() {
        addEntityAndGenericRepository()
        myFixture.configureByText(
            "PayrollController.kt",
            """
            package com.company.payroll.view

            // Keep this documentation and the expression unchanged.
            class PayrollController<caret>
            """.trimIndent(),
        )

        val service = NativeRepositoryInjectionService(project)
        val target = requireNotNull(service.target(myFixture.file, myFixture.caretOffset))
        assertEquals(NativeInjectionLanguage.KOTLIN, target.language)
        val candidate = service.candidates(target).single()

        val first = service.inject(target, candidate)
        assertTrue(first.message, first.accepted)
        assertTrue(
            myFixture.file.text.contains(
                "private lateinit var employeeRepository: com.company.payroll.repository.EmployeeRepository",
            ),
        )
        assertTrue(myFixture.file.text.contains("Keep this documentation"))

        val second = service.inject(target, candidate)
        assertTrue(second.message, second.accepted)
        assertEquals(1, Regex("""lateinit var employeeRepository\b""")
            .findAll(myFixture.file.text).count())

        myFixture.configureByText(
            "ConstructorInjectedController.kt",
            """
            package com.company.payroll.view

            import com.company.payroll.repository.EmployeeRepository as PayrollRepository

            class ConstructorInjectedController(
                private val employeeRepository: PayrollRepository,
            ) {
                <caret>
            }
            """.trimIndent(),
        )
        val constructorTarget = requireNotNull(
            service.target(myFixture.file, myFixture.caretOffset),
        )
        val constructorCandidate = service.candidates(constructorTarget).single()
        val existing = service.inject(constructorTarget, constructorCandidate)
        assertTrue(existing.message, existing.accepted)
        assertTrue(existing.message.contains("already injected"))
        assertEquals(1, Regex("""employeeRepository:\s*PayrollRepository""")
            .findAll(myFixture.file.text).count())
        assertFalse(myFixture.file.text.contains("lateinit var"))
    }

    fun testEvaluatesInheritedDisabledAndUnprovenConstraintPolicies() {
        myFixture.addClass(
            """
            package com.company.payroll.entity;
            public class Employee {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.payroll.repository;

            import io.jmix.core.repository.ApplyConstraints;
            import io.jmix.core.repository.JmixDataRepository;
            import org.springframework.data.repository.NoRepositoryBean;

            @NoRepositoryBean
            @ApplyConstraints(false)
            public interface DisabledBase<T, ID> extends JmixDataRepository<T, ID> {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.payroll.repository;

            import java.util.UUID;

            public interface DisabledRepository
                extends DisabledBase<com.company.payroll.entity.Employee, UUID> {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.payroll.repository;

            import io.jmix.core.repository.ApplyConstraints;
            import io.jmix.core.repository.JmixDataRepository;
            import java.util.UUID;

            @ApplyConstraints(UNKNOWN_POLICY)
            public interface UnprovenRepository
                extends JmixDataRepository<com.company.payroll.entity.Employee, UUID> {
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "SecurityAwareController.java",
            """
            package com.company.payroll.view;
            public class SecurityAwareController {
                <caret>
            }
            """.trimIndent(),
        )

        val service = NativeRepositoryInjectionService(project)
        val target = requireNotNull(service.target(myFixture.file, myFixture.caretOffset))
        val candidates = service.candidates(target).associateBy { it.qualifiedName }
        assertEquals(
            false,
            candidates.getValue(
                "com.company.payroll.repository.DisabledRepository",
            ).constraintsApplied,
        )
        assertNull(
            candidates.getValue(
                "com.company.payroll.repository.UnprovenRepository",
            ).constraintsApplied,
        )
    }

    fun testInjectionFailureRestoresExactSourceAndLockedRecheckPreservesConcurrentEdit() {
        addEntityAndGenericRepository()
        myFixture.configureByText(
            "FailureSafeController.java",
            """
            package com.company.payroll.view;

            public class FailureSafeController {
                // Preserve this handwritten controller exactly.
                <caret>
            }
            """.trimIndent(),
        )
        val service = NativeRepositoryInjectionService(project)
        val target = requireNotNull(service.target(myFixture.file, myFixture.caretOffset))
        val candidate = service.candidates(target).single()
        val original = myFixture.file.text

        val failed = service.inject(
            target,
            candidate,
            WorkspaceMutationProbe { event ->
                if (event.phase == WorkspaceMutationPhase.AFTER_FILE_MUTATION) {
                    throw IllegalStateException("Injected PSI mutation failure")
                }
            },
        )
        assertFalse(failed.accepted)
        assertEquals(original, myFixture.file.text)

        val manualSuffix = "\n// Concurrent developer edit\n"
        val stale = service.inject(
            target,
            candidate,
            WorkspaceMutationProbe { event ->
                if (event.phase == WorkspaceMutationPhase.AFTER_OUTER_PREFLIGHT) {
                    WriteCommandAction.runWriteCommandAction(project) {
                        myFixture.editor.document.insertString(
                            myFixture.editor.document.textLength,
                            manualSuffix,
                        )
                    }
                }
            },
        )
        assertFalse(stale.accepted)
        assertTrue(stale.message.contains("changed before IntelliJ obtained write access"))
        assertEquals(original + manualSuffix, myFixture.editor.document.text)
        assertFalse(myFixture.file.text.contains("@Autowired"))
    }

    fun testActionIsPackagedInSharedAndBothHostDescriptors() {
        val pluginRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first {
                Files.isRegularFile(
                    it.resolve("src/main/resources/META-INF/plugin.xml"),
                ) && Files.isDirectory(it.resolve("hosts/idea253"))
            }
        listOf(
            pluginRoot.resolve("src/main/resources/META-INF/plugin.xml"),
            pluginRoot.resolve("hosts/idea253/src/main/resources/META-INF/plugin.xml"),
            pluginRoot.resolve("hosts/idea262/src/main/resources/META-INF/plugin.xml"),
        ).forEach { descriptor ->
            val text = Files.readString(descriptor)
            assertTrue(
                "$descriptor does not register native repository injection",
                text.contains("""id="JmixWorkbench.InjectRepository""""),
            )
            assertTrue(
                "$descriptor does not expose repository injection in Generate",
                text.contains("""group-id="GenerateGroup""""),
            )
        }
    }

    private fun addEntityAndGenericRepository() {
        myFixture.addClass(
            """
            package com.company.payroll.entity;
            public class Employee {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.shared.repository;

            import io.jmix.core.repository.JmixDataRepository;
            import org.springframework.data.repository.NoRepositoryBean;

            @NoRepositoryBean
            public interface EnterpriseRepository<T, ID>
                extends JmixDataRepository<T, ID> {
            }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.company.payroll.repository;

            import com.company.payroll.entity.Employee;
            import com.company.shared.repository.EnterpriseRepository;
            import java.util.UUID;

            public interface EmployeeRepository
                extends EnterpriseRepository<Employee, UUID> {
            }
            """.trimIndent(),
        )
    }
}
