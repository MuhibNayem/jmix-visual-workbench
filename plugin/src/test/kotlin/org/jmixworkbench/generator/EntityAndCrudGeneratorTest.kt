package org.jmixworkbench.generator

import org.jmixworkbench.model.EntityGenerationTarget
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
import org.jmixworkbench.model.AssociationCollectionType
import org.jmixworkbench.model.AssociationConfig
import org.jmixworkbench.model.AssociationType
import org.jmixworkbench.model.CascadeType
import org.jmixworkbench.model.JoinTableConfig
import org.jmixworkbench.model.UniqueConstraintModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityAndCrudGeneratorTest {
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
}
