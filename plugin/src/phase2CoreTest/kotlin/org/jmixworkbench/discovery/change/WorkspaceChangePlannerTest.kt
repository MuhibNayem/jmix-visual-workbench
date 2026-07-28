package org.jmixworkbench.discovery.change

import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceChangePlannerTest {

    @Test
    fun `applies targeted edits while preserving all handwritten source outside the ranges`() {
        val original = """
            <view id="Loan.detail">
              <!-- handwritten extension point -->
              <layout><textField id="amount" width="20em"/></layout>
            </view>
        """.trimIndent()
        val widthStart = original.indexOf("20em")
        val insertionPoint = original.indexOf("</layout>")
        val plan = WorkspaceChangePlanner.plan(
            WorkspaceChangeSet(
                id = "screen:Loan.detail:responsive-width",
                label = "Update Loan detail layout",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = "loan/src/main/resources/views/loan-detail-view.xml",
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = CanonicalDiscoveryJson.sha256(original),
                        edits = listOf(
                            WorkspaceTextEdit(widthStart, widthStart + 4, "20em", "100%"),
                            WorkspaceTextEdit(
                                insertionPoint,
                                insertionPoint,
                                "",
                                "<button id=\"approveButton\"/>",
                            ),
                        ),
                    ),
                ),
            ),
            currentContent = mapOf("loan/src/main/resources/views/loan-detail-view.xml" to original),
        )

        assertTrue(plan.accepted)
        assertNotNull(plan.planDigest)
        val result = plan.files.single().resultContent
        assertTrue("<!-- handwritten extension point -->" in result)
        assertTrue("width=\"100%\"" in result)
        assertTrue("<button id=\"approveButton\"/>" in result)
        assertEquals(2, plan.files.single().appliedEditCount)
    }

    @Test
    fun `rejects stale overlapping unexpected and traversing modifications`() {
        val original = "<view><layout/></view>"
        val path = "src/main/resources/view.xml"
        val stale = WorkspaceChangePlanner.plan(
            changeSet(
                WorkspaceFileChange(
                    path,
                    WorkspaceFileChangeMode.MODIFY,
                    "stale",
                    edits = listOf(WorkspaceTextEdit(0, 6, "<view>", "<view id=\"x\">")),
                ),
            ),
            mapOf(path to original),
        )
        assertRejected(stale, "JVW-CHANGE-STALE")

        val overlapping = WorkspaceChangePlanner.plan(
            changeSet(
                WorkspaceFileChange(
                    path,
                    WorkspaceFileChangeMode.MODIFY,
                    CanonicalDiscoveryJson.sha256(original),
                    edits = listOf(
                        WorkspaceTextEdit(0, 6, "<view>", "A"),
                        WorkspaceTextEdit(4, 8, "w><l", "B"),
                    ),
                ),
            ),
            mapOf(path to original),
        )
        assertRejected(overlapping, "JVW-CHANGE-EDITS-OVERLAP")

        val mismatched = WorkspaceChangePlanner.plan(
            changeSet(
                WorkspaceFileChange(
                    path,
                    WorkspaceFileChangeMode.MODIFY,
                    CanonicalDiscoveryJson.sha256(original),
                    edits = listOf(WorkspaceTextEdit(0, 6, "<other>", "replacement")),
                ),
            ),
            mapOf(path to original),
        )
        assertRejected(mismatched, "JVW-CHANGE-EXPECTED-TEXT-MISMATCH")

        val traversal = WorkspaceChangePlanner.plan(
            changeSet(
                WorkspaceFileChange(
                    "../outside.xml",
                    WorkspaceFileChangeMode.CREATE,
                    null,
                    createContent = "<view/>",
                ),
            ),
            emptyMap(),
        )
        assertRejected(traversal, "JVW-CHANGE-PATH-REJECTED")
    }

    @Test
    fun `creates only absent files and produces deterministic revision-bound plans`() {
        val changeSet = WorkspaceChangeSet(
            id = "create:loan-view",
            label = "Create Loan view",
            files = listOf(
                WorkspaceFileChange(
                    relativePath = "loan/src/main/resources/views/loan-view.xml",
                    mode = WorkspaceFileChangeMode.CREATE,
                    baseRevisionFingerprint = null,
                    createContent = "<view id=\"Loan.list\"/>",
                ),
            ),
        )

        val first = WorkspaceChangePlanner.plan(changeSet, emptyMap())
        val second = WorkspaceChangePlanner.plan(changeSet, emptyMap())
        assertTrue(first.accepted)
        assertEquals(first, second)

        val conflict = WorkspaceChangePlanner.plan(
            changeSet,
            mapOf("loan/src/main/resources/views/loan-view.xml" to "<view id=\"manual\"/>"),
        )
        assertRejected(conflict, "JVW-CHANGE-CREATE-CONFLICT")
    }

    private fun changeSet(file: WorkspaceFileChange): WorkspaceChangeSet =
        WorkspaceChangeSet("test-change", "Test change", listOf(file))

    private fun assertRejected(plan: WorkspaceChangePlan, code: String) {
        assertFalse(plan.accepted)
        assertTrue(plan.files.isEmpty())
        assertTrue(plan.issues.any { it.code == code }, plan.issues.toString())
    }
}
