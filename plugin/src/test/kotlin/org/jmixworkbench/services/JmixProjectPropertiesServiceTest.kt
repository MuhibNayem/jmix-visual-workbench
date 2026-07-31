package org.jmixworkbench.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JmixProjectPropertiesServiceTest {
    @Test
    fun `builds profile and multi-store inventory without exposing secrets`() {
        val snapshot = JmixProjectPropertiesService.parsePropertiesProfile(
            relativePath = "payroll/src/main/resources/application-prod.properties",
            content = """
                server.port=8088
                server.servlet.context-path=/payroll
                spring.profiles.active=dev,local
                jmix.core.available-locales=en|English,bn|বাংলা
                jmix.core.additional-stores=loan, audit
                main.datasource.url=jdbc:postgresql://db/payroll?password=raw-url-secret
                main.datasource.username=payroll
                main.datasource.password=raw-secret
                loan.datasource.url=jdbc:postgresql://loan_user:loan_secret@db/loan
                loan.datasource.password=${'$'}{LOAN_PASSWORD:fallback-secret}
                loan.liquibase.change-log=com/company/loan/changelog.xml
                audit.datasource.url=jdbc:postgresql://db/audit
                orphan.datasource.url=jdbc:postgresql://db/orphan
                oracle.datasource.url=jdbc:oracle:thin:ledger/ledger-secret@db.example:1521/ledger
                oauth.client-secret=very-private-client-value
                integration.api-key=private-integration-key
            """.trimIndent(),
        )

        assertEquals("payroll", snapshot.modulePath)
        assertEquals("prod", snapshot.profile)
        assertEquals("8088", snapshot.serverPort)
        assertEquals("/payroll", snapshot.contextPath)
        assertEquals(listOf("dev", "local"), snapshot.activeProfiles)
        assertEquals(listOf("en", "bn"), snapshot.availableLocales)
        assertEquals(listOf("main", "audit", "loan", "oracle", "orphan"), snapshot.stores.map { it.name })
        assertTrue(snapshot.stores.single { it.name == "audit" }.declaredAdditional)
        assertFalse(snapshot.stores.single { it.name == "orphan" }.declaredAdditional)
        assertTrue(snapshot.stores.single { it.name == "main" }.passwordConfigured)
        assertFalse(snapshot.stores.single { it.name == "main" }.passwordUsesPlaceholder)
        assertTrue(snapshot.stores.single { it.name == "loan" }.passwordUsesPlaceholder)
        assertEquals(
            "jdbc:postgresql://db/payroll?password=••••••••",
            snapshot.stores.single { it.name == "main" }.url,
        )
        assertEquals(
            "jdbc:postgresql://loan_user:••••••••@db/loan",
            snapshot.stores.single { it.name == "loan" }.url,
        )
        assertEquals(
            "jdbc:oracle:thin:ledger/••••••••@db.example:1521/ledger",
            snapshot.stores.single { it.name == "oracle" }.url,
        )
        assertEquals(
            "••••••••",
            snapshot.properties.single { it.key == "main.datasource.password" }.displayValue,
        )
        assertEquals(
            "\${LOAN_PASSWORD:••••••••}",
            snapshot.properties.single { it.key == "loan.datasource.password" }.displayValue,
        )
        assertEquals(
            "••••••••",
            snapshot.properties.single { it.key == "oauth.client-secret" }.displayValue,
        )
        assertEquals(
            "••••••••",
            snapshot.properties.single { it.key == "integration.api-key" }.displayValue,
        )
        val serializedShape = snapshot.toString()
        assertFalse("raw-secret" in serializedShape)
        assertFalse("raw-url-secret" in serializedShape)
        assertFalse("loan_secret" in serializedShape)
        assertFalse("fallback-secret" in serializedShape)
        assertFalse("very-private-client-value" in serializedShape)
        assertFalse("ledger-secret" in serializedShape)
        assertFalse("private-integration-key" in serializedShape)
    }

    @Test
    fun `uses default profile and does not invent optional settings`() {
        val snapshot = JmixProjectPropertiesService.parsePropertiesProfile(
            relativePath = "src/main/resources/application.properties",
            content = """
                main.datasource.url=jdbc:hsqldb:file:.jmix/hsqldb/app
                custom.flag=true
            """.trimIndent(),
        )

        assertEquals("", snapshot.modulePath)
        assertEquals("default", snapshot.profile)
        assertNull(snapshot.serverPort)
        assertNull(snapshot.contextPath)
        assertTrue(snapshot.activeProfiles.isEmpty())
        assertTrue(snapshot.availableLocales.isEmpty())
        assertEquals(listOf("main"), snapshot.stores.map { it.name })
        assertFalse(snapshot.stores.single().passwordConfigured)
    }

    @Test
    fun `parses escaped and continued properties with exact physical ranges`() {
        val content =
            "server.port : 8080\r\n" +
                "jmix.core.available-locales=en|English,\\\r\n" +
                "  bn|বাংলা\r\n" +
                "escaped\\ key=leading\\ value\r\n"

        val document = JmixProjectPropertiesService.parseEditablePropertiesDocument(content)

        assertEquals("\r\n", document.lineSeparator)
        assertEquals(
            listOf("server.port", "jmix.core.available-locales", "escaped key"),
            document.entries.map { it.key },
        )
        assertEquals("8080", document.entries[0].decodedValue)
        assertFalse(document.entries[0].continued)
        assertEquals("en|English,bn|বাংলা", document.entries[1].decodedValue)
        assertTrue(document.entries[1].continued)
        assertEquals(
            "en|English,\\\r\n  bn|বাংলা",
            content.substring(
                document.entries[1].valueStartOffset,
                document.entries[1].valueEndOffset,
            ),
        )
        assertEquals("leading value", document.entries[2].decodedValue)
    }

    @Test
    fun `preserves legacy carriage return line separators for deterministic append`() {
        val document = JmixProjectPropertiesService.parseEditablePropertiesDocument(
            "server.port=8080\rserver.servlet.context-path=/app\r",
        )

        assertEquals("\r", document.lineSeparator)
        assertEquals(
            listOf("server.port", "server.servlet.context-path"),
            document.entries.map { it.key },
        )
    }
}
