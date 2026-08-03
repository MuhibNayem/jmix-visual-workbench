package org.jmixworkbench.generator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RestApiSourcePatcherTest {
    @Test
    fun `adds method to matching service without touching manual XML`() {
        val source = """
            <?xml version="1.0" encoding="UTF-8"?>
            <services xmlns="http://jmix.io/schema/rest/services" enterprise-mode="strict">
                <!-- manually maintained -->
                <extension owner="bank"/>
                <service name="loan_LoanService" audit="retained">
                    <method name="existing">
                        <custom-policy id="four-eyes"/>
                    </method>
                </service>
            </services>
        """.trimIndent() + "\n"

        val result = apply(
            source,
            RestApiSourcePatcher.add(
                source,
                RestApiXmlContract.ServiceMethod(
                    "loan_LoanService",
                    "approve",
                    listOf(
                        RestApiXmlParameter("loanId", "java.util.UUID"),
                        RestApiXmlParameter("comment"),
                    ),
                ),
            ),
        )

        assertTrue("<!-- manually maintained -->" in result)
        assertTrue("""<extension owner="bank"/>""" in result)
        assertTrue("""audit="retained"""" in result)
        assertTrue("""<custom-policy id="four-eyes"/>""" in result)
        assertTrue("""<method name="approve">""" in result)
        assertTrue("""<param name="loanId" type="java.util.UUID"/>""" in result)
        assertTrue("""<param name="comment"/>""" in result)
        assertEquals(1, Regex("""<service name="loan_LoanService"""").findAll(result).count())
    }

    @Test
    fun `adds new service before namespaced root close`() {
        val source = """
            <rest:services xmlns:rest="http://jmix.io/schema/rest/services">
                <rest:service name="existing_Service"/>
            </rest:services>
        """.trimIndent()

        val result = apply(
            source,
            RestApiSourcePatcher.add(
                source,
                RestApiXmlContract.ServiceMethod("fund_SettlementService", "settle", emptyList()),
            ),
        )

        assertTrue("""<service name="fund_SettlementService">""" in result)
        assertTrue("""<method name="settle">""" in result)
        assertTrue(result.indexOf("fund_SettlementService") < result.indexOf("</rest:services>"))
    }

    @Test
    fun `adds typed JPQL query and preserves CDATA terminator safely`() {
        val source = """
            <queries xmlns="http://jmix.io/schema/rest/queries">
                <!-- keep -->
            </queries>
        """.trimIndent()
        val jpql = "select e from loan_LoanApp e where e.note <> ']]>' and e.state = :state"

        val result = apply(
            source,
            RestApiSourcePatcher.add(
                source,
                RestApiXmlContract.Query(
                    name = "loansByState",
                    entityName = "loan_LoanApp",
                    fetchPlan = "loanApp-with-account",
                    jpql = jpql,
                    parameters = listOf(RestApiXmlParameter("state", "java.lang.String")),
                ),
            ),
        )

        assertTrue("<!-- keep -->" in result)
        assertTrue("""name="loansByState" entity="loan_LoanApp" fetchPlan="loanApp-with-account"""" in result)
        assertTrue("]]]]><![CDATA[>" in result)
        assertTrue("""<param name="state" type="java.lang.String"/>""" in result)
    }

    @Test
    fun `rejects duplicates and inconsistent query parameters`() {
        val services = """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="loan_Service">
                    <method name="approve"><param name="id" type="java.util.UUID"/></method>
                </service>
            </services>
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> {
            RestApiSourcePatcher.add(
                services,
                RestApiXmlContract.ServiceMethod(
                    "loan_Service",
                    "approve",
                    listOf(RestApiXmlParameter("id", "java.util.UUID")),
                ),
            )
        }

        val queries = """<queries xmlns="http://jmix.io/schema/rest/queries"></queries>"""
        val failure = assertFailsWith<IllegalArgumentException> {
            RestApiSourcePatcher.add(
                queries,
                RestApiXmlContract.Query(
                    "byState",
                    "loan_LoanApp",
                    "_base",
                    "select e from loan_LoanApp e where e.state = :state",
                    listOf(RestApiXmlParameter("wrong", "java.lang.String")),
                ),
            )
        }
        assertTrue(failure.message.orEmpty().contains("PARAMETER-MISMATCH"))
    }

    @Test
    fun `updates service contract while preserving custom method content and attributes`() {
        val source = """
            <services xmlns="http://jmix.io/schema/rest/services">
                <service name="loan_Service">
                    <method name="approve" audit="four-eyes">
                        <!-- manual authorization note -->
                        <param name="id" type="java.util.UUID"/>
                        <custom-policy bean="loanAuthorizer"/>
                    </method>
                </service>
            </services>
        """.trimIndent() + "\n"
        val edits = RestApiSourcePatcher.update(
            source,
            RestApiXmlTarget.ServiceMethod(
                "loan_Service",
                "approve",
                listOf("java.util.UUID"),
            ),
            RestApiXmlContract.ServiceMethod(
                "loan_Service",
                "approveAndPost",
                listOf(
                    RestApiXmlParameter("id", "java.util.UUID"),
                    RestApiXmlParameter("postingDate", "java.time.LocalDate"),
                ),
            ),
        )
        val result = apply(source, edits)

        assertTrue("""name="approveAndPost" audit="four-eyes"""" in result)
        assertTrue("<!-- manual authorization note -->" in result)
        assertTrue("""<custom-policy bean="loanAuthorizer"/>""" in result)
        assertTrue("""<param name="postingDate" type="java.time.LocalDate"/>""" in result)
        assertEquals(1, Regex("""name="id"""").findAll(result).count())
    }

    @Test
    fun `updates query known fields and params without removing extensions`() {
        val source = """
            <queries xmlns="http://jmix.io/schema/rest/queries">
                <query name="byState" entity="loan_LoanApp" fetchPlan="_base" cacheable="true">
                    <jpql timeout="reviewed"><![CDATA[select e from loan_LoanApp e where e.state = :state]]></jpql>
                    <!-- business query -->
                    <params>
                        <param name="state" type="java.lang.String"/>
                        <parameter-extension owner="risk"/>
                    </params>
                    <result-policy mask="salary"/>
                </query>
            </queries>
        """.trimIndent() + "\n"
        val edits = RestApiSourcePatcher.update(
            source,
            RestApiXmlTarget.Query("byState", "loan_LoanApp"),
            RestApiXmlContract.Query(
                "approvedByBranch",
                "loan_LoanApp",
                "loan-with-account",
                "select e from loan_LoanApp e where e.state = :state and e.branch.code = :branch",
                listOf(
                    RestApiXmlParameter("state", "java.lang.String"),
                    RestApiXmlParameter("branch", "java.lang.String"),
                ),
            ),
        )
        val result = apply(source, edits)

        assertTrue("""name="approvedByBranch"""" in result)
        assertTrue("""fetchPlan="loan-with-account"""" in result)
        assertTrue("""cacheable="true"""" in result)
        assertTrue("""timeout="reviewed"""" in result)
        assertTrue("<!-- business query -->" in result)
        assertTrue("""<parameter-extension owner="risk"/>""" in result)
        assertTrue("""<result-policy mask="salary"/>""" in result)
        assertTrue("e.branch.code = :branch" in result)
        assertTrue("""<param name="branch" type="java.lang.String"/>""" in result)
    }

    @Test
    fun `removes only selected contract`() {
        val source = """
            <services xmlns="http://jmix.io/schema/rest/services">
                <!-- keep -->
                <service name="loan_Service">
                    <method name="approve"><param name="id" type="java.util.UUID"/></method>
                    <method name="reject"/>
                </service>
            </services>
        """.trimIndent() + "\n"
        val result = apply(
            source,
            RestApiSourcePatcher.remove(
                source,
                RestApiXmlTarget.ServiceMethod("loan_Service", "approve", listOf("java.util.UUID")),
            ),
        )

        assertTrue("<!-- keep -->" in result)
        assertTrue("""<method name="reject"/>""" in result)
        assertTrue("""<service name="loan_Service">""" in result)
        assertTrue("approve" !in result)
    }

    private fun apply(source: String, edit: org.jmixworkbench.discovery.change.WorkspaceTextEdit): String =
        source.replaceRange(edit.startOffset, edit.endOffset, edit.replacement)

    private fun apply(
        source: String,
        edits: List<org.jmixworkbench.discovery.change.WorkspaceTextEdit>,
    ): String = edits.sortedByDescending { it.startOffset }
        .fold(source) { current, edit ->
            current.replaceRange(edit.startOffset, edit.endOffset, edit.replacement)
        }
}
