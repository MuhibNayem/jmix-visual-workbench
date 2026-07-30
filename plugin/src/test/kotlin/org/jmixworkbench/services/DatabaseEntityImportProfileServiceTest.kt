package org.jmixworkbench.services

import org.jmixworkbench.model.EntitySourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseEntityImportProfileServiceTest {
    @Test
    fun `profile serialization is deterministic credential free and replayable`() {
        val request = DatabaseEntityImportRequest(
            storeId = "loan:main",
            moduleId = "loan",
            packageName = "com.company.loan.entity",
            sourceLanguage = EntitySourceLanguage.KOTLIN,
            selectedTables = listOf(
                DatabaseTableReference("payroll", "public", "LOAN_ACCT", "TABLE", null),
            ),
            identifierOverrides = linkedMapOf(
                "payroll.public.V_LOAN" to listOf("LOAN_NO"),
            ),
            classNameOverrides = linkedMapOf(
                "payroll.public.LOAN_ACCT" to "LoanAccount",
            ),
            profileId = "loan-accounts",
            profileLabel = "Loan accounts",
            connectTimeoutSeconds = 2,
            networkTimeoutSeconds = 4,
        )
        val table = DatabaseTableSnapshot(
            catalog = "payroll",
            schema = "public",
            name = "LOAN_ACCT",
            type = "TABLE",
            remarks = "Loan accounts",
            columns = emptyList(),
            primaryKeyColumns = listOf("ID"),
        )
        val plan = DatabaseEntityImportPlanResponse(
            accepted = true,
            ready = true,
            snapshotDigest = "a".repeat(64),
            storeId = "loan:main",
            database = database(),
            tables = listOf(
                DatabaseEntityImportTablePlan(
                    table = table,
                    selectedByUser = true,
                    requiredBy = emptyList(),
                    status = DatabaseEntityImportStatus.READY,
                    entityClassName = "LoanAccount",
                    entityQualifiedName = "com.company.loan.entity.LoanAccount",
                    compositeIdClassName = null,
                    generated = true,
                    issues = emptyList(),
                ),
            ),
            entities = emptyList(),
            issues = emptyList(),
        )

        val profile = requireNotNull(DatabaseEntityImportProfileService.fromPlan(request, plan))
        val first = DatabaseEntityImportProfileService.serialize(profile)
        val second = DatabaseEntityImportProfileService.serialize(profile)

        assertEquals(first, second)
        assertEquals(10, profile.request.connectTimeoutSeconds)
        assertEquals(30, profile.request.networkTimeoutSeconds)
        assertEquals(
            ".jmix-workbench/database-imports/loan-accounts.json",
            DatabaseEntityImportProfileService.path(profile),
        )
        assertFalse(first.contains("password", ignoreCase = true))
        assertFalse(first.contains("jdbc:", ignoreCase = true))
        assertTrue(first.contains("\"baselineSnapshotDigest\": \"${"a".repeat(64)}\""))
        assertNull(DatabaseEntityImportProfileService.validate(profile, "loan-accounts"))
    }

    @Test
    fun `profile validation rejects file identity mismatch and incomplete baselines`() {
        val request = DatabaseEntityImportRequest(
            storeId = "main",
            moduleId = "app",
            packageName = "test.domain",
            selectedTables = listOf(
                DatabaseTableReference(null, "public", "ACCOUNT", "TABLE", null),
            ),
            profileId = "account-model",
            profileLabel = "Account model",
        )
        val profile = DatabaseEntityImportProfile(
            schemaVersion = 1,
            id = "account-model",
            label = "Account model",
            request = request,
            baselineSnapshotDigest = "b".repeat(64),
            database = database(),
            tables = emptyList(),
        )

        assertTrue(
            DatabaseEntityImportProfileService.validate(profile, "different-name")
                ?.contains("does not match") == true,
        )
        assertTrue(
            DatabaseEntityImportProfileService.validate(profile, "account-model")
                ?.contains("no database tables") == true,
        )
    }

    private fun database() = DatabaseProductSnapshot(
        name = "PostgreSQL",
        version = "17",
        driverName = "PostgreSQL JDBC Driver",
        driverVersion = "42",
        urlFingerprint = "fingerprint",
    )
}
