package org.jmixworkbench.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectSourceDestinationServiceTest {
    @Test
    fun `infers sibling destinations for recovered conventional source sets`() {
        assertEquals(
            "loan/src/main/resources",
            ProjectSourceDestinationService.inferSiblingSourceRoot(
                "loan/src/main/java/com/acme/Loan.java",
                ProjectSourceDestinationKind.PRODUCTION_RESOURCES,
            ),
        )
        assertEquals(
            "risk/src/integration/java",
            ProjectSourceDestinationService.inferSiblingSourceRoot(
                "risk/src/integration/resources/dmn/risk.dmn",
                ProjectSourceDestinationKind.PRODUCTION_JAVA,
            ),
        )
        assertEquals(
            "loan/src/main/kotlin",
            ProjectSourceDestinationService.inferSiblingSourceRoot(
                "loan/src/main/java/com/acme/Loan.java",
                ProjectSourceDestinationKind.PRODUCTION_KOTLIN,
            ),
        )
        assertEquals(
            "risk/src/test/java",
            ProjectSourceDestinationService.inferSiblingSourceRoot(
                "risk/src/integration/kotlin/com/acme/Risk.kt",
                ProjectSourceDestinationKind.TEST_JAVA,
            ),
        )
    }

    @Test
    fun `preserves external aliases and excludes test sources from production`() {
        assertEquals(
            "__jmix_external__/risk-abc/src/main/resources",
            ProjectSourceDestinationService.inferSiblingSourceRoot(
                "__jmix_external__/risk-abc/src/main/groovy/com/acme/Risk.groovy",
                ProjectSourceDestinationKind.PRODUCTION_RESOURCES,
            ),
        )
        assertNull(
            ProjectSourceDestinationService.inferSiblingSourceRoot(
                "loan/src/test/java/com/acme/LoanTest.java",
                ProjectSourceDestinationKind.PRODUCTION_JAVA,
            ),
        )
        assertNull(
            ProjectSourceDestinationService.inferSiblingSourceRoot(
                "loan/src/benchmark/resources/cases.json",
                ProjectSourceDestinationKind.PRODUCTION_RESOURCES,
            ),
        )
    }
}
