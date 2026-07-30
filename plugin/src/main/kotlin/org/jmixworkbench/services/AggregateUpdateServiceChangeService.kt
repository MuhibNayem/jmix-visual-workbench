package org.jmixworkbench.services

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.AggregateUpdateServiceGenerator
import org.jmixworkbench.generator.AggregateUpdateServiceModel
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.EntityType

/**
 * Creates a transaction-explicit aggregate update service and wires one exact
 * FlowUI DataContext to it in the same atomic, undoable workspace change.
 *
 * The JCEF request is untrusted: descriptor, controller, instance container,
 * entity source, module ownership and target source root are all re-derived
 * from current backend evidence. The client cannot choose a write path.
 */
@Service(Service.Level.PROJECT)
class AggregateUpdateServiceChangeService(
    private val project: Project,
) {
    fun preview(request: AggregateUpdateServiceRequest): WorkspaceChangePreviewResponse {
        val proposal = propose(request)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal)
    }

    fun prepareApply(request: AggregateUpdateServiceApplyRequest): PreparedWorkspaceChange {
        val change = request.change
            ?: return rejectedPrepared(
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-AGGREGATE-SERVICE-REQUEST-INVALID",
                        "Aggregate update-service request is required.",
                    ),
                ),
            )
        val expectedDigest = request.expectedPlanDigest
            ?.takeIf { SHA_256.matches(it) }
            ?: return rejectedPrepared(
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-AGGREGATE-SERVICE-DIGEST-INVALID",
                        "A valid preview digest is required before applying this aggregate service.",
                    ),
                ),
            )
        val proposal = propose(change)
        val changeSet = proposal.changeSet ?: return rejectedPrepared(proposal.issues)
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, expectedDigest),
        )
    }

    internal fun propose(request: AggregateUpdateServiceRequest): AggregateUpdateServiceProposal {
        val descriptorSource = request.descriptorSource
            ?: return rejected("JVW-AGGREGATE-SERVICE-DESCRIPTOR-MISSING", "View descriptor evidence is required.")
        val controllerSource = request.controllerSource
            ?: return rejected("JVW-AGGREGATE-SERVICE-CONTROLLER-MISSING", "View controller evidence is required.")
        val entitySource = request.entitySource
            ?: return rejected("JVW-AGGREGATE-SERVICE-ENTITY-MISSING", "Aggregate entity evidence is required.")
        if (!validLocator(descriptorSource)) {
            return rejected(
                "JVW-AGGREGATE-SERVICE-DESCRIPTOR-INVALID",
                "The view descriptor locator is malformed. Refresh the designer.",
            )
        }
        if (!validLocator(controllerSource)) {
            return rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-INVALID",
                "The view controller locator is malformed. Refresh the designer.",
            )
        }
        if (!validLocator(entitySource)) {
            return rejected(
                "JVW-AGGREGATE-SERVICE-ENTITY-INVALID",
                "The aggregate entity locator is malformed. Refresh the designer.",
            )
        }
        val containerId = request.containerId
            ?.takeIf { JAVA_IDENTIFIER.matches(it) && it.length <= MAX_IDENTIFIER_LENGTH }
            ?: return rejected(
                "JVW-AGGREGATE-SERVICE-CONTAINER-INVALID",
                "Select an instance data container with a valid id.",
                descriptorSource.relativePath,
            )
        val entityQualifiedName = request.entityQualifiedName
            ?.takeIf { QUALIFIED_NAME.matches(it) && it.length <= MAX_QUALIFIED_NAME_LENGTH }
            ?: return rejected(
                "JVW-AGGREGATE-SERVICE-ENTITY-INVALID",
                "The aggregate entity must have a valid qualified JVM name.",
                entitySource.relativePath,
            )

        val workspace = runCatching {
            FlowUiWorkspaceService.getInstance(project).load(FlowUiWorkspaceRequest(descriptorSource))
        }.getOrElse {
            return rejected(
                "JVW-AGGREGATE-SERVICE-WORKSPACE-UNAVAILABLE",
                "The exact FlowUI workspace could not be reconstructed. Refresh the designer.",
                descriptorSource.relativePath,
            )
        }
        if (!workspace.accepted) {
            return AggregateUpdateServiceProposal(null, workspace.issues)
        }
        val controllerModel = workspace.controllerModel
            ?: return rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-UNRESOLVED",
                "The selected view has no uniquely indexed Java or Kotlin controller.",
                descriptorSource.relativePath,
            )
        if (
            controllerModel.relativePath != controllerSource.relativePath ||
            controllerModel.revisionFingerprint != controllerSource.revisionFingerprint
        ) {
            return rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-STALE",
                "The view controller changed or no longer belongs to this descriptor. Refresh before generating.",
                controllerSource.relativePath,
            )
        }
        val container = workspace.dataModel?.containers?.singleOrNull {
            it.id == containerId &&
                it.kind.substringAfter(':').equals("instance", ignoreCase = true)
        } ?: return rejected(
            "JVW-AGGREGATE-SERVICE-CONTAINER-MISSING",
            "The selected instance container is missing or ambiguous in the current descriptor.",
            descriptorSource.relativePath,
        )
        if (
            container.entityClass != entityQualifiedName &&
            !entityQualifiedName.endsWith(".${container.entityClass.orEmpty()}")
        ) {
            return rejected(
                "JVW-AGGREGATE-SERVICE-CONTAINER-ENTITY-MISMATCH",
                "The instance container no longer binds the submitted aggregate entity.",
                descriptorSource.relativePath,
            )
        }

        val schema = SchemaWorkspaceService.getInstance(project).load()
        val entity = schema.entities.singleOrNull {
            it.sourceLocator == entitySource && it.qualifiedName == entityQualifiedName
        } ?: return rejected(
            "JVW-AGGREGATE-SERVICE-ENTITY-STALE",
            "The exact aggregate entity is missing, ambiguous, or changed. Refresh the designer.",
            entitySource.relativePath,
        )
        if (entity.entityType != EntityType.ENTITY || entity.databaseView) {
            return rejected(
                "JVW-AGGREGATE-SERVICE-ENTITY-UNSUPPORTED",
                "Transactional update services require a writable persistent JPA entity.",
                entity.sourceLocator.relativePath,
            )
        }
        aggregateBoundaryIssue(entity, schema.entities)?.let { return AggregateUpdateServiceProposal(null, listOf(it)) }

        val loadedController = loadController(controllerSource)
        val controllerFile = loadedController.psiFile
            ?: return AggregateUpdateServiceProposal(null, loadedController.issues)
        val sourceLanguage = when (controllerFile) {
            is PsiJavaFile -> EntitySourceLanguage.JAVA
            is KtFile -> EntitySourceLanguage.KOTLIN
            else -> return rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-LANGUAGE",
                "Only Java and Kotlin FlowUI controllers are supported.",
                controllerSource.relativePath,
            )
        }
        val controllerPackage = when (controllerFile) {
            is PsiJavaFile -> controllerFile.packageName
            is KtFile -> controllerFile.packageFqName.asString()
            else -> ""
        }.takeIf { QUALIFIED_NAME.matches(it) }
            ?: return rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-PACKAGE",
                "The controller must declare a valid package before a service can be generated.",
                controllerSource.relativePath,
            )
        val graph = ApplicationGraphService.getInstance(project).graph()
        val controllerArtifact = graph.artifacts.singleOrNull {
            it.kind == ArtifactKind.VIEW_CONTROLLER &&
                it.sourceLocator.relativePath == controllerSource.relativePath
        } ?: return rejected(
            "JVW-AGGREGATE-SERVICE-CONTROLLER-OWNERSHIP",
            "The controller module ownership is missing or ambiguous.",
            controllerSource.relativePath,
        )
        val destinations = ProjectSourceDestinationService.getInstance(project).let { service ->
            when (sourceLanguage) {
                EntitySourceLanguage.JAVA -> service.productionJava(graph)
                EntitySourceLanguage.KOTLIN -> service.productionKotlin(graph)
            }
        }.filter { it.moduleId == controllerArtifact.owner.moduleId }
        val destination = destinations
            .filter { rootContains(controllerSource.relativePath, it.sourceRoot) }
            .maxByOrNull { it.sourceRoot.length }
            ?: destinations.singleOrNull()
            ?: return rejected(
                "JVW-AGGREGATE-SERVICE-DESTINATION",
                "No unambiguous ${sourceLanguage.name.lowercase()} production source root is registered for " +
                    "controller module ${controllerArtifact.owner.moduleId}.",
                controllerSource.relativePath,
            )

        val servicePackage = servicePackage(controllerPackage)
        val serviceClassName = "${entity.className}UpdateService"
        val serviceQualifiedName = "$servicePackage.$serviceClassName"
        val servicePath = listOf(
            destination.sourceRoot.trimEnd('/', '\\'),
            servicePackage.replace('.', '/'),
            "$serviceClassName.${sourceLanguage.fileExtension}",
        ).joinToString("/")
        val platformDelegates = hasPlatformUpdateDelegates()
        if (platformDelegates) {
            val existingDelegates = existingPlatformUpdateDelegates(entity.qualifiedName)
                .filterNot { it.qualifiedName == serviceQualifiedName }
            if (existingDelegates.isNotEmpty()) {
                return rejected(
                    "JVW-AGGREGATE-SERVICE-DELEGATE-CONFLICT",
                    buildString {
                        append("An existing update delegate already owns ")
                        append(entity.qualifiedName)
                        append(": ")
                        append(
                            existingDelegates.joinToString { existing ->
                                "${existing.contract.substringAfterLast('.')}=${existing.qualifiedName}"
                            },
                        )
                        append(". Jmix permits at most one save/remove delegate per entity; preserve and wire the existing service.")
                    },
                    existingDelegates.first().relativePath,
                )
            }
        }
        val transactionManager = entity.storeName
            .takeUnless { it.isBlank() || it == "main" }
            ?.let { "$it${TRANSACTION_MANAGER_SUFFIX}" }
        val serviceContent = AggregateUpdateServiceGenerator.generate(
            AggregateUpdateServiceModel(
                className = serviceClassName,
                packageName = servicePackage,
                entityQualifiedName = entity.qualifiedName,
                sourceLanguage = sourceLanguage,
                transactionManagerBean = transactionManager,
                platformDelegates = platformDelegates,
            ),
        )
        syntaxIssue(servicePath, sourceLanguage, serviceContent)?.let {
            return AggregateUpdateServiceProposal(null, listOf(it))
        }

        val serviceChange = existingServiceChange(servicePath, serviceContent)
        if (serviceChange.issue != null) {
            return AggregateUpdateServiceProposal(null, listOf(serviceChange.issue))
        }
        val controllerChange = when (controllerFile) {
            is PsiJavaFile -> proposeJavaControllerWiring(
                loadedController = loadedController,
                psiFile = controllerFile,
                serviceQualifiedName = serviceQualifiedName,
            )
            is KtFile -> proposeKotlinControllerWiring(
                loadedController = loadedController,
                psiFile = controllerFile,
                serviceQualifiedName = serviceQualifiedName,
            )
            else -> ControllerWiringProposal(null, emptyList())
        }
        if (controllerChange.issues.isNotEmpty()) {
            return AggregateUpdateServiceProposal(null, controllerChange.issues)
        }
        val changes = listOfNotNull(serviceChange.change, controllerChange.change)
        if (changes.isEmpty()) {
            return AggregateUpdateServiceProposal(null, emptyList(), noChange = true)
        }
        val identity = buildString {
            append(descriptorSource.relativePath)
            append('\u0000').append(descriptorSource.revisionFingerprint)
            append('\u0000').append(controllerSource.relativePath)
            append('\u0000').append(controllerSource.revisionFingerprint)
            append('\u0000').append(entitySource.relativePath)
            append('\u0000').append(entitySource.revisionFingerprint)
            append('\u0000').append(containerId)
            append('\u0000').append(servicePath)
            append('\u0000').append(serviceContent)
        }
        return AggregateUpdateServiceProposal(
            changeSet = WorkspaceChangeSet(
                id = "aggregate-update-service:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Create and wire $serviceClassName",
                files = changes,
            ),
            issues = emptyList(),
            noChange = false,
        )
    }

    private fun loadController(locator: SourceLocator): LoadedAggregateController {
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath)
            ?: return LoadedAggregateController.rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-MISSING",
                "The indexed controller source no longer exists.",
                locator.relativePath,
            )
        val file = resolved.file
        if (
            file.isDirectory ||
            file.extension !in setOf("java", "kt") ||
            !VfsUtilCore.isAncestor(resolved.root, file, false)
        ) {
            return LoadedAggregateController.rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-PATH",
                "The controller must be Java or Kotlin source inside project content.",
                locator.relativePath,
            )
        }
        if (file.length > MAX_SOURCE_BYTES) {
            return LoadedAggregateController.rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-SIZE",
                "The controller exceeds the reviewed ${MAX_SOURCE_BYTES / (1024 * 1024)} MiB mutation limit.",
                locator.relativePath,
            )
        }
        val content = runCatching { ProjectSourceText.read(file) }.getOrElse {
            return LoadedAggregateController.rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-READ",
                "The current controller document cannot be read.",
                locator.relativePath,
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (fingerprint != locator.revisionFingerprint) {
            return LoadedAggregateController.rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-STALE",
                "The controller changed after the workspace opened. Refresh before generating.",
                locator.relativePath,
            )
        }
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return LoadedAggregateController.rejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-PSI",
                "IntelliJ could not build a live PSI model for the controller.",
                locator.relativePath,
            )
        return LoadedAggregateController(content, fingerprint, psiFile, emptyList())
    }

    private fun proposeJavaControllerWiring(
        loadedController: LoadedAggregateController,
        psiFile: PsiJavaFile,
        serviceQualifiedName: String,
    ): ControllerWiringProposal {
        val controllerClass = psiFile.classes.singleOrNull { candidate ->
            candidate.annotations.any { it.shortName() == "ViewController" }
        } ?: psiFile.classes.singleOrNull()
            ?: return controllerRejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-CLASS",
                "The Java controller class is missing or ambiguous.",
                psiFile.virtualFile?.path,
            )
        val serviceSimpleName = serviceQualifiedName.substringAfterLast('.')
        val fieldName = serviceSimpleName.replaceFirstChar(Char::lowercase)
        val imports = psiFile.importList?.allImportStatements
            ?.mapNotNull { it.importReference?.qualifiedName }
            .orEmpty()
        val conflictingImport = imports.firstOrNull {
            it.substringAfterLast('.') == serviceSimpleName && it != serviceQualifiedName
        }
        if (conflictingImport != null) {
            return controllerRejected(
                "JVW-AGGREGATE-SERVICE-IMPORT-COLLISION",
                "$conflictingImport already owns the simple name $serviceSimpleName.",
                psiFile.virtualFile?.path,
            )
        }
        val serviceFields = controllerClass.fields.filter { field ->
            field.type.canonicalText == serviceQualifiedName ||
                (
                    field.type.presentableText == serviceSimpleName &&
                        (
                            serviceQualifiedName in imports ||
                                psiFile.packageName == serviceQualifiedName.substringBeforeLast('.')
                            )
                    )
        }
        if (serviceFields.size > 1) {
            return controllerRejected(
                "JVW-AGGREGATE-SERVICE-INJECTION-AMBIGUOUS",
                "The controller has multiple $serviceSimpleName fields.",
                psiFile.virtualFile?.path,
            )
        }
        val existingField = serviceFields.singleOrNull()
        val conflictingField = controllerClass.fields.firstOrNull {
            it !== existingField && it.name == fieldName
        }
        if (conflictingField != null) {
            return controllerRejected(
                "JVW-AGGREGATE-SERVICE-FIELD-COLLISION",
                "A different controller field already uses '$fieldName'.",
                psiFile.virtualFile?.path,
            )
        }
        val effectiveFieldName = existingField?.name ?: fieldName
        val dataContextHandlers = controllerClass.methods.filter { method ->
            method.annotations.any { annotation ->
                annotation.shortName() == "Install" &&
                    annotation.findDeclaredAttributeValue("target")
                        ?.text
                        ?.substringAfterLast('.')
                        ?.trim() == "DATA_CONTEXT"
            }
        }
        if (dataContextHandlers.size > 1) {
            return controllerRejected(
                "JVW-AGGREGATE-SERVICE-DELEGATE-AMBIGUOUS",
                "The controller has multiple DataContext install handlers.",
                psiFile.virtualFile?.path,
            )
        }
        val existingHandler = dataContextHandlers.singleOrNull()
        if (existingHandler != null) {
            val equivalent = existingHandler.returnType?.presentableText?.contains("Set") == true &&
                existingHandler.parameterList.parameters.singleOrNull()
                    ?.type
                    ?.presentableText
                    ?.substringAfterLast('.') == "SaveContext" &&
                existingHandler.body?.text?.contains("$effectiveFieldName.saveChanges") == true
            return if (equivalent) {
                ControllerWiringProposal(null, emptyList())
            } else {
                controllerRejected(
                    "JVW-AGGREGATE-SERVICE-DELEGATE-CONFLICT",
                    "This controller already owns a different DataContext save delegate. " +
                        "Review it manually instead of silently replacing business logic.",
                    psiFile.virtualFile?.path,
                )
            }
        }
        val methodName = "${effectiveFieldName}SaveDelegate"
        if (controllerClass.findMethodsByName(methodName, false).isNotEmpty()) {
            return controllerRejected(
                "JVW-AGGREGATE-SERVICE-METHOD-COLLISION",
                "A controller method named '$methodName' already exists.",
                psiFile.virtualFile?.path,
            )
        }
        val requiredImports = linkedSetOf(
            serviceQualifiedName,
            "org.springframework.beans.factory.annotation.Autowired",
            "io.jmix.flowui.view.Install",
            "io.jmix.flowui.view.Target",
            "io.jmix.core.SaveContext",
            "java.util.Set",
        )
        val edits = mutableListOf<WorkspaceTextEdit>()
        addJavaImportEdit(psiFile, requiredImports)?.let(edits::add)
        val rBrace = controllerClass.rBrace
            ?: return controllerRejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-MALFORMED",
                "The Java controller closing brace is unavailable.",
                psiFile.virtualFile?.path,
            )
        val indent = memberIndent(loadedController.content, controllerClass)
        val insertion = buildString {
            if (existingField == null) {
                append("\n\n")
                append(indent).append("@Autowired\n")
                append(indent).append("private ").append(serviceSimpleName).append(' ')
                    .append(effectiveFieldName).append(';')
            }
            append("\n\n")
            append(indent).append("@Install(target = Target.DATA_CONTEXT)\n")
            append(indent).append("private Set<Object> ").append(methodName)
                .append("(final SaveContext saveContext) {\n")
            append(indent).append("    return ").append(effectiveFieldName)
                .append(".saveChanges(saveContext);\n")
            append(indent).append("}\n")
        }
        edits += WorkspaceTextEdit(
            startOffset = rBrace.textRange.startOffset,
            endOffset = rBrace.textRange.startOffset,
            expectedText = "",
            replacement = insertion,
        )
        val resulting = applyEdits(loadedController.content, edits)
        syntaxIssue(
            psiFile.name,
            EntitySourceLanguage.JAVA,
            resulting,
        )?.let { return ControllerWiringProposal(null, listOf(it)) }
        return ControllerWiringProposal(
            WorkspaceFileChange(
                relativePath = psiFile.virtualFile?.let {
                    ProjectFileResolver.getInstance(project).locatorPath(it)
                } ?: return controllerRejected(
                    "JVW-AGGREGATE-SERVICE-CONTROLLER-PATH",
                    "The Java controller project-relative path is unavailable.",
                ),
                mode = WorkspaceFileChangeMode.MODIFY,
                baseRevisionFingerprint = loadedController.fingerprint,
                edits = edits.sortedBy(WorkspaceTextEdit::startOffset),
            ),
            emptyList(),
        )
    }

    private fun proposeKotlinControllerWiring(
        loadedController: LoadedAggregateController,
        psiFile: KtFile,
        serviceQualifiedName: String,
    ): ControllerWiringProposal {
        val controllerClass = psiFile.declarations
            .filterIsInstance<KtClassOrObject>()
            .singleOrNull { it.annotationEntries.any { annotation -> annotation.shortName?.asString() == "ViewController" } }
            ?: psiFile.declarations.filterIsInstance<KtClassOrObject>().singleOrNull()
            ?: return controllerRejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-CLASS",
                "The Kotlin controller class is missing or ambiguous.",
                psiFile.virtualFile?.path,
            )
        val serviceSimpleName = serviceQualifiedName.substringAfterLast('.')
        val preferredFieldName = serviceSimpleName.replaceFirstChar(Char::lowercase)
        val imports = psiFile.importDirectives.mapNotNull { it.importedFqName?.asString() }.toSet()
        val serviceProperties = controllerClass.declarations.filterIsInstance<KtProperty>().filter { property ->
            val type = property.typeReference?.text?.removeSuffix("?")
            type == serviceQualifiedName ||
                (
                    type == serviceSimpleName &&
                        (
                            serviceQualifiedName in imports ||
                                psiFile.packageFqName.asString() == serviceQualifiedName.substringBeforeLast('.')
                            )
                    )
        }
        if (serviceProperties.size > 1) {
            return controllerRejected(
                "JVW-AGGREGATE-SERVICE-INJECTION-AMBIGUOUS",
                "The Kotlin controller has multiple $serviceSimpleName properties.",
                psiFile.virtualFile?.path,
            )
        }
        val existingProperty = serviceProperties.singleOrNull()
        val conflictingProperty = controllerClass.declarations.filterIsInstance<PsiNamedElement>()
            .firstOrNull { it !== existingProperty && it.name == preferredFieldName }
        if (conflictingProperty != null) {
            return controllerRejected(
                "JVW-AGGREGATE-SERVICE-FIELD-COLLISION",
                "A different Kotlin declaration already uses '$preferredFieldName'.",
                psiFile.virtualFile?.path,
            )
        }
        val fieldName = existingProperty?.name ?: preferredFieldName
        val dataContextHandlers = controllerClass.declarations.filterIsInstance<KtNamedFunction>()
            .filter { function ->
                function.annotationEntries.any { annotation ->
                    annotation.shortName?.asString() == "Install" &&
                        annotation.valueArgumentList?.text?.contains("Target.DATA_CONTEXT") == true
                }
            }
        if (dataContextHandlers.size > 1) {
            return controllerRejected(
                "JVW-AGGREGATE-SERVICE-DELEGATE-AMBIGUOUS",
                "The Kotlin controller has multiple DataContext install handlers.",
                psiFile.virtualFile?.path,
            )
        }
        val existingHandler = dataContextHandlers.singleOrNull()
        if (existingHandler != null) {
            val equivalent = existingHandler.valueParameters.singleOrNull()
                ?.typeReference
                ?.text
                ?.substringAfterLast('.') == "SaveContext" &&
                existingHandler.text.contains("$fieldName.saveChanges")
            return if (equivalent) {
                ControllerWiringProposal(null, emptyList())
            } else {
                controllerRejected(
                    "JVW-AGGREGATE-SERVICE-DELEGATE-CONFLICT",
                    "This Kotlin controller already owns a different DataContext save delegate.",
                    psiFile.virtualFile?.path,
                )
            }
        }
        val methodName = "${fieldName}SaveDelegate"
        if (controllerClass.declarations.filterIsInstance<KtNamedFunction>().any { it.name == methodName }) {
            return controllerRejected(
                "JVW-AGGREGATE-SERVICE-METHOD-COLLISION",
                "A Kotlin function named '$methodName' already exists.",
                psiFile.virtualFile?.path,
            )
        }
        val body = controllerClass.body
            ?: return controllerRejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-MALFORMED",
                "The Kotlin controller must have an explicit class body.",
                psiFile.virtualFile?.path,
            )
        val rBrace = body.rBrace
            ?: return controllerRejected(
                "JVW-AGGREGATE-SERVICE-CONTROLLER-MALFORMED",
                "The Kotlin controller closing brace is unavailable.",
                psiFile.virtualFile?.path,
            )
        val indent = kotlinMemberIndent(loadedController.content, controllerClass)
        val insertion = buildString {
            if (existingProperty == null) {
                append("\n\n")
                append(
                    """
                    @org.springframework.beans.factory.annotation.Autowired
                    private lateinit var $fieldName: $serviceQualifiedName
                    """.trimIndent().prependIndent(indent),
                )
            }
            append("\n\n")
            append(
                """
                @io.jmix.flowui.view.Install(target = io.jmix.flowui.view.Target.DATA_CONTEXT)
                private fun $methodName(
                    saveContext: io.jmix.core.SaveContext,
                ): Set<Any> = $fieldName.saveChanges(saveContext)
                """.trimIndent().prependIndent(indent),
            )
            append('\n')
        }
        val edit = WorkspaceTextEdit(
            startOffset = rBrace.textRange.startOffset,
            endOffset = rBrace.textRange.startOffset,
            expectedText = "",
            replacement = insertion,
        )
        val resulting = loadedController.content.replaceRange(
            edit.startOffset,
            edit.endOffset,
            edit.replacement,
        )
        syntaxIssue(psiFile.name, EntitySourceLanguage.KOTLIN, resulting)?.let {
            return ControllerWiringProposal(null, listOf(it))
        }
        val relativePath = psiFile.virtualFile?.let {
            ProjectFileResolver.getInstance(project).locatorPath(it)
        } ?: return controllerRejected(
            "JVW-AGGREGATE-SERVICE-CONTROLLER-PATH",
            "The Kotlin controller project-relative path is unavailable.",
        )
        return ControllerWiringProposal(
            WorkspaceFileChange(
                relativePath = relativePath,
                mode = WorkspaceFileChangeMode.MODIFY,
                baseRevisionFingerprint = loadedController.fingerprint,
                edits = listOf(edit),
            ),
            emptyList(),
        )
    }

    private fun existingServiceChange(
        relativePath: String,
        generatedContent: String,
    ): ExistingServiceProposal {
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(relativePath)
            ?: return ExistingServiceProposal(
                WorkspaceFileChange(
                    relativePath = relativePath,
                    mode = WorkspaceFileChangeMode.CREATE,
                    baseRevisionFingerprint = null,
                    createContent = generatedContent,
                ),
                null,
            )
        val file = resolved.file
        if (file.isDirectory || !VfsUtilCore.isAncestor(resolved.root, file, false)) {
            return ExistingServiceProposal(
                null,
                WorkspaceChangeIssue(
                    "JVW-AGGREGATE-SERVICE-TARGET-INVALID",
                    "The derived update-service target is not a writable project source file.",
                    relativePath,
                ),
            )
        }
        val current = runCatching { ProjectSourceText.read(file) }.getOrNull()
            ?: return ExistingServiceProposal(
                null,
                WorkspaceChangeIssue(
                    "JVW-AGGREGATE-SERVICE-TARGET-UNREADABLE",
                    "The existing update-service target cannot be read.",
                    relativePath,
                ),
            )
        return if (current == generatedContent) {
            ExistingServiceProposal(null, null)
        } else {
            ExistingServiceProposal(
                null,
                WorkspaceChangeIssue(
                    "JVW-AGGREGATE-SERVICE-TARGET-CONFLICT",
                    "A different source file already exists at $relativePath. " +
                        "It is preserved; choose or adapt the existing service manually.",
                    relativePath,
                ),
            )
        }
    }

    private fun hasPlatformUpdateDelegates(): Boolean {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = ProjectScope.getAllScope(project)
        return facade.findClass("io.jmix.core.SaveDelegate", scope) != null &&
            facade.findClass("io.jmix.core.RemoveDelegate", scope) != null
    }

    private fun existingPlatformUpdateDelegates(
        entityQualifiedName: String,
    ): List<ExistingPlatformUpdateDelegate> {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = ProjectScope.getProjectScope(project)
        return PLATFORM_UPDATE_DELEGATES.flatMap { contract ->
            val delegate = facade.findClass(contract, ProjectScope.getAllScope(project))
                ?: return@flatMap emptyList()
            val parameter = delegate.typeParameters.singleOrNull()
                ?: return@flatMap emptyList()
            ClassInheritorsSearch.search(delegate, scope, true)
                .findAll()
                .mapNotNull { implementation ->
                    if (implementation.hasModifierProperty("abstract")) return@mapNotNull null
                    val substitutor = TypeConversionUtil.getSuperClassSubstitutor(
                        delegate,
                        implementation,
                        PsiSubstitutor.EMPTY,
                    )
                    val entityType = substitutor.substitute(parameter)?.canonicalText
                    if (entityType != entityQualifiedName) return@mapNotNull null
                    val qualifiedName = implementation.qualifiedName ?: return@mapNotNull null
                    val relativePath = implementation.containingFile?.virtualFile?.let { file ->
                        runCatching {
                            ProjectFileResolver.getInstance(project).locatorPath(file)
                        }.getOrNull()
                    } ?: return@mapNotNull null
                    ExistingPlatformUpdateDelegate(contract, qualifiedName, relativePath)
                }
        }.distinct()
    }

    /**
     * A transaction manager can cover only one Jmix data store. Walk the whole
     * composition graph (including inherited mappings), not merely the root's
     * first-level properties, and deny generation whenever that boundary is
     * crossed or cannot be proven from the current entity inventory.
     */
    private fun aggregateBoundaryIssue(
        root: SchemaEntitySnapshot,
        entities: List<SchemaEntitySnapshot>,
    ): WorkspaceChangeIssue? {
        val byQualifiedName = entities.associateBy(SchemaEntitySnapshot::qualifiedName)
        val bySimpleName = entities.groupBy(SchemaEntitySnapshot::className)
        val pending = ArrayDeque<Pair<SchemaEntitySnapshot, String>>()
        val visited = linkedSetOf<String>()
        pending += root to root.className
        while (pending.isNotEmpty()) {
            val (current, path) = pending.removeFirst()
            if (!visited.add(current.qualifiedName)) continue
            val attributes = current.attributes + current.inheritedAttributes.map { it.attribute }
            attributes.forEach { attribute ->
                val association = attribute.associationDetails
                    ?.takeIf { it.composition }
                    ?: return@forEach
                val compositionPath = "$path.${attribute.name}"
                if (association.crossDataStore) {
                    return WorkspaceChangeIssue(
                        "JVW-AGGREGATE-SERVICE-CROSS-STORE",
                        "Composition '$compositionPath' is cross-store. A local @Transactional service cannot " +
                            "guarantee distributed atomicity; use an explicit saga/outbox design.",
                        current.sourceLocator.relativePath,
                    )
                }
                val target = byQualifiedName[association.relatedEntity]
                    ?: bySimpleName[association.relatedEntity.substringAfterLast('.')]
                        ?.singleOrNull()
                    ?: return WorkspaceChangeIssue(
                        "JVW-AGGREGATE-SERVICE-COMPOSITION-UNPROVEN",
                        "Composition '$compositionPath' does not resolve to one indexed entity. " +
                            "The aggregate transaction boundary cannot be proven.",
                        current.sourceLocator.relativePath,
                    )
                if (target.storeName != root.storeName) {
                    return WorkspaceChangeIssue(
                        "JVW-AGGREGATE-SERVICE-CROSS-STORE",
                        "Composition '$compositionPath' resolves to store '${target.storeName}', while the aggregate " +
                            "root uses '${root.storeName}'. Use an explicit saga/outbox design.",
                        current.sourceLocator.relativePath,
                    )
                }
                pending += target to compositionPath
            }
        }
        return null
    }

    /**
     * Gson can instantiate Kotlin data classes without invoking their init
     * blocks. Treat every locator crossing the JCEF boundary as raw input and
     * re-establish the SourceLocator invariants before reading any field.
     */
    private fun validLocator(locator: SourceLocator): Boolean = runCatching {
        val path = locator.relativePath
        val revision = locator.revisionFingerprint
        path.isNotBlank() &&
            path.length <= MAX_RELATIVE_PATH_LENGTH &&
            !path.startsWith('/') &&
            !WINDOWS_ABSOLUTE_PATH.containsMatchIn(path) &&
            '\\' !in path &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
            SHA_256.matches(revision)
    }.getOrDefault(false)

    private fun syntaxIssue(
        fileName: String,
        sourceLanguage: EntitySourceLanguage,
        content: String,
    ): WorkspaceChangeIssue? {
        val fileType = when (sourceLanguage) {
            EntitySourceLanguage.JAVA -> JavaFileType.INSTANCE
            EntitySourceLanguage.KOTLIN -> FileTypeManager.getInstance().getFileTypeByExtension("kt")
        }
        val psi = PsiFileFactory.getInstance(project).createFileFromText(
            fileName.substringAfterLast('/'),
            fileType,
            content,
        )
        val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java) ?: return null
        return WorkspaceChangeIssue(
            "JVW-AGGREGATE-SERVICE-GENERATED-SYNTAX",
            "Generated ${fileName.substringAfterLast('/')} is invalid: ${error.errorDescription}.",
            fileName,
        )
    }

    private fun addJavaImportEdit(
        psiFile: PsiJavaFile,
        requiredImports: Set<String>,
    ): WorkspaceTextEdit? {
        val importList = psiFile.importList ?: return null
        val existing = importList.allImportStatements.mapNotNull { it.importReference?.qualifiedName }.toSet()
        val missing = requiredImports.filterNot(existing::contains).sorted()
        if (missing.isEmpty()) return null
        val statements = importList.allImportStatements
        val offset = statements.lastOrNull()?.textRange?.endOffset
            ?: psiFile.packageStatement?.textRange?.endOffset
            ?: 0
        val text = missing.joinToString("\n") { "import $it;" }
        val replacement = when {
            statements.isNotEmpty() -> "\n$text"
            offset > 0 -> "\n\n$text"
            else -> "$text\n\n"
        }
        return WorkspaceTextEdit(offset, offset, "", replacement)
    }

    private fun applyEdits(source: String, edits: List<WorkspaceTextEdit>): String =
        edits.sortedByDescending(WorkspaceTextEdit::startOffset).fold(source) { current, edit ->
            current.replaceRange(edit.startOffset, edit.endOffset, edit.replacement)
        }

    private fun memberIndent(source: String, psiClass: PsiClass): String {
        val member = (psiClass.fields.asList() + psiClass.methods.asList())
            .minByOrNull { it.textRange.startOffset }
            ?: return "    "
        val lineStart = source.lastIndexOf('\n', member.textRange.startOffset - 1) + 1
        return source.substring(lineStart, member.textRange.startOffset).takeWhile(Char::isWhitespace)
            .ifEmpty { "    " }
    }

    private fun kotlinMemberIndent(source: String, declaration: KtClassOrObject): String {
        val member = declaration.declarations.minByOrNull { it.textRange.startOffset } ?: return "    "
        val lineStart = source.lastIndexOf('\n', member.textRange.startOffset - 1) + 1
        return source.substring(lineStart, member.textRange.startOffset).takeWhile(Char::isWhitespace)
            .ifEmpty { "    " }
    }

    private fun PsiAnnotation.shortName(): String? =
        nameReferenceElement?.referenceName ?: qualifiedName?.substringAfterLast('.')

    private fun servicePackage(controllerPackage: String): String {
        val marker = controllerPackage.indexOf(".view.")
        val base = when {
            marker >= 0 -> controllerPackage.substring(0, marker)
            controllerPackage.endsWith(".view") -> controllerPackage.removeSuffix(".view")
            else -> controllerPackage
        }
        return "$base.service"
    }

    private fun rootContains(relativePath: String, sourceRoot: String): Boolean {
        val path = relativePath.replace('\\', '/').trim('/')
        val root = sourceRoot.replace('\\', '/').trim('/')
        return path == root || path.startsWith("$root/")
    }

    private fun rejected(
        code: String,
        message: String,
        path: String? = null,
    ): AggregateUpdateServiceProposal =
        AggregateUpdateServiceProposal(
            null,
            listOf(WorkspaceChangeIssue(code, message, path)),
        )

    private fun controllerRejected(
        code: String,
        message: String,
        path: String? = null,
    ): ControllerWiringProposal =
        ControllerWiringProposal(null, listOf(WorkspaceChangeIssue(code, message, path)))

    private fun rejectedPreview(proposal: AggregateUpdateServiceProposal): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = proposal.noChange,
            changeSetId = if (proposal.noChange) {
                "aggregate-update-service:no-change"
            } else {
                "aggregate-update-service:rejected"
            },
            label = if (proposal.noChange) {
                "Aggregate update service already wired"
            } else {
                "Aggregate update service rejected"
            },
            planDigest = null,
            files = emptyList(),
            issues = proposal.issues,
        )

    private fun rejectedPrepared(issues: List<WorkspaceChangeIssue>): PreparedWorkspaceChange =
        PreparedWorkspaceChange(
            plan = WorkspaceChangePlan(
                accepted = false,
                changeSetId = "aggregate-update-service:rejected",
                label = "Aggregate update service rejected",
                planDigest = null,
                files = emptyList(),
                issues = issues,
            ),
            baseDir = null,
        )

    companion object {
        private const val MAX_SOURCE_BYTES = 2L * 1024 * 1024
        private const val MAX_IDENTIFIER_LENGTH = 160
        private const val MAX_QUALIFIED_NAME_LENGTH = 512
        private const val MAX_RELATIVE_PATH_LENGTH = 4_096
        private const val TRANSACTION_MANAGER_SUFFIX = "TransactionManager"
        private val JAVA_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val QUALIFIED_NAME =
            Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
        private val SHA_256 = Regex("[a-fA-F0-9]{64}")
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:")
        private val PLATFORM_UPDATE_DELEGATES = listOf(
            "io.jmix.core.SaveDelegate",
            "io.jmix.core.RemoveDelegate",
        )

        fun getInstance(project: Project): AggregateUpdateServiceChangeService =
            project.getService(AggregateUpdateServiceChangeService::class.java)
    }
}

