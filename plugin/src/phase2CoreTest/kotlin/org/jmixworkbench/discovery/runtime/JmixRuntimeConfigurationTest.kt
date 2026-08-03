package org.jmixworkbench.discovery.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JmixRuntimeConfigurationTest {
    @Test
    fun `properties resolve runtime keys and placeholder defaults`() {
        val parsed = JmixRuntimeConfigurationParser.parseProperties(
            """
            server.port=${'$'}{SERVER_PORT:8181}
            server.servlet.context-path = /payroll/
            jmix.core.hot-deploy-enabled=true
            multiline=hello\
              world
            """.trimIndent(),
        )

        assertEquals("8181", parsed.resolved("server.port").value)
        assertEquals("/payroll/", parsed.resolved("server.servlet.context-path").value)
        assertEquals("helloworld", parsed.resolved("multiline").value)
        assertEquals("", JmixRuntimeConfigurationParser.normalizeContextPath("/"))
        assertEquals("/payroll", JmixRuntimeConfigurationParser.normalizeContextPath("/payroll/"))
    }

    @Test
    fun `yaml flattens nested Spring and Jmix keys`() {
        val parsed = JmixRuntimeConfigurationParser.parseYaml(
            """
            server:
              port: 8090
              servlet:
                context-path: "/enterprise"
            jmix:
              core:
                unsafe-runtime-features-enabled: false
            """.trimIndent(),
        )

        assertEquals("8090", parsed.resolved("server.port").value)
        assertEquals("/enterprise", parsed.resolved("server.servlet.context-path").value)
        assertEquals("false", parsed.boolean("jmix.core.unsafe-runtime-features-enabled", true).value)
    }

    @Test
    fun `environment-only placeholders remain unresolved`() {
        val parsed = JmixRuntimeConfigurationParser.parseProperties("server.port=${'$'}{SERVER_PORT}")
        val resolved = parsed.resolved("server.port")

        assertNull(resolved.value)
        assertTrue("SERVER_PORT" in resolved.unresolvedPlaceholders)
    }

    @Test
    fun `later sources override base configuration`() {
        val base = JmixRuntimeConfigurationParser.parseProperties("server.port=8080")
        val profile = JmixRuntimeConfigurationParser.parseYaml("server:\n  port: 9090")

        assertEquals(
            "9090",
            JmixRuntimeConfigurationParser.merge(listOf(base, profile)).resolved("server.port").value,
        )
    }

    @Test
    fun `profile yaml documents do not override default configuration`() {
        val documents = JmixRuntimeConfigurationParser.parseYamlDocuments(
            """
            server:
              port: 8080
            ---
            spring:
              config:
                activate:
                  on-profile: payroll
            server:
              port: 8181
            """.trimIndent(),
        )

        assertEquals("8080", JmixRuntimeConfigurationParser.parseYaml(
            """
            server:
              port: 8080
            ---
            spring:
              config:
                activate:
                  on-profile: payroll
            server:
              port: 8181
            """.trimIndent(),
        ).resolved("server.port").value)
        assertEquals(setOf("payroll"), documents[1].profiles)
        assertEquals("8181", documents[1].configuration.resolved("server.port").value)
    }
}
