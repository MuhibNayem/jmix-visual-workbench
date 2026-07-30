package org.jmixworkbench.generator

import org.jmixworkbench.model.EntityGenerationTarget
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.DdlGenerationConfig
import org.jmixworkbench.model.DdlGenerationMode
import org.jmixworkbench.model.DatabaseType
import org.jmixworkbench.model.DataRepositoryConfig
import org.jmixworkbench.model.IdConfig
import org.jmixworkbench.model.IdType
import org.jmixworkbench.model.IndexModel
import org.jmixworkbench.model.ProjectConfig
import org.jmixworkbench.model.TraitType
import org.jmixworkbench.model.AttributeModel
import org.jmixworkbench.model.AttributeType
import org.jmixworkbench.model.AssociationCollectionType
import org.jmixworkbench.model.AssociationConfig
import org.jmixworkbench.model.AssociationJoinColumn
import org.jmixworkbench.model.AssociationType
import org.jmixworkbench.model.CascadeType
import org.jmixworkbench.model.DtoConfig
import org.jmixworkbench.model.EntityType
import org.jmixworkbench.model.EnumConfig
import org.jmixworkbench.model.EnumIdType
import org.jmixworkbench.model.EnumValueModel
import org.jmixworkbench.model.EmbeddedAssociationOverride
import org.jmixworkbench.model.EmbeddedAttributeOverride
import org.jmixworkbench.model.InheritanceConfig
import org.jmixworkbench.model.InheritanceRole
import org.jmixworkbench.model.InheritanceStrategy
import org.jmixworkbench.model.JoinTableConfig
import org.jmixworkbench.model.MethodParameter
import org.jmixworkbench.model.QueryType
import org.jmixworkbench.model.RepositoryMethod
import org.jmixworkbench.model.RepositoryParameterRole
import org.jmixworkbench.model.RepositoryQueryHint
import org.jmixworkbench.model.UniqueConstraintModel
import org.jmixworkbench.model.ValidationModel
import org.jmixworkbench.model.ValidationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EntityAndCrudGeneratorTest {
    @Test
    fun `Java and Kotlin generate root subtype and embedded override contracts`() {
        val root = EntityModel(
            className = "Payment",
            packageName = "com.company.payments.entity",
            inheritance = InheritanceConfig(
                role = InheritanceRole.ROOT,
                strategy = InheritanceStrategy.JOINED,
                discriminatorColumn = "PAYMENT_KIND",
                discriminatorType = "STRING",
                discriminatorLength = 24,
                discriminatorValue = "BASE",
            ),
        )
        val subtype = EntityModel(
            className = "WirePayment",
            packageName = "com.company.payments.entity",
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            extendsClass = "com.company.payments.entity.Payment",
            inheritance = InheritanceConfig(
                role = InheritanceRole.SUBTYPE,
                strategy = InheritanceStrategy.JOINED,
                discriminatorValue = "WIRE",
                primaryKeyJoinColumnName = "PAYMENT_ID",
                primaryKeyJoinReferencedColumnName = "ID",
                parentTableName = "PAYMENT",
                parentIdColumnName = "ID",
            ),
        )
        val customer = EntityModel(
            className = "Customer",
            packageName = "com.company.payments.entity",
            attributes = mutableListOf(
                AttributeModel(
                    name = "address",
                    type = AttributeType.EMBEDDED,
                    embeddedClass = "com.company.payments.entity.PostalAddress",
                    embeddedAttributeOverrides = mutableListOf(
                        EmbeddedAttributeOverride(
                            path = "city",
                            columnName = "POSTAL_CITY",
                            attributeType = AttributeType.STRING,
                            nullable = false,
                            length = 120,
                        ),
                        EmbeddedAttributeOverride(
                            path = "location.code",
                            columnName = "LOCATION_CODE",
                            columnDefinition = "varchar(16)",
                        ),
                    ),
                    embeddedAssociationOverrides = mutableListOf(
                        EmbeddedAssociationOverride(
                            path = "country",
                            relatedEntity = "com.company.payments.entity.Country",
                            relatedIdType = IdType.UUID,
                            joinColumns = mutableListOf(
                                AssociationJoinColumn(
                                    name = "COUNTRY_ID",
                                    referencedColumnName = "ID",
                                    nullable = false,
                                ),
                                AssociationJoinColumn(
                                    name = "COUNTRY_TENANT_ID",
                                    referencedColumnName = "TENANT_ID",
                                    nullable = false,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val javaRoot = EntityGenerator.generate(root)
        val kotlinSubtype = KotlinEntityGenerator.generate(subtype)
        val javaEmbedded = EntityGenerator.generate(customer)
        val kotlinEmbedded = KotlinEntityGenerator.generate(
            customer.copy(sourceLanguage = EntitySourceLanguage.KOTLIN),
        )
        val rootMigration = MigrationGenerator.generateFromEntity(root, DatabaseType.POSTGRES)
        val subtypeMigration = MigrationGenerator.generateFromEntity(subtype, DatabaseType.POSTGRES)
        val embeddedMigration = MigrationGenerator.generateFromEntity(customer, DatabaseType.POSTGRES)

        assertTrue(javaRoot.contains("import java.util.UUID;"))
        assertTrue(javaRoot.contains("@Inheritance(strategy = InheritanceType.JOINED)"))
        assertTrue(
            javaRoot.contains(
                "@DiscriminatorColumn(name = \"PAYMENT_KIND\", " +
                    "discriminatorType = DiscriminatorType.STRING, length = 24)",
            ),
        )
        assertTrue(javaRoot.contains("@DiscriminatorValue(\"BASE\")"))

        assertFalse(kotlinSubtype.contains("@Inheritance"))
        assertFalse(kotlinSubtype.contains("@DiscriminatorColumn"))
        assertTrue(kotlinSubtype.contains("@DiscriminatorValue(\"WIRE\")"))
        assertTrue(
            kotlinSubtype.contains(
                "@PrimaryKeyJoinColumn(name = \"PAYMENT_ID\", referencedColumnName = \"ID\")",
            ),
        )
        assertTrue(kotlinSubtype.contains("open class WirePayment : Payment()"))
        assertFalse(kotlinSubtype.contains("var id:"))

        assertTrue(javaEmbedded.contains("@Embedded"))
        assertTrue(javaEmbedded.contains("import jakarta.persistence.AttributeOverride;"))
        assertTrue(javaEmbedded.contains("import jakarta.persistence.AssociationOverride;"))
        assertTrue(javaEmbedded.contains("import jakarta.persistence.Column;"))
        assertTrue(javaEmbedded.contains("import jakarta.persistence.JoinColumn;"))
        assertTrue(
            javaEmbedded.contains(
                "@AttributeOverride(name = \"city\", " +
                    "column = @Column(name = \"POSTAL_CITY\", nullable = false, length = 120))",
            ),
        )
        assertTrue(javaEmbedded.contains("name = \"location.code\""))
        assertTrue(javaEmbedded.contains("columnDefinition = \"varchar(16)\""))
        assertTrue(
            javaEmbedded.contains(
                "@AssociationOverride(name = \"country\", " +
                    "joinColumns = {@JoinColumn(name = \"COUNTRY_ID\", " +
                    "referencedColumnName = \"ID\", nullable = false), " +
                    "@JoinColumn(name = \"COUNTRY_TENANT_ID\", " +
                    "referencedColumnName = \"TENANT_ID\", nullable = false)})",
            ),
        )
        assertTrue(kotlinEmbedded.contains("@AttributeOverrides("))
        assertTrue(
            kotlinEmbedded.contains(
                "AttributeOverride(name = \"city\", " +
                    "column = Column(name = \"POSTAL_CITY\", nullable = false, length = 120))",
            ),
        )
        assertTrue(
            kotlinEmbedded.contains(
                "AssociationOverride(name = \"country\", " +
                    "joinColumns = [JoinColumn(name = \"COUNTRY_ID\", " +
                    "referencedColumnName = \"ID\", nullable = false), " +
                    "JoinColumn(name = \"COUNTRY_TENANT_ID\", " +
                    "referencedColumnName = \"TENANT_ID\", nullable = false)])",
            ),
        )
        val rootTable = rootMigration.changes.single().changes
            .filterIsInstance<org.jmixworkbench.model.DbChange.CreateTable>()
            .single()
        assertTrue(rootTable.columns.any {
            it.name == "PAYMENT_KIND" && it.type == "VARCHAR(24)" && !it.nullable
        })
        val subtypeChanges = subtypeMigration.changes.single().changes
        val subtypeTable = subtypeChanges
            .filterIsInstance<org.jmixworkbench.model.DbChange.CreateTable>()
            .single()
        assertTrue(subtypeTable.columns.any { it.name == "PAYMENT_ID" && it.primaryKey })
        assertTrue(subtypeChanges.filterIsInstance<org.jmixworkbench.model.DbChange.AddForeignKeyConstraint>()
            .any {
                it.baseColumnNames == "PAYMENT_ID" &&
                    it.referencedTableName == "PAYMENT" &&
                    it.referencedColumnNames == "ID"
            })
        val embeddedChanges = embeddedMigration.changes.single().changes
        val embeddedTable = embeddedChanges
            .filterIsInstance<org.jmixworkbench.model.DbChange.CreateTable>()
            .single()
        assertTrue(embeddedTable.columns.any {
            it.name == "POSTAL_CITY" && it.type == "VARCHAR(120)" && !it.nullable
        })
        assertTrue(embeddedTable.columns.any { it.name == "COUNTRY_ID" && it.type == "UUID" })
        assertTrue(embeddedChanges.filterIsInstance<org.jmixworkbench.model.DbChange.AddForeignKeyConstraint>()
            .any {
                it.baseColumnNames == "COUNTRY_ID" &&
                    it.referencedTableName == "COUNTRY"
            })
    }

    @Test
    fun `Kotlin entity generation preserves Jmix enum ids relationships and repository contracts`() {
        val entity = EntityModel(
            className = "LoanApplication",
            packageName = "com.company.loan.entity",
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            dataStore = "fund",
            traits = mutableListOf(TraitType.STANDARD_ENTITY),
            attributes = mutableListOf(
                AttributeModel(
                    name = "status",
                    type = org.jmixworkbench.model.AttributeType.ENUM,
                    enumClass = "com.company.loan.entity.LoanStatus",
                    mandatory = true,
                    length = 32,
                ),
                AttributeModel(
                    name = "schedules",
                    type = org.jmixworkbench.model.AttributeType.COMPOSITION,
                    association = AssociationConfig(
                        associationType = AssociationType.ONE_TO_MANY,
                        relatedEntity = "com.company.loan.entity.RepaymentSchedule",
                        mappedBy = "loanApplication",
                        cascade = mutableListOf(CascadeType.ALL),
                        orphanRemoval = true,
                    ),
                ),
            ),
            dataRepository = DataRepositoryConfig(enabled = true),
        )

        val source = KotlinEntityGenerator.generate(entity)
        val repository = KotlinDataRepositoryGenerator.generate(entity)

        assertTrue(source.contains("package com.company.loan.entity"))
        assertTrue(source.contains("@Store(name = \"fund\")"))
        assertTrue(source.contains("open class LoanApplication"))
        assertTrue(source.contains("@JvmField\n    protected var status: String? = null"))
        assertTrue(source.contains("fun getStatus(): LoanStatus? = status?.let(LoanStatus::fromId)"))
        assertTrue(source.contains("status = value?.getId()"))
        assertTrue(source.contains("@OneToMany(fetch = FetchType.LAZY, cascade = [CascadeType.ALL]"))
        assertTrue(source.contains("var schedules: MutableList<RepaymentSchedule>? = null"))
        assertTrue(repository.contains("interface LoanApplicationRepository : JmixDataRepository<LoanApplication, UUID>"))
        assertFalse(repository.contains("import java.util.List"))
    }

    @Test
    fun `Kotlin EnumClass uses stable string and integer identifiers`() {
        val stringEnum = KotlinEntityGenerator.generate(
            EntityModel(
                className = "LoanStatus",
                packageName = "com.company.loan.entity",
                sourceLanguage = EntitySourceLanguage.KOTLIN,
                entityType = EntityType.ENUM,
                enumConfig = EnumConfig(
                    values = mutableListOf(
                        EnumValueModel("NEW", "N"),
                        EnumValueModel("APPROVED", "A"),
                    ),
                ),
            ),
        )
        val integerEnum = KotlinEntityGenerator.generate(
            EntityModel(
                className = "RiskBand",
                packageName = "com.company.loan.entity",
                sourceLanguage = EntitySourceLanguage.KOTLIN,
                entityType = EntityType.ENUM,
                enumConfig = EnumConfig(
                    idType = EnumIdType.INTEGER,
                    values = mutableListOf(EnumValueModel("LOW", "10")),
                ),
            ),
        )

        assertTrue(stringEnum.contains("enum class LoanStatus(private val id: String) : EnumClass<String>"))
        assertTrue(stringEnum.contains("NEW(\"N\")"))
        assertTrue(stringEnum.contains("entries.firstOrNull { it.id == id }"))
        assertTrue(integerEnum.contains("enum class RiskBand(private val id: Int) : EnumClass<Int>"))
        assertTrue(integerEnum.contains("LOW(10)"))
    }

    @Test
    fun `project id prefixes entity table migration and JPQL names exactly like Studio`() {
        val entity = EntityModel(
            className = "LoanApplication",
            packageName = "com.company.loan.entity",
        )
        val config = ProjectConfig(
            projectRoot = "/workspace",
            basePackage = "com.company.loan",
            projectId = "loan",
        )

        val output = CrudOrchestrator.generate(
            entity,
            config,
            CrudOrchestrator.CrudOptions(generateMigration = true),
        )

        assertTrue(output.entityFile.content.contains("@Entity(name = \"loan_LoanApplication\")"))
        assertTrue(output.entityFile.content.contains("@Table(name = \"LOAN_LOAN_APPLICATION\")"))
        assertTrue(output.migrationFile.content.contains("tableName=\"LOAN_LOAN_APPLICATION\""))
        assertTrue(output.listViewXml.content.contains("select e from loan_LoanApplication e"))
        assertEquals("001-loan_loan_application.xml", output.migrationFile.relativePath.substringAfterLast('/'))
    }

    @Test
    fun `additional data store annotation is generated`() {
        val source = EntityGenerator.generate(
            EntityModel(
                className = "LoanApp",
                packageName = "com.company.loan.entity",
                dataStore = "fund",
                generationTarget = EntityGenerationTarget("loan", "loan:fund"),
            ),
        )

        assertTrue(source.contains("import io.jmix.core.metamodel.annotation.Store;"))
        assertTrue(source.contains("@Store(name = \"fund\")"))
    }

    @Test
    fun `crud keeps the declared entity package and module roots`() {
        val entity = EntityModel(
            className = "LoanApp",
            packageName = "com.company.loan.entity",
        )
        val output = CrudOrchestrator.generate(
            entity,
            ProjectConfig(
                projectRoot = "/workspace",
                basePackage = "com.company.loan",
                sourceRoot = "loan/src/main/java",
                resourceRoot = "loan/src/main/resources",
            ),
            CrudOrchestrator.CrudOptions(generateMigration = false),
        )

        assertTrue(
            output.entityFile.relativePath ==
                "loan/src/main/java/com/company/loan/entity/LoanApp.java",
        )
        assertFalse(output.entityFile.relativePath.contains("/entity/entity/"))
        assertTrue(
            output.listViewController.relativePath ==
                "loan/src/main/java/com/company/loan/view/LoanAppListView.java",
        )
        assertTrue(output.menuXml.relativePath.startsWith("loan/src/main/resources/"))
    }

    @Test
    fun `current Jmix annotations audit traits and schema columns are generated together`() {
        val entity = EntityModel(
            className = "PayrollRun",
            packageName = "com.company.payroll.entity",
            traits = mutableListOf(
                TraitType.STANDARD_ENTITY,
                TraitType.SOFT_DELETE,
                TraitType.HAS_TENANT_ID,
            ),
            databaseView = true,
            ddlGeneration = DdlGenerationConfig(
                enabled = true,
                mode = DdlGenerationMode.CREATE_ONLY,
                unmappedColumns = mutableListOf("LEGACY_TOTAL"),
                unmappedConstraints = mutableListOf("IDX_LEGACY_TOTAL"),
            ),
        )
        val source = EntityGenerator.generate(entity)

        assertTrue(source.contains("import io.jmix.core.metamodel.annotation.JmixEntity;"))
        assertTrue(source.contains("import io.jmix.core.entity.annotation.JmixGeneratedValue;"))
        assertTrue(source.contains("import io.jmix.core.annotation.DeletedDate;"))
        assertTrue(source.contains("import org.springframework.data.annotation.LastModifiedDate;"))
        assertTrue(source.contains("import io.jmix.core.annotation.TenantId;"))
        assertTrue(source.contains("import io.jmix.data.DbView;"))
        assertTrue(source.contains("import io.jmix.data.DdlGeneration;"))
        assertTrue(source.contains("import io.jmix.data.DdlGeneration.DbScriptGenerationMode;"))
        assertTrue(source.contains("@DdlGeneration(DbScriptGenerationMode.CREATE_ONLY"))
        assertTrue(source.contains("unmappedColumns = {\"LEGACY_TOTAL\"}"))
        assertTrue(source.contains("unmappedConstraints = {\"IDX_LEGACY_TOTAL\"}"))
        assertTrue(source.contains("@Table(name = \"PAYROLL_RUN\")"))
        assertTrue(source.contains("protected OffsetDateTime createdDate;"))
        assertTrue(source.contains("protected String lastModifiedBy;"))
        assertTrue(source.contains("protected String sysTenantId;"))
        assertFalse(source.contains("io.jmix.core.entity.annotation.JmixEntity"))
        assertFalse(source.contains("@UpdatedDate"))
        assertFalse(source.contains("implements StandardEntity"))

        val migration = MigrationGenerator.generateFromEntity(entity, DatabaseType.POSTGRES)
        val columns = (migration.changes.single().changes.single() as org.jmixworkbench.model.DbChange.CreateTable)
            .columns
            .map { it.name }
        assertTrue("CREATED_BY" in columns)
        assertTrue("CREATED_DATE" in columns)
        assertTrue("LAST_MODIFIED_BY" in columns)
        assertTrue("LAST_MODIFIED_DATE" in columns)
        assertTrue("SYS_TENANT_ID" in columns)
        assertTrue("DELETED_DATE" in columns)
    }

    @Test
    fun `disabled DDL still keeps JPA table mapping`() {
        val source = EntityGenerator.generate(
            EntityModel(
                className = "LegacyLedger",
                packageName = "com.company.payroll.entity",
                ddlGeneration = DdlGenerationConfig(enabled = false),
            ),
        )

        assertTrue(source.contains("@Table(name = \"LEGACY_LEDGER\")"))
        assertTrue(source.contains("@DdlGeneration(DbScriptGenerationMode.DISABLED)"))
    }

    @Test
    fun `uuid trait and Jmix data repository use current framework contracts`() {
        val entity = EntityModel(
            className = "PayrollRun",
            packageName = "com.company.payroll.entity",
            id = IdConfig(type = IdType.LONG),
            traits = mutableListOf(TraitType.UUID_TRAIT),
            dataRepository = DataRepositoryConfig(enabled = true),
        )
        val source = EntityGenerator.generate(entity)
        val repository = DataRepositoryGenerator.generate(entity)
        val migration = MigrationGenerator.generateFromEntity(entity, DatabaseType.POSTGRES)
        val columns = (migration.changes.single().changes.single() as org.jmixworkbench.model.DbChange.CreateTable)
            .columns

        assertTrue(source.contains("protected UUID uuid;"))
        assertTrue(source.contains("@JmixGeneratedValue"))
        assertTrue(columns.any { it.name == "UUID" && it.unique && !it.nullable })
        assertTrue(repository.contains("extends JmixDataRepository<PayrollRun, Long>"))
        assertTrue(repository.contains("import io.jmix.core.repository.JmixDataRepository;"))
        assertFalse(repository.contains("JpaRepository"))
    }

    @Test
    fun `Java and Kotlin repositories generate advanced secure Jmix query contracts`() {
        val methods = mutableListOf(
            RepositoryMethod(
                name = "findByEmployeeNumberContainingIgnoreCase",
                returnType = "Page<Employee>",
                parameters = mutableListOf(
                    MethodParameter("employeeNumber", "String"),
                    MethodParameter(
                        name = "pageable",
                        type = "Pageable",
                        role = RepositoryParameterRole.PAGEABLE,
                    ),
                    MethodParameter(
                        name = "fetchPlan",
                        type = "FetchPlan",
                        nullable = true,
                        role = RepositoryParameterRole.FETCH_PLAN,
                    ),
                ),
                fetchPlan = "employee-summary",
                description = "Searches employees without bypassing row-level constraints.",
            ),
            RepositoryMethod(
                name = "countByDepartment",
                returnType = "List<KeyValueEntity>",
                parameters = mutableListOf(
                    MethodParameter(
                        name = "department",
                        type = "String",
                        bindingName = "departmentCode",
                    ),
                ),
                query = """
                    select e.department, count(e)
                    from payroll_Employee e
                    where e.department = :departmentCode
                    group by e.department
                """.trimIndent(),
                queryType = QueryType.JPQL,
                queryProperties = mutableListOf("department", "count"),
                applyConstraints = true,
                queryHints = mutableListOf(
                    RepositoryQueryHint("jmix.query.cacheable", "true"),
                ),
            ),
        )
        val javaEntity = EntityModel(
            className = "Employee",
            packageName = "com.company.payroll.entity",
            entityName = "payroll_Employee",
            dataRepository = DataRepositoryConfig(
                enabled = true,
                interfaceName = "EmployeeReadRepository",
                applyConstraints = false,
                methods = methods,
            ),
        )
        val kotlinEntity = javaEntity.copy(sourceLanguage = EntitySourceLanguage.KOTLIN)

        val java = DataRepositoryGenerator.generate(javaEntity)
        val kotlin = KotlinDataRepositoryGenerator.generate(kotlinEntity)

        assertTrue(java.contains("@ApplyConstraints(false)\npublic interface EmployeeReadRepository"))
        assertTrue(java.contains("@io.jmix.core.repository.FetchPlan(\"employee-summary\")"))
        assertTrue(java.contains("import io.jmix.core.FetchPlan;"))
        assertFalse(java.contains("import io.jmix.core.repository.FetchPlan;"))
        assertTrue(java.contains("Page<Employee> findByEmployeeNumberContainingIgnoreCase("))
        assertTrue(java.contains("@Nullable FetchPlan fetchPlan"))
        assertTrue(java.contains("properties = {\"department\", \"count\"}"))
        assertTrue(java.contains("@Param(\"departmentCode\") String department"))
        assertTrue(java.contains("@QueryHints({@QueryHint(name = \"jmix.query.cacheable\", value = \"true\")})"))
        assertTrue(kotlin.contains("@ApplyConstraints(false)\ninterface EmployeeReadRepository"))
        assertTrue(kotlin.contains("@io.jmix.core.repository.FetchPlan(\"employee-summary\")"))
        assertTrue(kotlin.contains("import io.jmix.core.FetchPlan"))
        assertFalse(kotlin.contains("import io.jmix.core.repository.FetchPlan\n"))
        assertTrue(kotlin.contains("Page<Employee>"))
        assertTrue(kotlin.contains("fetchPlan: FetchPlan?"))
        assertTrue(kotlin.contains("properties = [\"department\", \"count\"]"))
        assertTrue(kotlin.contains("@Param(\"departmentCode\") department: String"))
        assertTrue(kotlin.contains("@QueryHints(value = [QueryHint("))
    }

    @Test
    fun `Jmix repositories fail closed on native mutation and parameter drift`() {
        fun entity(method: RepositoryMethod) = EntityModel(
            className = "Payment",
            packageName = "com.company.payment.entity",
            entityName = "payment_Payment",
            dataRepository = DataRepositoryConfig(
                enabled = true,
                methods = mutableListOf(method),
            ),
        )

        val native = assertFailsWith<IllegalStateException> {
            DataRepositoryGenerator.generate(
                entity(
                    RepositoryMethod(
                        name = "findNative",
                        returnType = "List<Payment>",
                        query = "select * from PAYMENT",
                        queryType = QueryType.NATIVE,
                    ),
                ),
            )
        }
        assertTrue(native.message.orEmpty().contains("NATIVE-UNSUPPORTED"))

        val drift = assertFailsWith<IllegalArgumentException> {
            DataRepositoryGenerator.generate(
                entity(
                    RepositoryMethod(
                        name = "findByStatus",
                        returnType = "List<Payment>",
                        parameters = mutableListOf(MethodParameter("status", "String")),
                        query = "select p from payment_Payment p where p.status = :state",
                        queryType = QueryType.JPQL,
                    ),
                ),
            )
        }
        assertTrue(drift.message.orEmpty().contains("JPQL-PARAMETERS"))

        val mutation = assertFailsWith<IllegalArgumentException> {
            DataRepositoryGenerator.generate(
                entity(
                    RepositoryMethod(
                        name = "deleteEverything",
                        returnType = "Long",
                        query = "delete from payment_Payment",
                        queryType = QueryType.JPQL,
                    ),
                ),
            )
        }
        assertTrue(mutation.message.orEmpty().contains("JPQL-READ-ONLY"))
    }

    @Test
    fun `indexes unique constraints and rollback stay aligned between entity and Liquibase`() {
        val entity = EntityModel(
            className = "EmployeeAccount",
            packageName = "com.company.payroll.entity",
            attributes = mutableListOf(
                AttributeModel(name = "companyCode", columnName = "COMPANY_CODE"),
                AttributeModel(name = "employeeNumber", columnName = "EMPLOYEE_NUMBER"),
            ),
            indexes = mutableListOf(
                IndexModel(
                    name = "IDX_EMPLOYEE_ACCOUNT_NUMBER",
                    columns = listOf("EMPLOYEE_NUMBER"),
                ),
            ),
            uniqueConstraints = mutableListOf(
                UniqueConstraintModel(
                    name = "UQ_EMPLOYEE_ACCOUNT_COMPANY_NUMBER",
                    columns = listOf("COMPANY_CODE", "EMPLOYEE_NUMBER"),
                ),
            ),
        )

        val source = EntityGenerator.generate(entity)
        val migration = MigrationGenerator.generate(
            MigrationGenerator.generateFromEntity(entity, DatabaseType.POSTGRES),
        )

        assertTrue(source.contains("@Index(name = \"IDX_EMPLOYEE_ACCOUNT_NUMBER\", columnList = \"EMPLOYEE_NUMBER\")"))
        assertTrue(source.contains("@UniqueConstraint(name = \"UQ_EMPLOYEE_ACCOUNT_COMPANY_NUMBER\""))
        assertTrue(migration.contains("<createIndex tableName=\"EMPLOYEE_ACCOUNT\" indexName=\"IDX_EMPLOYEE_ACCOUNT_NUMBER\""))
        assertTrue(migration.contains("<addUniqueConstraint tableName=\"EMPLOYEE_ACCOUNT\" constraintName=\"UQ_EMPLOYEE_ACCOUNT_COMPANY_NUMBER\""))
        assertTrue(migration.contains("<rollback>"))
        assertTrue(migration.contains("<dropTable tableName=\"EMPLOYEE_ACCOUNT\" cascadeConstraints=\"true\""))
    }

    @Test
    fun `enterprise relationships generate correct Java collections ownership and database constraints`() {
        val entity = EntityModel(
            className = "LoanApp",
            packageName = "com.company.loan.entity",
            attributes = mutableListOf(
                AttributeModel(
                    name = "employee",
                    type = org.jmixworkbench.model.AttributeType.ASSOCIATION,
                    mandatory = true,
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_ONE,
                        relatedEntity = "com.company.payroll.entity.Employee",
                        relatedTableName = "PAYROLL_EMPLOYEE",
                        relatedIdType = IdType.LONG,
                        joinColumnName = "EMPLOYEE_ID",
                    ),
                ),
                AttributeModel(
                    name = "schedules",
                    type = org.jmixworkbench.model.AttributeType.COMPOSITION,
                    association = AssociationConfig(
                        associationType = AssociationType.ONE_TO_MANY,
                        relatedEntity = "com.company.loan.entity.RepaymentSchedule",
                        relatedTableName = "LOAN_REPAYMENT_SCHEDULE",
                        mappedBy = "loanApp",
                        collectionType = AssociationCollectionType.LIST,
                        cascade = mutableListOf(CascadeType.ALL),
                        orphanRemoval = true,
                    ),
                ),
                AttributeModel(
                    name = "documents",
                    type = org.jmixworkbench.model.AttributeType.ASSOCIATION,
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_MANY,
                        relatedEntity = "com.company.document.entity.Document",
                        relatedTableName = "DOC_DOCUMENT",
                        joinTable = JoinTableConfig(
                            name = "LOAN_APP_DOCUMENT_LINK",
                            joinColumnName = "LOAN_APP_ID",
                            inverseJoinColumnName = "DOCUMENT_ID",
                        ),
                    ),
                ),
            ),
        )

        val source = EntityGenerator.generate(entity)
        val migration = MigrationGenerator.generate(
            MigrationGenerator.generateFromEntity(entity, DatabaseType.POSTGRES),
        )

        assertTrue(source.contains("protected Employee employee;"))
        assertTrue(source.contains("optional = false"))
        assertTrue(source.contains("@JoinColumn(name = \"EMPLOYEE_ID\", referencedColumnName = \"ID\", nullable = false)"))
        assertTrue(source.contains("protected List<RepaymentSchedule> schedules;"))
        assertTrue(source.contains("@OneToMany(mappedBy = \"loanApp\""))
        assertTrue(source.contains("@Composition"))
        assertTrue(source.contains("orphanRemoval = true"))
        assertTrue(source.contains("protected List<Document> documents;"))
        assertTrue(source.contains("@JoinTable(name = \"LOAN_APP_DOCUMENT_LINK\""))
        assertTrue(migration.contains("<column name=\"EMPLOYEE_ID\" type=\"BIGINT\""))
        assertTrue(migration.contains("referencedTableName=\"PAYROLL_EMPLOYEE\""))
        assertFalse(migration.contains("column name=\"SCHEDULES_ID\""))
        assertTrue(migration.contains("<createTable tableName=\"LOAN_APP_DOCUMENT_LINK\""))
        assertTrue(migration.contains("constraintName=\"FK_LOAN_APP_DOCUMENT_LINK_LOAN_APP_ID\""))
        assertTrue(migration.contains("constraintName=\"FK_LOAN_APP_DOCUMENT_LINK_DOCUMENT_ID\""))
    }

    @Test
    fun `cross data store reference uses Jmix id plus transient property without database foreign key`() {
        val entity = EntityModel(
            className = "LoanApp",
            packageName = "com.company.loan.entity",
            attributes = mutableListOf(
                AttributeModel(
                    name = "fundProfile",
                    type = org.jmixworkbench.model.AttributeType.ASSOCIATION,
                    mandatory = true,
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_ONE,
                        relatedEntity = "com.company.fund.entity.FundProfile",
                        relatedTableName = "FUND_PROFILE",
                        relatedIdType = IdType.UUID,
                        localIdAttributeName = "fundProfileId",
                        joinColumnName = "FUND_PROFILE_ID",
                        crossDataStore = true,
                    ),
                ),
            ),
        )

        val source = EntityGenerator.generate(entity)
        val migration = MigrationGenerator.generate(
            MigrationGenerator.generateFromEntity(entity, DatabaseType.POSTGRES),
        )

        assertTrue(source.contains("@SystemLevel"))
        assertTrue(source.contains("protected UUID fundProfileId;"))
        assertTrue(source.contains("@Transient"))
        assertTrue(source.contains("@JmixProperty"))
        assertTrue(source.contains("@DependsOnProperties(\"fundProfileId\")"))
        assertTrue(source.contains("protected FundProfile fundProfile;"))
        assertFalse(source.contains("@ManyToOne"))
        assertTrue(migration.contains("<column name=\"FUND_PROFILE_ID\" type=\"UUID\""))
        assertFalse(migration.contains("FK_LOAN_APP_FUND_PROFILE_ID"))
    }

    @Test
    fun `Jmix enum uses typed EnumClass IDs and entity fields persist the ID without Enumerated`() {
        val enumSource = EntityGenerator.generate(
            EntityModel(
                className = "ApprovalState",
                packageName = "com.company.loan.entity",
                entityType = EntityType.ENUM,
                enumConfig = EnumConfig(
                    idType = EnumIdType.INTEGER,
                    values = mutableListOf(
                        EnumValueModel("DRAFT", "10"),
                        EnumValueModel("APPROVED", "20"),
                    ),
                ),
            ),
        )
        val entitySource = EntityGenerator.generate(
            EntityModel(
                className = "LoanApp",
                packageName = "com.company.loan.entity",
                attributes = mutableListOf(
                    AttributeModel(
                        name = "state",
                        type = org.jmixworkbench.model.AttributeType.ENUM,
                        enumClass = "com.company.loan.entity.ApprovalState",
                        enumIdType = EnumIdType.INTEGER,
                    ),
                ),
            ),
        )

        assertTrue(enumSource.contains("implements EnumClass<Integer>"))
        assertTrue(enumSource.contains("DRAFT(10)"))
        assertFalse(enumSource.contains("import EnumClass<Integer>"))
        assertTrue(entitySource.contains("protected Integer state;"))
        assertTrue(entitySource.contains("public ApprovalState getState()"))
        assertTrue(entitySource.contains("ApprovalState.fromId(state)"))
        assertTrue(entitySource.contains("state.getId()"))
        assertFalse(entitySource.contains("@Enumerated"))
    }

    @Test
    fun `DTO metadata stays Jmix-only and honors annotated properties and read-only access`() {
        val source = EntityGenerator.generate(
            EntityModel(
                className = "LoanDecision",
                packageName = "com.company.loan.dto",
                entityType = EntityType.DTO,
                annotatedPropertiesOnly = true,
                dtoConfig = DtoConfig(readOnly = true),
                attributes = mutableListOf(
                    AttributeModel(
                        name = "reason",
                        mandatory = true,
                        readOnly = true,
                    ),
                ),
            ),
        )

        assertTrue(source.contains("@JmixEntity(name = \"LoanDecision\", annotatedPropertiesOnly = true)"))
        assertTrue(source.contains("@JmixId"))
        assertTrue(source.contains("@JmixProperty"))
        assertTrue(source.contains("@NotNull"))
        assertFalse(source.contains("jakarta.persistence"))
        assertFalse(source.contains("@Column"))
        assertFalse(source.contains("setReason"))
        assertFalse(source.contains("setId"))
    }

    @Test
    fun `embedded identifier repository and entity use the configured embeddable class`() {
        val entity = EntityModel(
            className = "LedgerEntry",
            packageName = "com.company.ledger.entity",
            id = IdConfig(
                type = IdType.EMBEDDED,
                embeddedIdClass = "com.company.ledger.entity.LedgerEntryId",
            ),
        )

        val source = EntityGenerator.generate(entity)
        val repository = DataRepositoryGenerator.generate(entity)

        assertTrue(source.contains("@EmbeddedId"))
        assertTrue(source.contains("protected LedgerEntryId id;"))
        assertTrue(repository.contains("JmixDataRepository<LedgerEntry, LedgerEntryId>"))
        assertTrue(repository.contains("import com.company.ledger.entity.LedgerEntryId;"))
    }

    @Test
    fun `qualified table mappings and composite identity value semantics match in Java and Kotlin`() {
        val identity = EntityModel(
            className = "LedgerEntryId",
            packageName = "com.company.ledger.entity",
            entityType = EntityType.EMBEDDABLE,
            embeddableIdentity = true,
            attributes = mutableListOf(
                AttributeModel(
                    name = "accountCode",
                    columnName = "ACCOUNT_CODE",
                    mandatory = true,
                    length = 40,
                ),
                AttributeModel(
                    name = "postingSequence",
                    type = org.jmixworkbench.model.AttributeType.LONG,
                    columnName = "POSTING_SEQUENCE",
                    mandatory = true,
                ),
            ),
        )
        val javaIdentity = EntityGenerator.generate(identity)
        val kotlinIdentity = KotlinEntityGenerator.generate(
            identity.copy(sourceLanguage = EntitySourceLanguage.KOTLIN),
        )
        val javaEntity = EntityGenerator.generate(
            EntityModel(
                className = "LedgerEntry",
                packageName = "com.company.ledger.entity",
                tableName = "LEDGER_ENTRY",
                tableSchema = "accounting",
                tableCatalog = "bank",
                ddlGeneration = DdlGenerationConfig(enabled = false),
            ),
        )
        val kotlinEntity = KotlinEntityGenerator.generate(
            EntityModel(
                className = "LedgerEntry",
                packageName = "com.company.ledger.entity",
                sourceLanguage = EntitySourceLanguage.KOTLIN,
                tableName = "LEDGER_ENTRY",
                tableSchema = "accounting",
                tableCatalog = "bank",
                ddlGeneration = DdlGenerationConfig(enabled = false),
            ),
        )

        assertTrue(javaEntity.contains("@Table(name = \"LEDGER_ENTRY\", schema = \"accounting\", catalog = \"bank\")"))
        assertTrue(kotlinEntity.contains("@Table(name = \"LEDGER_ENTRY\", schema = \"accounting\", catalog = \"bank\")"))
        assertTrue(javaIdentity.contains("implements Serializable"))
        assertTrue(javaIdentity.contains("private static final long serialVersionUID = 1L;"))
        assertTrue(javaIdentity.contains("Objects.equals(accountCode, that.accountCode)"))
        assertTrue(javaIdentity.contains("Objects.hash(accountCode, postingSequence)"))
        assertTrue(kotlinIdentity.contains("open class LedgerEntryId : Serializable"))
        assertTrue(kotlinIdentity.contains("other as LedgerEntryId"))
        assertTrue(kotlinIdentity.contains("Objects.hash(accountCode, postingSequence)"))
    }

    @Test
    fun `composite foreign keys and qualified join tables generate exact Java and Kotlin mappings`() {
        val entity = EntityModel(
            className = "LedgerAllocation",
            packageName = "com.company.ledger.entity",
            attributes = mutableListOf(
                AttributeModel(
                    name = "ledgerEntry",
                    type = org.jmixworkbench.model.AttributeType.ASSOCIATION,
                    mandatory = true,
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_ONE,
                        relatedEntity = "com.company.ledger.entity.LedgerEntry",
                        relatedIdType = IdType.EMBEDDED,
                        joinColumns = mutableListOf(
                            AssociationJoinColumn("ACCOUNT_CODE", "ACCOUNT_CODE", nullable = false),
                            AssociationJoinColumn("POSTING_SEQUENCE", "POSTING_SEQUENCE", nullable = false),
                        ),
                    ),
                ),
                AttributeModel(
                    name = "tags",
                    type = org.jmixworkbench.model.AttributeType.ASSOCIATION,
                    association = AssociationConfig(
                        associationType = AssociationType.MANY_TO_MANY,
                        relatedEntity = "com.company.ledger.entity.LedgerTag",
                        collectionType = AssociationCollectionType.SET,
                        joinTable = JoinTableConfig(
                            name = "LEDGER_ENTRY_TAG",
                            joinColumnName = "ACCOUNT_CODE",
                            inverseJoinColumnName = "TAG_CODE",
                            schema = "accounting",
                            catalog = "bank",
                            joinColumns = mutableListOf(
                                AssociationJoinColumn("ACCOUNT_CODE", "ACCOUNT_CODE"),
                                AssociationJoinColumn("POSTING_SEQUENCE", "POSTING_SEQUENCE"),
                            ),
                            inverseJoinColumns = mutableListOf(
                                AssociationJoinColumn("TAG_TENANT", "TENANT"),
                                AssociationJoinColumn("TAG_CODE", "CODE"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val java = EntityGenerator.generate(entity)
        val kotlin = KotlinEntityGenerator.generate(
            entity.copy(sourceLanguage = EntitySourceLanguage.KOTLIN),
        )

        assertTrue(java.contains("@JoinColumns({@JoinColumn(name = \"ACCOUNT_CODE\", referencedColumnName = \"ACCOUNT_CODE\", nullable = false), @JoinColumn(name = \"POSTING_SEQUENCE\", referencedColumnName = \"POSTING_SEQUENCE\", nullable = false)})"))
        assertTrue(java.contains("@JoinTable(name = \"LEDGER_ENTRY_TAG\", schema = \"accounting\", catalog = \"bank\""))
        assertTrue(java.contains("inverseJoinColumns = {@JoinColumn(name = \"TAG_TENANT\", referencedColumnName = \"TENANT\"), @JoinColumn(name = \"TAG_CODE\", referencedColumnName = \"CODE\")}"))
        assertTrue(kotlin.contains("@JoinColumns(value = [JoinColumn(name = \"ACCOUNT_CODE\", referencedColumnName = \"ACCOUNT_CODE\", nullable = false), JoinColumn(name = \"POSTING_SEQUENCE\", referencedColumnName = \"POSTING_SEQUENCE\", nullable = false)])"))
        assertTrue(kotlin.contains("@JoinTable(name = \"LEDGER_ENTRY_TAG\""))
        assertTrue(kotlin.contains("schema = \"accounting\", catalog = \"bank\""))
        assertTrue(kotlin.contains("inverseJoinColumns = [JoinColumn(name = \"TAG_TENANT\", referencedColumnName = \"TENANT\"), JoinColumn(name = \"TAG_CODE\", referencedColumnName = \"CODE\")]"))
    }

    @Test
    fun `advanced property metadata comments validation groups and read-only fields are generated`() {
        val entity = EntityModel(
                className = "EmployeeProfile",
                packageName = "com.company.payroll.entity",
                comment = "Enterprise employee profile",
                systemLevel = true,
                annotatedPropertiesOnly = true,
                instanceNameAttribute = "displayName",
                attributes = mutableListOf(
                    AttributeModel(
                        name = "displayName",
                        mandatory = true,
                        readOnly = true,
                        comment = "Computed display label",
                        dependsOnProperties = mutableListOf("firstName", "lastName"),
                        propertyDatatype = "employeeName",
                        validations = mutableListOf(
                            ValidationModel(
                                type = ValidationType.NOT_BLANK,
                                groups = mutableListOf("com.company.validation.PayrollChecks"),
                            ),
                        ),
                    ),
                ),
        )
        val source = EntityGenerator.generate(entity)
        val kotlin = KotlinEntityGenerator.generate(
            entity.copy(sourceLanguage = EntitySourceLanguage.KOTLIN),
        )

        assertTrue(source.contains("@SystemLevel"))
        assertTrue(source.contains("@Comment(\"Enterprise employee profile\")"))
        assertTrue(source.contains("@InstanceName"))
        assertTrue(source.contains("@DependsOnProperties({\"firstName\", \"lastName\"})"))
        assertTrue(source.contains("@PropertyDatatype(\"employeeName\")"))
        assertTrue(source.contains("@NotBlank(groups = {PayrollChecks.class})"))
        assertTrue(source.contains("import com.company.validation.PayrollChecks;"))
        assertTrue(source.contains("insertable = false, updatable = false"))
        assertFalse(source.contains("setDisplayName"))
        assertTrue(kotlin.contains("insertable = false, updatable = false"))
        assertTrue(kotlin.contains("val displayName: String? = null"))
    }

    @Test
    fun `all official scalar datatypes stay aligned between Java and Liquibase`() {
        val entity = EntityModel(
            className = "DatatypeMatrix",
            packageName = "com.company.test.entity",
            attributes = mutableListOf(
                AttributeModel("initial", org.jmixworkbench.model.AttributeType.CHARACTER),
                AttributeModel("offsetTime", org.jmixworkbench.model.AttributeType.OFFSET_TIME),
                AttributeModel("sqlDate", org.jmixworkbench.model.AttributeType.SQL_DATE),
                AttributeModel("sqlTime", org.jmixworkbench.model.AttributeType.SQL_TIME),
                AttributeModel("homepage", org.jmixworkbench.model.AttributeType.URI),
                AttributeModel("attachment", org.jmixworkbench.model.AttributeType.FILE_REF),
                AttributeModel(
                    "money",
                    org.jmixworkbench.model.AttributeType.CUSTOM,
                    javaTypeName = "com.company.money.Money",
                    sqlType = "numeric(19,4)",
                ),
            ),
        )
        val source = EntityGenerator.generate(entity)
        val migration = MigrationGenerator.generate(
            MigrationGenerator.generateFromEntity(entity, DatabaseType.POSTGRES),
        )

        assertTrue(source.contains("protected Character initial;"))
        assertTrue(source.contains("protected OffsetTime offsetTime;"))
        assertTrue(source.contains("protected java.sql.Date sqlDate;"))
        assertTrue(source.contains("protected URI homepage;"))
        assertTrue(source.contains("protected FileRef attachment;"))
        assertTrue(source.contains("protected Money money;"))
        assertTrue(source.contains("columnDefinition = \"numeric(19,4)\""))
        assertTrue(migration.contains("name=\"ATTACHMENT\" type=\"VARCHAR(1024)\""))
        assertTrue(migration.contains("name=\"MONEY\" type=\"numeric(19,4)\""))
    }
}
