package org.jmixworkbench.services

import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.model.AssociationType
import org.jmixworkbench.model.DataRepositoryConfig
import org.jmixworkbench.model.IdType
import org.jmixworkbench.model.MethodParameter
import org.jmixworkbench.model.QueryType
import org.jmixworkbench.model.RepositoryMethod
import org.jmixworkbench.model.RepositoryParameterRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositorySemanticAnalyzerTest {

    @Test
    fun `derived methods resolve nested entity paths operators ordering and parameter contracts`() {
        val (employee, entities) = fixture()
        val response = RepositorySemanticAnalyzer.analyze(
            employee,
            entities,
            DataRepositoryConfig(
                enabled = true,
                methods = mutableListOf(
                    RepositoryMethod(
                        name = "findTop10ByDepartmentCodeContainingIgnoreCaseAndActiveTrueOrderByEmployeeNumberAsc",
                        returnType = "List<Employee>",
                        parameters = mutableListOf(MethodParameter("code", "String")),
                        queryType = QueryType.DERIVED,
                    ),
                ),
            ),
        )

        assertTrue(response.accepted, response.diagnostics.joinToString { it.message })
        assertEquals(
            listOf("department.code", "active", "employeeNumber"),
            response.methods.single().propertyPaths,
        )
        assertEquals(1, response.methods.single().expectedValueParameters)
        assertTrue(response.propertyPaths.any { it.path == "department.code" })
    }

    @Test
    fun `derived methods reject unknown properties invalid operators arity and result types`() {
        val (employee, entities) = fixture()
        val response = RepositorySemanticAnalyzer.analyze(
            employee,
            entities,
            DataRepositoryConfig(
                enabled = true,
                methods = mutableListOf(
                    RepositoryMethod(
                        name = "findByDepartmentCod",
                        returnType = "List<Employee>",
                        parameters = mutableListOf(MethodParameter("code", "String")),
                        queryType = QueryType.DERIVED,
                    ),
                    RepositoryMethod(
                        name = "findByActiveContaining",
                        returnType = "List<Employee>",
                        parameters = mutableListOf(MethodParameter("active", "String")),
                        queryType = QueryType.DERIVED,
                    ),
                    RepositoryMethod(
                        name = "findByEmployeeNumberBetween",
                        returnType = "List<Employee>",
                        parameters = mutableListOf(MethodParameter("from", "String")),
                        queryType = QueryType.DERIVED,
                    ),
                    RepositoryMethod(
                        name = "existsByEmployeeNumber",
                        returnType = "Long",
                        parameters = mutableListOf(MethodParameter("employeeNumber", "String")),
                        queryType = QueryType.DERIVED,
                    ),
                ),
            ),
        )

        assertFalse(response.accepted)
        val codes = response.diagnostics.map { it.code }.toSet()
        assertTrue("JVW-REPOSITORY-DERIVED-PROPERTY" in codes)
        assertTrue("JVW-REPOSITORY-DERIVED-TEXT-OPERATOR" in codes)
        assertTrue("JVW-REPOSITORY-DERIVED-PARAMETER-TYPE" in codes)
        assertTrue("JVW-REPOSITORY-DERIVED-PARAMETER-COUNT" in codes)
        assertTrue("JVW-REPOSITORY-DERIVED-RETURN-TYPE" in codes)
        assertTrue(
            response.diagnostics
                .first { it.code == "JVW-REPOSITORY-DERIVED-PROPERTY" }
                .suggestions
                .contains("department.code"),
        )
    }

    @Test
    fun `JPQL analysis validates joins paths projection shape fetch plans and aliases`() {
        val (employee, entities) = fixture()
        val valid = RepositorySemanticAnalyzer.analyze(
            employee,
            entities,
            DataRepositoryConfig(
                enabled = true,
                methods = mutableListOf(
                    RepositoryMethod(
                        name = "summarizeByDepartment",
                        returnType = "List<KeyValueEntity>",
                        parameters = mutableListOf(
                            MethodParameter(
                                name = "code",
                                type = "String",
                                bindingName = "code",
                            ),
                        ),
                        query = """
                            select d.code, count(e)
                            from payroll_Employee e
                            join e.department d
                            where d.code = :code
                            group by d.code
                        """.trimIndent(),
                        queryType = QueryType.JPQL,
                        queryProperties = mutableListOf("departmentCode", "employeeCount"),
                    ),
                ),
            ),
        )

        assertTrue(valid.accepted, valid.diagnostics.joinToString { it.message })
        assertEquals(RepositoryResultKind.AGGREGATE, valid.methods.single().resultKind)
        assertTrue("department.code" in valid.methods.single().propertyPaths)

        val invalid = RepositorySemanticAnalyzer.analyze(
            employee,
            entities,
            DataRepositoryConfig(
                enabled = true,
                methods = mutableListOf(
                    RepositoryMethod(
                        name = "invalidPaths",
                        returnType = "String",
                        query = """
                            select x.employeeNumber
                            from payroll_Employee e
                            join e.employeeNumber n
                            where e.department.missing = :value
                        """.trimIndent(),
                        queryType = QueryType.JPQL,
                        parameters = mutableListOf(MethodParameter("value", "String")),
                        fetchPlan = "employee-summary",
                    ),
                ),
            ),
        )

        assertFalse(invalid.accepted)
        val codes = invalid.diagnostics.map { it.code }.toSet()
        assertTrue("JVW-REPOSITORY-JPQL-ALIAS" in codes)
        assertTrue("JVW-REPOSITORY-JPQL-JOIN-SCALAR" in codes)
        assertTrue("JVW-REPOSITORY-JPQL-PROPERTY" in codes)
        assertTrue("JVW-REPOSITORY-JPQL-SCALAR-FETCH-PLAN" in codes)
    }

    @Test
    fun `JPQL analysis handles uppercase distinct and outer join syntax`() {
        val (employee, entities) = fixture()
        val response = RepositorySemanticAnalyzer.analyze(
            employee,
            entities,
            DataRepositoryConfig(
                enabled = true,
                methods = mutableListOf(
                    RepositoryMethod(
                        name = "findDistinctEmployeesByDepartment",
                        returnType = "List<Employee>",
                        parameters = mutableListOf(
                            MethodParameter(
                                name = "code",
                                type = "String",
                                bindingName = "code",
                            ),
                        ),
                        query = """
                            SELECT DISTINCT e
                            FROM payroll_Employee e
                            LEFT OUTER JOIN e.department d
                            WHERE d.code = :code
                        """.trimIndent(),
                        queryType = QueryType.JPQL,
                    ),
                ),
            ),
        )

        assertTrue(response.accepted, response.diagnostics.joinToString { it.message })
        assertEquals(RepositoryResultKind.ENTITY, response.methods.single().resultKind)
        assertTrue("department" in response.methods.single().propertyPaths)
        assertTrue("department.code" in response.methods.single().propertyPaths)
    }

    @Test
    fun `security bypasses remain visible without hiding otherwise valid semantics`() {
        val (employee, entities) = fixture()
        val response = RepositorySemanticAnalyzer.analyze(
            employee,
            entities,
            DataRepositoryConfig(
                enabled = true,
                applyConstraints = false,
                methods = mutableListOf(
                    RepositoryMethod(
                        name = "findByEmployeeNumber",
                        returnType = "Employee",
                        parameters = mutableListOf(MethodParameter("employeeNumber", "String")),
                        queryType = QueryType.DERIVED,
                        applyConstraints = false,
                    ),
                ),
            ),
        )

        assertTrue(response.accepted)
        assertEquals(
            2,
            response.diagnostics.count { it.severity == RepositorySemanticSeverity.WARNING },
        )
    }

    private fun fixture(): Pair<SchemaEntitySnapshot, List<SchemaEntitySnapshot>> {
        val department = entity(
            className = "Department",
            entityName = "payroll_Department",
            attributes = listOf(attribute("code", "String", nullable = false)),
        )
        val employee = entity(
            className = "Employee",
            entityName = "payroll_Employee",
            attributes = listOf(
                attribute("employeeNumber", "String", nullable = false),
                attribute("active", "Boolean", nullable = false),
                SchemaEntityAttributeSnapshot(
                    artifactId = "entity:Employee:department",
                    name = "department",
                    javaType = "Department",
                    columnName = "DEPARTMENT_ID",
                    nullable = true,
                    unique = false,
                    association = true,
                    associationDetails = SchemaAssociationSnapshot(
                        associationType = AssociationType.MANY_TO_ONE,
                        relatedEntity = department.qualifiedName,
                    ),
                    moneyCandidate = false,
                ),
            ),
        )
        return employee to listOf(employee, department)
    }

    private fun entity(
        className: String,
        entityName: String,
        attributes: List<SchemaEntityAttributeSnapshot>,
    ) = SchemaEntitySnapshot(
        artifactId = "entity:$className",
        moduleId = "payroll",
        className = className,
        qualifiedName = "com.company.payroll.entity.$className",
        entityName = entityName,
        tableName = className.uppercase(),
        storeName = "main",
        idType = IdType.UUID,
        idColumnName = "ID",
        databaseView = false,
        ddlMode = SchemaDdlMode.CREATE_AND_DROP,
        sourceLocator = SourceLocator(
            relativePath = "payroll/src/main/java/com/company/payroll/entity/$className.java",
            revisionFingerprint = "$className-revision",
        ),
        attributes = attributes,
        migrationCoverage = SchemaMigrationCoverage.COVERED,
        migrationArtifactIds = emptyList(),
    )

    private fun attribute(
        name: String,
        javaType: String,
        nullable: Boolean,
    ) = SchemaEntityAttributeSnapshot(
        artifactId = "attribute:$name",
        name = name,
        javaType = javaType,
        columnName = name.uppercase(),
        nullable = nullable,
        unique = false,
        association = false,
        moneyCandidate = false,
    )
}
