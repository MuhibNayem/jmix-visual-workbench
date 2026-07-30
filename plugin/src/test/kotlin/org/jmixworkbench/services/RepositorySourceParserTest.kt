package org.jmixworkbench.services

import org.jmixworkbench.model.QueryType
import org.jmixworkbench.model.RepositoryParameterRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepositorySourceParserTest {

    @Test
    fun `parses Java security paging fetch plan aggregate query and hints`() {
        val source = """
            package com.company.payroll.repository;

            import com.company.payroll.entity.Employee;
            import io.jmix.core.repository.ApplyConstraints;
            import io.jmix.core.repository.FetchPlan;
            import io.jmix.core.repository.JmixDataRepository;
            import io.jmix.core.repository.Query;
            import io.jmix.core.repository.QueryHints;
            import jakarta.persistence.QueryHint;
            import org.springframework.data.domain.Page;
            import org.springframework.data.domain.Pageable;
            import org.springframework.data.repository.query.Param;

            @ApplyConstraints(false)
            public interface EmployeeRepository extends JmixDataRepository<Employee, UUID> {

                @FetchPlan("employee-summary")
                Page<Employee> findByNameContainingIgnoreCase(String name, Pageable pageable);

                /** Department totals for the dashboard. */
                @Query(
                    value = "select e.department, count(e) from payroll_Employee e where e.department = :department group by e.department",
                    properties = {"department", "count"}
                )
                @ApplyConstraints
                @QueryHints({@QueryHint(name = "jmix.query.cacheable", value = "true")})
                List<KeyValueEntity> totals(@Param("department") String department);
            }
        """.trimIndent()

        val parsed = assertNotNull(RepositorySourceParser.parse(source, kotlin = false))

        assertEquals("EmployeeRepository", parsed.interfaceName)
        assertEquals("Employee", parsed.entityType)
        assertEquals("UUID", parsed.idType)
        assertFalse(parsed.config.applyConstraints)
        assertTrue(parsed.config.useNamedParameters)
        assertEquals(2, parsed.config.methods.size)
        val derived = parsed.config.methods[0]
        assertEquals(QueryType.DERIVED, derived.queryType)
        assertEquals("employee-summary", derived.fetchPlan)
        assertEquals(RepositoryParameterRole.PAGEABLE, derived.parameters[1].role)
        val aggregate = parsed.config.methods[1]
        assertEquals(QueryType.JPQL, aggregate.queryType)
        assertEquals(listOf("department", "count"), aggregate.queryProperties)
        assertEquals(true, aggregate.applyConstraints)
        assertEquals("department", aggregate.parameters.single().bindingName)
        assertEquals("jmix.query.cacheable", aggregate.queryHints.single().name)
        assertTrue(aggregate.description.orEmpty().contains("Department totals"))
        assertTrue(parsed.methods.all { it.editable })
    }

    @Test
    fun `parses Kotlin nullable Jmix parameters and protects custom methods`() {
        val source = """
            package com.company.loan.repository

            import com.company.loan.entity.LoanApp
            import io.jmix.core.FetchPlan
            import io.jmix.core.repository.JmixDataRepository
            import io.jmix.core.repository.Query
            import org.springframework.data.repository.query.Param

            interface LoanAppRepository : JmixDataRepository<LoanApp, UUID> {
                @Query("select l from loan_LoanApp l where l.number = :number")
                fun findByNumberQuery(@Param("number") number: String, fetchPlan: FetchPlan?): LoanApp?

                @AuditedQuery
                fun findPrivileged(number: String): List<LoanApp>
            }
        """.trimIndent()

        val parsed = assertNotNull(RepositorySourceParser.parse(source, kotlin = true))

        assertEquals(2, parsed.config.methods.size)
        val query = parsed.config.methods[0]
        assertEquals("LoanApp?", query.returnType)
        assertEquals(RepositoryParameterRole.FETCH_PLAN, query.parameters[1].role)
        assertTrue(query.parameters[1].nullable)
        assertTrue(parsed.methods[0].editable)
        assertFalse(parsed.methods[1].editable)
        assertTrue(parsed.methods[1].issue.orEmpty().contains("AuditedQuery"))
    }
}
