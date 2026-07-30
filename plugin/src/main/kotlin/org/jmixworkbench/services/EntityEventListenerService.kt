package org.jmixworkbench.services

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.EventListenerGenerator
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.EntityType

/**
 * Creates transaction-explicit Jmix entity listeners for one exact indexed entity.
 *
 * Entity identity, module and target source root are backend-derived. The
 * client can choose language/package/class/events, but cannot redirect writes
 * to an arbitrary module or submit a spoofed entity name.
 */
@Service(Service.Level.PROJECT)
class EntityEventListenerService(private val project: Project) {

    fun preview(request: EntityEventListenerRequest): WorkspaceChangePreviewResponse {
        val proposal = propose(request)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal.issues)
    }

    fun prepareApply(request: EntityEventListenerApplyRequest): PreparedWorkspaceChange {
        val proposal = propose(request.listener)
        val changeSet = proposal.changeSet ?: return rejectedPrepared(proposal.issues)
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    internal fun propose(request: EntityEventListenerRequest): EntityEventListenerProposal {
        val schema = SchemaWorkspaceService.getInstance(project).load()
        val entity = schema.entities.singleOrNull { it.sourceLocator == request.entitySource }
            ?: return rejected(
                "JVW-ENTITY-LISTENER-SOURCE-STALE",
                "The selected entity is missing, ambiguous, or changed after the listener workflow opened. Refresh Entity Designer.",
                request.entitySource.relativePath,
            )
        if (entity.entityType != EntityType.ENTITY) {
            return rejected(
                "JVW-ENTITY-LISTENER-KIND-UNSUPPORTED",
                "Data event listeners can be created only for concrete persistent entities.",
                entity.sourceLocator.relativePath,
            )
        }
        if (request.className.length > MAX_CLASS_NAME || !JAVA_IDENTIFIER.matches(request.className)) {
            return rejected(
                "JVW-ENTITY-LISTENER-CLASS-INVALID",
                "Listener class name must be a valid Java/Kotlin identifier.",
            )
        }
        if (request.packageName.length > MAX_PACKAGE_NAME || !PACKAGE_NAME.matches(request.packageName)) {
            return rejected(
                "JVW-ENTITY-LISTENER-PACKAGE-INVALID",
                "Listener package must be a dot-separated Java/Kotlin package name.",
            )
        }
        val sourceLanguage = request.sourceLanguage
            ?: return rejected(
                "JVW-ENTITY-LISTENER-LANGUAGE-INVALID",
                "Choose Java or Kotlin for the listener source.",
            )
        val events = request.events.filterNotNull().distinct()
        if (events.size != request.events.size) {
            return rejected(
                "JVW-ENTITY-LISTENER-EVENTS-INVALID",
                "The listener contains an unknown event selection.",
            )
        }
        if (events.isEmpty()) {
            return rejected(
                "JVW-ENTITY-LISTENER-EVENTS-EMPTY",
                "Select at least one Jmix entity event.",
            )
        }
        if (events.size > EventListenerGenerator.ListenerEvent.entries.size) {
            return rejected(
                "JVW-ENTITY-LISTENER-EVENTS-INVALID",
                "The listener event selection is invalid.",
            )
        }
        if (
            request.afterCommitRequiresNewTransaction &&
            EventListenerGenerator.ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT !in events
        ) {
            return rejected(
                "JVW-ENTITY-LISTENER-TRANSACTION-INVALID",
                "A new after-commit transaction can be enabled only when the after-commit event is selected.",
            )
        }

        val graph = ApplicationGraphService.getInstance(project).graph()
        val destinations = ProjectSourceDestinationService.getInstance(project).let { service ->
            when (sourceLanguage) {
                EntitySourceLanguage.JAVA -> service.productionJava(graph)
                EntitySourceLanguage.KOTLIN -> service.productionKotlin(graph)
            }
        }.filter { it.moduleId == entity.moduleId }
        val destination = destinations.firstOrNull()
            ?: return rejected(
                "JVW-ENTITY-LISTENER-DESTINATION-MISSING",
                "No ${sourceLanguage.name.lowercase()} production source root is registered for module ${entity.moduleId}.",
            )
        val projectId = (
            schema.modules.singleOrNull { it.moduleId == entity.moduleId }?.projectId
                ?: entity.entityName.substringBefore('_').takeIf {
                    '_' in entity.entityName && it.isNotBlank()
                }
            )
            ?.replace(Regex("[^A-Za-z0-9_]"), "_")
            ?.trim('_')
            ?.takeIf(String::isNotBlank)
        val beanName = projectId
            ?.let { "${it}_${request.className}" }
            ?: request.className.replaceFirstChar { it.lowercase() }
        val model = EventListenerGenerator.ListenerModel(
            entityClassName = entity.className,
            entityQualifiedName = entity.qualifiedName,
            listenerClassName = request.className,
            packageName = request.packageName,
            beanName = beanName,
            sourceLanguage = sourceLanguage,
            events = events.sortedBy(EventListenerGenerator.ListenerEvent::ordinal),
            afterCommitRequiresNewTransaction = request.afterCommitRequiresNewTransaction,
        )
        val content = EventListenerGenerator.generate(model)
        syntaxIssue("${request.className}.${sourceLanguage.fileExtension}", sourceLanguage, content)
            ?.let { return EntityEventListenerProposal(null, listOf(it)) }
        val targetPath = listOf(
            destination.sourceRoot.trimEnd('/', '\\'),
            request.packageName.replace('.', '/'),
            "${request.className}.${sourceLanguage.fileExtension}",
        ).joinToString("/")
        val identity = buildString {
            append(entity.sourceLocator.relativePath)
            append('\u0000').append(entity.sourceLocator.revisionFingerprint)
            append('\u0000').append(targetPath)
            append('\u0000').append(content)
        }
        return EntityEventListenerProposal(
            changeSet = WorkspaceChangeSet(
                id = "entity-listener:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Create ${request.className} for ${entity.className}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = targetPath,
                        mode = WorkspaceFileChangeMode.CREATE,
                        baseRevisionFingerprint = null,
                        createContent = content,
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun syntaxIssue(
        fileName: String,
        language: EntitySourceLanguage,
        content: String,
    ): WorkspaceChangeIssue? {
        val fileType = when (language) {
            EntitySourceLanguage.JAVA -> JavaFileType.INSTANCE
            EntitySourceLanguage.KOTLIN -> {
                val type = FileTypeManager.getInstance().getFileTypeByExtension("kt")
                if (!type.name.contains("kotlin", ignoreCase = true)) {
                    return WorkspaceChangeIssue(
                        "JVW-ENTITY-LISTENER-KOTLIN-MISSING",
                        "Kotlin listener generation requires the bundled IntelliJ Kotlin plugin.",
                    )
                }
                type
            }
        }
        val psi = PsiFileFactory.getInstance(project).createFileFromText(fileName, fileType, content)
        val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java) ?: return null
        return WorkspaceChangeIssue(
            "JVW-ENTITY-LISTENER-GENERATED-SYNTAX",
            "Generated $fileName is invalid: ${error.errorDescription}.",
        )
    }

    private fun rejected(
        code: String,
        message: String,
        path: String? = null,
    ): EntityEventListenerProposal =
        EntityEventListenerProposal(null, listOf(WorkspaceChangeIssue(code, message, path)))

    private fun rejectedPreview(issues: List<WorkspaceChangeIssue>) =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "entity-listener:rejected",
            label = "Entity listener creation rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    private fun rejectedPrepared(issues: List<WorkspaceChangeIssue>) =
        PreparedWorkspaceChange(
            plan = WorkspaceChangePlan(
                accepted = false,
                changeSetId = "entity-listener:rejected",
                label = "Entity listener creation rejected",
                planDigest = null,
                files = emptyList(),
                issues = issues,
            ),
            baseDir = null,
        )

    companion object {
        private val JAVA_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val PACKAGE_NAME =
            Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
        private const val MAX_CLASS_NAME = 120
        private const val MAX_PACKAGE_NAME = 300

        fun getInstance(project: Project): EntityEventListenerService =
            project.getService(EntityEventListenerService::class.java)
    }
}

data class EntityEventListenerRequest(
    val entitySource: SourceLocator,
    val className: String,
    val packageName: String,
    val sourceLanguage: EntitySourceLanguage? = EntitySourceLanguage.JAVA,
    val events: List<EventListenerGenerator.ListenerEvent?>,
    val afterCommitRequiresNewTransaction: Boolean = false,
)

data class EntityEventListenerApplyRequest(
    val listener: EntityEventListenerRequest,
    val expectedPlanDigest: String,
)

data class EntityEventListenerProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)