data class AggregateUpdateServiceRequest(
    val descriptorSource: SourceLocator?,
    val controllerSource: SourceLocator?,
    val entitySource: SourceLocator?,
    val containerId: String?,
    val entityQualifiedName: String?,
)

data class AggregateUpdateServiceApplyRequest(
    val change: AggregateUpdateServiceRequest?,
    val expectedPlanDigest: String?,
)

data class AggregateUpdateServiceProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
    val noChange: Boolean = false,
)

private data class LoadedAggregateController(
    val content: String,
    val fingerprint: String,
    val psiFile: com.intellij.psi.PsiFile?,
    val issues: List<WorkspaceChangeIssue>,
) {
    companion object {
        fun rejected(code: String, message: String, path: String) =
            LoadedAggregateController(
                "",
                "",
                null,
                listOf(WorkspaceChangeIssue(code, message, path)),
            )
    }
}

private data class ControllerWiringProposal(
    val change: WorkspaceFileChange?,
    val issues: List<WorkspaceChangeIssue>,
)

private data class ExistingServiceProposal(
    val change: WorkspaceFileChange?,
    val issue: WorkspaceChangeIssue?,
)

private data class ExistingPlatformUpdateDelegate(
    val contract: String,
    val qualifiedName: String,
    val relativePath: String,
)
