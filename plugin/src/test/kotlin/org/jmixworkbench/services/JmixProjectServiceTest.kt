package org.jmixworkbench.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JmixProjectServiceTest {
    @Test
    fun `detects Groovy and Kotlin Jmix project id declarations`() {
        assertEquals(
            "payroll",
            JmixProjectService.detectProjectId(
                """
                plugins {
                    id 'io.jmix' version '2.8.3'
                }
                jmix {
                    projectId = 'payroll'
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            "loan",
            JmixProjectService.detectProjectId(
                """
                plugins {
                    id("io.jmix") version "3.0.0"
                }
                jmix {
                    projectId.set("loan")
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            "fund",
            JmixProjectService.detectProjectId("jmix.projectId = \"fund\""),
        )
    }

    @Test
    fun `does not mistake unrelated Gradle project identifiers for Jmix project id`() {
        assertNull(
            JmixProjectService.detectProjectId(
                """
                plugins {
                    java
                }
                projectId = "not-jmix"
                """.trimIndent(),
            ),
        )
    }
}
