package org.jmixworkbench.discovery.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceNavigationPolicyTest {

    @Test
    fun `accepts a revision-bound project-relative source location`() {
        val result = SourceNavigationPolicy.validate(
            relativePath = "payroll/src/main/java/com/acme/LoanService.java",
            line = 42,
            column = 9,
            revisionFingerprint = "sha256:current",
        )

        assertTrue(result.accepted)
        val locator = assertNotNull(result.locator)
        assertEquals(42, locator.line)
        assertEquals(9, locator.column)
        assertTrue(SourceNavigationPolicy.revisionMatches(locator, "sha256:current"))
        assertFalse(SourceNavigationPolicy.revisionMatches(locator, "sha256:changed"))
    }

    @Test
    fun `rejects traversal absolute ambiguous and malformed locations`() {
        val invalidPaths = listOf(
            "../secrets.txt",
            "module/../secrets.txt",
            "/etc/passwd",
            "C:/Windows/system.ini",
            "module\\src\\Entity.java",
            "module//src/Entity.java",
        )

        invalidPaths.forEach { path ->
            val result = SourceNavigationPolicy.validate(path, 1, 1, "revision")
            assertFalse(result.accepted, path)
            assertEquals("JVW-NAVIGATION-PATH-REJECTED", result.errorCode)
        }
    }

    @Test
    fun `rejects invalid coordinates and blank revisions`() {
        listOf(
            SourceNavigationPolicy.validate("src/Entity.java", 0, 1, "revision"),
            SourceNavigationPolicy.validate("src/Entity.java", 1, 0, "revision"),
            SourceNavigationPolicy.validate("src/Entity.java", 10_000_001, 1, "revision"),
            SourceNavigationPolicy.validate("src/Entity.java", 1, 10_000_001, "revision"),
            SourceNavigationPolicy.validate("src/Entity.java", 1, 1, " "),
        ).forEach { result ->
            assertFalse(result.accepted)
            assertEquals("JVW-NAVIGATION-PATH-REJECTED", result.errorCode)
        }
    }
}
