package org.jmixworkbench.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JmixEnvironmentConfigurationServiceTest {
    @Test
    fun `parses dotenv syntax with exact ranges and line separators`() {
        val content =
            "# local values\r\n" +
                "export API_URL=\"https://example.test/api\"\r\n" +
                "PLAIN=value\r\n" +
                "EMPTY=\r\n"

        val document = JmixEnvironmentConfigurationService.parseEnvironmentDocument(content)

        assertTrue(document.issues.isEmpty())
        assertEquals("\r\n", document.lineSeparator)
        assertEquals(listOf("API_URL", "PLAIN", "EMPTY"), document.entries.map { it.name })
        assertEquals("https://example.test/api", document.entries[0].value)
        assertEquals('"', document.entries[0].quote)
        assertEquals("value", document.entries[1].value)
        assertEquals("", document.entries[2].value)
        document.entries.forEach { entry ->
            assertTrue(entry.lineStartOffset < entry.lineEndOffset)
            assertTrue(entry.valueStartOffset <= entry.valueEndOffset)
        }
    }

    @Test
    fun `rejects ambiguous duplicate and unterminated declarations`() {
        val document = JmixEnvironmentConfigurationService.parseEnvironmentDocument(
            "TOKEN=first\nTOKEN=second\nBROKEN=\"unterminated\nnot-an-assignment\n",
        )

        assertTrue(document.issues.any { it.code == "JVW-ENV-DUPLICATE" })
        assertTrue(document.issues.any { it.code == "JVW-ENV-VALUE-SYNTAX-UNSUPPORTED" })
        assertTrue(document.issues.any { it.code == "JVW-ENV-SYNTAX-UNSUPPORTED" })
        assertFalse(document.issues.isEmpty())
    }
}
