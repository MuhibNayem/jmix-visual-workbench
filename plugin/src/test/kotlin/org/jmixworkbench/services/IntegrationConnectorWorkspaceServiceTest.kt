package org.jmixworkbench.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationConnectorModel
import org.jmixworkbench.model.IntegrationHttpMethod
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationAuthenticationModel
import org.jmixworkbench.model.IntegrationDeliveryGuarantee
import org.jmixworkbench.model.IntegrationOutboxModel
import org.jmixworkbench.model.IntegrationReliabilityModel
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegrationConnectorWorkspaceServiceTest : HeavyPlatformTestCase() {
    fun testPlansConnectorOutboxMigrationAndRootIncludeAsOneAtomicChange() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "build.gradle.kts",
                """
                plugins { id("io.jmix") version "2.8.3" }
                dependencies {
                    implementation("io.jmix.flowui:jmix-flowui-starter")
                    implementation("org.springframework.kafka:spring-kafka")
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/loan/LoanEvent.java",
                """
                package com.acme.loan;
                public record LoanEvent(String loanId) {}
                """.trimIndent(),
            )
            write(
                root,
                "src/main/resources/application.properties",
                "payroll.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml",
            )
            write(
                root,
                "src/main/resources/db/changelog/db.changelog-master.xml",
                """
                <databaseChangeLog
                    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                </databaseChangeLog>
                """.trimIndent(),
            )
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )
        val service = IntegrationConnectorWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        val store = requireNotNull(workspace.dataStores.firstOrNull { it.moduleId == destination.moduleId }) {
            "No schema data store was indexed: ${workspace.issues}"
        }
        val model = IntegrationConnectorModel(
            name = "Durable loan events",
            destinationId = destination.id,
            packageName = "com.acme.loan.integration",
            className = "LoanEventPublisher",
            beanName = "loanEventPublisher",
            kind = IntegrationConnectorKind.KAFKA_PUBLISHER,
            configurationPrefix = "loan.events",
            addressProperty = "loan.events.topic",
            payloadJavaType = "com.acme.loan.LoanEvent",
            reliability = IntegrationReliabilityModel(
                deliveryGuarantee = IntegrationDeliveryGuarantee.AT_LEAST_ONCE,
                transactional = true,
                outboxEnabled = true,
                outbox = IntegrationOutboxModel(
                    storeId = store.id,
                    tableName = "jvw_loan_event_outbox",
                    dataSourceBean = "forgedDataSource",
                    transactionManagerBean = "forgedTransactionManager",
                ),
            ),
        )

        val proposal = service.propose(model)
        val changeSet = requireNotNull(proposal.changeSet) {
            "Outbox connector proposal rejected: ${proposal.issues}"
        }
        assertTrue(proposal.issues.isEmpty(), proposal.issues.joinToString())
        assertTrue(changeSet.files.size == 4, "Expected Java, policy, migration and root include: ${changeSet.files}")
        val migration = changeSet.files.single {
            it.relativePath.endsWith(".xml") && it.mode.name == "CREATE"
        }
        val rootEdit = changeSet.files.single {
            it.relativePath.endsWith("db.changelog-master.xml") && it.mode.name == "MODIFY"
        }
        val java = changeSet.files.single { it.relativePath.endsWith("LoanEventPublisher.java") }

        assertContains(requireNotNull(migration.createContent), "jvw_loan_event_outbox")
        assertContains(requireNotNull(migration.createContent), "<rollback>")
        assertContains(rootEdit.edits.single().replacement, "<include file=\"")
        assertContains(requireNotNull(java.createContent), "com.fasterxml.jackson.databind.ObjectMapper")
        assertContains(requireNotNull(java.createContent), "public String enqueue")
        assertContains(requireNotNull(java.createContent), "SpecificOperationAccessContext")
        assertContains(requireNotNull(java.createContent), "@Qualifier(\"payrollDataSource\") DataSource dataSource")
        assertContains(
            requireNotNull(java.createContent),
            "@Qualifier(\"payrollTransactionManager\") PlatformTransactionManager transactionManager",
        )
        assertContains(requireNotNull(java.createContent), "@Transactional(\"payrollTransactionManager\")")
        assertFalse(requireNotNull(java.createContent).contains("forgedDataSource"))
        assertFalse(requireNotNull(java.createContent).contains("forgedTransactionManager"))
        assertTrue(service.preview(model).accepted)
    }

    fun testCreatesTwoFileOwnedConnectorAndLocksWhenPolicyChanges() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "build.gradle.kts",
                """
                plugins { id("io.jmix") version "2.8.3" }
                dependencies {
                    implementation("io.jmix.flowui:jmix-flowui-starter")
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/loan/LoanApplication.java",
                """
                package com.acme.loan;
                public class LoanApplication {}
                """.trimIndent(),
            )
            write(root, "src/main/resources/application.properties", "spring.application.name=loan")
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )
        val service = IntegrationConnectorWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        assertTrue(destination.capabilities.any { it.name == "SPRING_WEB" })
        val model = IntegrationConnectorModel(
            name = "Loan risk lookup",
            destinationId = destination.id,
            packageName = "com.acme.loan.integration",
            className = "LoanRiskConnector",
            beanName = "loanRiskConnector",
            kind = IntegrationConnectorKind.HTTP_CLIENT,
            configurationPrefix = "loan.risk",
            addressProperty = "loan.risk.url",
            responseJavaType = "java.lang.String",
            httpMethod = IntegrationHttpMethod.GET,
        )

        val proposal = service.propose(model)
        val changes = requireNotNull(proposal.changeSet) {
            "Connector proposal rejected: ${proposal.issues}"
        }.files
        assertTrue(proposal.issues.isEmpty(), proposal.issues.joinToString())
        assertTrue(changes.size == 2)
        val javaChange = changes.single { it.relativePath.endsWith(".java") }
        val policyChange = changes.single { it.relativePath.endsWith(".properties") }
        assertContains(requireNotNull(javaChange.createContent), "// JVW-INTEGRATION-MODEL:")
        assertContains(requireNotNull(policyChange.createContent), "# JVW-INTEGRATION-MODEL:")
        assertTrue(service.preview(model).accepted)

        WriteAction.run<RuntimeException> {
            changes.forEach { change -> write(root, change.relativePath, requireNotNull(change.createContent)) }
        }
        ApplicationGraphService.getInstance(project).invalidate()
        val document = requireNotNull(
            service.load(forceRefresh = true).existingDocuments.singleOrNull(),
        ) { "Owned integration connector was not rediscovered." }
        assertTrue(document.editable)

        val policyFile = requireNotNull(root.findFileByRelativePath(policyChange.relativePath))
        WriteAction.run<RuntimeException> {
            VfsUtil.saveText(policyFile, requireNotNull(policyChange.createContent) + "\n# manually changed\n")
        }
        val rejected = service.propose(document.model)

        assertTrue(rejected.changeSet == null)
        assertTrue(rejected.issues.any { it.code == "JVW-INTEGRATION-SOURCE-NOT-OWNED" })
        assertFalse(service.load(forceRefresh = true).existingDocuments.single().editable)
    }

    fun testEverySupportedConnectorKindPassesTargetWorkspaceSyntaxValidation() {
        val root = getOrCreateProjectBaseDir()
        WriteAction.run<RuntimeException> {
            write(
                root,
                "build.gradle.kts",
                """
                plugins { id("io.jmix") version "2.8.3" }
                dependencies {
                    implementation("io.jmix.flowui:jmix-flowui-starter")
                    implementation("org.springframework.kafka:spring-kafka")
                    implementation("org.springframework.amqp:spring-rabbit")
                    implementation("org.springframework.integration:spring-integration-sftp")
                    implementation("io.jmix.email:jmix-email-starter")
                    implementation("io.jmix.core:jmix-core")
                    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/integration/LoanEventHandler.java",
                """
                package com.acme.integration;
                import org.springframework.stereotype.Service;

                @Service
                public class LoanEventHandler {
                    public void handle(String payload) {
                    }
                }
                """.trimIndent(),
            )
            write(
                root,
                "src/main/java/com/acme/integration/OAuth2ClientConfiguration.java",
                """
                package com.acme.integration;
                import org.springframework.context.annotation.Bean;
                import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

                public class OAuth2ClientConfiguration {
                    @Bean("authorizedClientManager")
                    public OAuth2AuthorizedClientManager authorizedClientManager() {
                        return null;
                    }
                }
                """.trimIndent(),
            )
            write(root, "src/main/resources/application.properties", "spring.application.name=integration")
        }
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/java")),
            JavaSourceRootType.SOURCE,
        )
        PsiTestUtil.addSourceRoot(
            module,
            requireNotNull(root.findFileByRelativePath("src/main/resources")),
            JavaResourceRootType.RESOURCE,
        )
        val service = IntegrationConnectorWorkspaceService.getInstance(project)
        val workspace = service.load(forceRefresh = true)
        val destination = requireNotNull(workspace.destinations.firstOrNull())
        assertTrue(workspace.oauth2Managers.any { it.beanName == "authorizedClientManager" })
        val supported = IntegrationConnectorKind.entries

        supported.forEach { kind ->
            val classStem = kind.name.lowercase()
                .split('_')
                .joinToString("") { word -> word.replaceFirstChar(Char::uppercase) }
            val consumer = kind in setOf(
                IntegrationConnectorKind.KAFKA_CONSUMER,
                IntegrationConnectorKind.RABBIT_CONSUMER,
            )
            val oauth2 = kind == IntegrationConnectorKind.IDENTITY_PROVIDER
            val model = IntegrationConnectorModel(
                name = "$classStem connector",
                destinationId = destination.id,
                packageName = "com.acme.integration",
                className = "${classStem}Connector",
                beanName = classStem.replaceFirstChar(Char::lowercase) + "Connector",
                kind = kind,
                configurationPrefix = "integration.${kind.name.lowercase().replace('_', '-')}",
                addressProperty = "integration.${kind.name.lowercase().replace('_', '-')}.address",
                payloadJavaType = if (kind.name.contains("SFTP") || kind.name.contains("STORAGE")) {
                    "byte[]"
                } else {
                    "java.lang.String"
                },
                responseJavaType = if (kind == IntegrationConnectorKind.SFTP_DOWNLOAD) {
                    "byte[]"
                } else {
                    "void"
                },
                handlerBeanClass = if (consumer) "com.acme.integration.LoanEventHandler" else null,
                handlerFieldName = if (consumer) "loanEventHandler" else null,
                handlerMethod = if (consumer) "handle" else null,
                authentication = if (oauth2) {
                    IntegrationAuthenticationModel(
                        kind = IntegrationAuthenticationKind.OAUTH2_CLIENT_CREDENTIALS,
                        authorizedClientManagerBeanName = "authorizedClientManager",
                        clientRegistrationIdProperty = "integration.identity.registration-id",
                        principalNameProperty = "integration.identity.principal-name",
                    )
                } else {
                    IntegrationAuthenticationModel()
                },
            )

            val proposal = service.propose(model)
            val changeSet = requireNotNull(proposal.changeSet) {
                "$kind connector rejected: ${proposal.issues}"
            }
            assertTrue(proposal.issues.isEmpty(), "$kind: ${proposal.issues}")
            assertTrue(changeSet.files.size == 2, "$kind did not produce the owned Java/policy pair")
            assertContains(
                requireNotNull(changeSet.files.single { it.relativePath.endsWith(".java") }.createContent),
                "@SuppressWarnings(\"JVW-INTEGRATION-CONNECTOR\")",
            )
        }
    }

    private fun write(root: VirtualFile, path: String, content: String) {
        val parentPath = path.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            root
        } else {
            requireNotNull(VfsUtil.createDirectoryIfMissing(root, parentPath))
        }
        VfsUtil.saveText(parent.findOrCreateChildData(this, path.substringAfterLast('/')), content)
    }
}
