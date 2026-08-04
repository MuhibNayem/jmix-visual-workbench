package org.jmixworkbench.discovery.semantic

import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactOwner
import org.jmixworkbench.discovery.model.RelationshipType
import org.jmixworkbench.discovery.model.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the tokenized implicit-reference linking semantics: one tokenization per
 * source text must decide membership exactly like the previous delimiter-aware
 * substring scans, including dotted-run and identifier-boundary edge cases.
 */
class ImplicitReferenceLinkingTest {
    private fun ownedSource(moduleId: String, path: String, content: String) = GraphSourceFile(
        relativePath = path,
        content = content.trimIndent(),
        owner = ArtifactOwner("linking-build", moduleId, "main"),
        language = SourceLanguage.JAVA,
    )

    private fun index(vararg files: GraphSourceFile) =
        ApplicationGraphIndexer().index(ApplicationGraphIndexInput(files.toList()))

    @Test
    fun `qualified reference only matches a complete dotted run`() {
        val result = index(
            ownedSource(
                "sales",
                "sales/src/main/java/com/acme/sales/Customer.java",
                "package com.acme.sales; @JmixEntity public class Customer {}",
            ),
            ownedSource(
                "sales",
                "sales/src/main/java/com/acme/sales/CustomerDirectoryService.java",
                """
                package com.acme.sales;
                @Service
                public class CustomerDirectoryService {
                    Object directory = com.acme.sales.CustomerDirectory.empty();
                }
                """,
            ),
        )

        val service = result.artifacts.single { it.displayName == "CustomerDirectoryService" }
        val uses = result.relationships.filter {
            it.sourceArtifactId == service.id && it.type == RelationshipType.USES_ENTITY
        }
        assertTrue(
            uses.none { it.targetArtifactId != null },
            "com.acme.sales.CustomerDirectory must not resolve to the Customer entity",
        )
    }

    @Test
    fun `simple name matches respect identifier boundaries`() {
        val result = index(
            ownedSource(
                "sales",
                "sales/src/main/java/com/acme/sales/Customer.java",
                "package com.acme.sales; @JmixEntity public class Customer {}",
            ),
            ownedSource(
                "sales",
                "sales/src/main/java/com/acme/sales/CustomerScopeService.java",
                """
                package com.acme.sales;
                @Service
                public class CustomerScopeService {
                    Object scope = CustomerScope.defaults();
                    Object exact = Customer.class;
                }
                """,
            ),
        )

        val service = result.artifacts.single { it.displayName == "CustomerScopeService" }
        val entity = result.artifacts.single { it.kind == ArtifactKind.ENTITY }
        val uses = result.relationships.filter {
            it.sourceArtifactId == service.id && it.type == RelationshipType.USES_ENTITY
        }
        assertEquals(
            listOf(entity.id),
            uses.mapNotNull { it.targetArtifactId }.distinct(),
            "Only the bounded Customer occurrence may link to the entity",
        )
    }

    @Test
    fun `qualified reference wins across duplicate simple names`() {
        val result = index(
            ownedSource(
                "retail",
                "retail/src/main/java/com/bank/retail/Customer.java",
                "package com.bank.retail; @JmixEntity public class Customer {}",
            ),
            ownedSource(
                "corporate",
                "corporate/src/main/java/com/bank/corporate/Customer.java",
                "package com.bank.corporate; @JmixEntity public class Customer {}",
            ),
            ownedSource(
                "reporting",
                "reporting/src/main/java/com/bank/reporting/CustomerAuditService.java",
                """
                package com.bank.reporting;
                @Service
                public class CustomerAuditService {
                    com.bank.corporate.Customer corporate;
                }
                """,
            ),
        )

        val service = result.artifacts.single { it.displayName == "CustomerAuditService" }
        val corporateEntity = result.artifacts.single {
            it.kind == ArtifactKind.ENTITY && it.owner.moduleId == "corporate"
        }
        val uses = result.relationships.filter {
            it.sourceArtifactId == service.id && it.type == RelationshipType.USES_ENTITY
        }
        assertEquals(listOf(corporateEntity.id), uses.mapNotNull { it.targetArtifactId }.distinct())
    }

    @Test
    fun `short symbols never create implicit links`() {
        val result = index(
            ownedSource(
                "sales",
                "sales/src/main/java/com/acme/sales/Id.java",
                "package com.acme.sales; @JmixEntity public class Id {}",
            ),
            ownedSource(
                "sales",
                "sales/src/main/java/com/acme/sales/IdService.java",
                """
                package com.acme.sales;
                @Service
                public class IdService {
                    Object id = Id.next();
                }
                """,
            ),
        )

        val service = result.artifacts.single { it.displayName == "IdService" }
        val uses = result.relationships.filter {
            it.sourceArtifactId == service.id && it.type == RelationshipType.USES_ENTITY
        }
        assertTrue(uses.isEmpty(), "Two-character symbols are excluded from implicit linking")
    }
}
