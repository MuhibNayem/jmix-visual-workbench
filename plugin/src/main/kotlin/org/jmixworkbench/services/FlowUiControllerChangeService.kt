package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.navigation.SourceNavigationPolicy
import org.jmixworkbench.model.RepositoryMethod
import javax.lang.model.SourceVersion

@Service(Service.Level.PROJECT)
class FlowUiControllerChangeService(
    private val project: Project,
) {
    fun previewInjection(request: FlowUiControllerInjectionRequest): WorkspaceChangePreviewResponse {
        val proposal = proposeInjection(request)
        if (!proposal.accepted) return proposal.preview("flowui-controller-injection", "Controller injection")
        val changeSet = proposal.changeSet
            ?: return proposal.preview("flowui-controller-injection", "Controller injection")
        return WorkspaceChangeService.getInstance(project).preview(changeSet)
    }

    fun prepareInjection(request: FlowUiControllerInjectionApplyRequest): PreparedWorkspaceChange {
        val proposal = proposeInjection(request.change)
        val changeSet = proposal.changeSet
        if (!proposal.accepted || changeSet == null) {
            return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "flowui-controller-injection:rejected",
                    label = "Controller injection rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = proposal.issues.ifEmpty {
                        listOf(
                            WorkspaceChangeIssue(
                                code = "JVW-CONTROLLER-NO-CHANGE",
                                message = "The component is already injected into this controller.",
                                relativePath = request.change.controllerLocator.relativePath,
                            ),
                        )
                    },
                ),
                baseDir = null,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    fun previewHandler(request: FlowUiControllerHandlerRequest): WorkspaceChangePreviewResponse {
        val proposal = proposeHandler(request)
        if (!proposal.accepted) return proposal.preview("flowui-controller-handler", "Controller handler")
        val changeSet = proposal.changeSet
            ?: return proposal.preview("flowui-controller-handler", "Controller handler")
        return WorkspaceChangeService.getInstance(project).preview(changeSet)
    }

    fun prepareHandler(request: FlowUiControllerHandlerApplyRequest): PreparedWorkspaceChange {
        val proposal = proposeHandler(request.change)
        val changeSet = proposal.changeSet
        if (!proposal.accepted || changeSet == null) {
            return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "flowui-controller-handler:rejected",
                    label = "Controller handler rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = proposal.issues.ifEmpty {
                        listOf(
                            WorkspaceChangeIssue(
                                code = "JVW-CONTROLLER-NO-CHANGE",
                                message = "An equivalent handler already exists in this controller.",
                                relativePath = request.change.controllerLocator.relativePath,
                            ),
                        )
                    },
                ),
                baseDir = null,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    private fun proposeInjection(request: FlowUiControllerInjectionRequest): ControllerChangeProposal {
        if (!SourceVersion.isIdentifier(request.componentId) || SourceVersion.isKeyword(request.componentId)) {
            return rejected(
                "JVW-CONTROLLER-FIELD-NAME-INVALID",
                "The component id must also be a valid non-keyword Java field name.",
                request.controllerLocator.relativePath,
            )
        }
        val loaded = loadController(request.controllerLocator)
        val psiFile = loaded.psiFile
            ?: return ControllerChangeProposal(false, null, loaded.issues)
        val controllerClass = loaded.controllerClass
            ?: return ControllerChangeProposal(false, null, loaded.issues)
        val sameNameField = controllerClass.fields.firstOrNull { it.name == request.componentId }
        if (sameNameField != null) {
            if (sameNameField.annotations.any { it.shortName() == "ViewComponent" }) {
                return ControllerChangeProposal(true, null, emptyList())
            }
            return rejected(
                "JVW-CONTROLLER-FIELD-CONFLICT",
                "A non-@ViewComponent field named '${request.componentId}' already exists.",
                request.controllerLocator.relativePath,
            )
        }
        if (controllerClass.fields.any { field ->
                field.annotations.any { annotation ->
                    annotation.shortName() == "ViewComponent" &&
                        annotation.stringAttribute("value") == request.componentId
                }
            }
        ) {
            return ControllerChangeProposal(true, null, emptyList())
        }

        val fieldType = controllerFieldType(request.componentTag, request.entityClass)
        val requiredImports = linkedSetOf(VIEW_COMPONENT_IMPORT).apply {
            addAll(fieldType.imports)
        }.filterNot { psiFile.hasImport(it) }
        val edits = mutableListOf<WorkspaceTextEdit>()
        if (requiredImports.isNotEmpty()) {
            val importList = psiFile.importList
                ?: return rejected(
                    "JVW-CONTROLLER-IMPORT-LIST-MISSING",
                    "IntelliJ could not locate the Java import list.",
                    request.controllerLocator.relativePath,
                )
            val existingImports = importList.allImportStatements
            val importOffset = existingImports.lastOrNull()?.textRange?.endOffset
                ?: psiFile.packageStatement?.textRange?.endOffset
                ?: 0
            val importText = requiredImports.sorted().joinToString("\n") { "import $it;" }
            val replacement = when {
                existingImports.isNotEmpty() -> "\n$importText"
                importOffset > 0 -> "\n\n$importText"
                else -> "$importText\n\n"
            }
            edits += WorkspaceTextEdit(
                startOffset = importOffset,
                endOffset = importOffset,
                expectedText = "",
                replacement = replacement,
            )
        }

        val memberAnchor = controllerClass.fields.lastOrNull()?.textRange?.endOffset
            ?: controllerClass.lBrace?.textRange?.endOffset
            ?: return rejected(
                "JVW-CONTROLLER-CLASS-MALFORMED",
                "The controller class body cannot be edited safely.",
                request.controllerLocator.relativePath,
            )
        val indent = memberIndent(loaded.content, controllerClass)
        val fieldText = buildString {
            append("\n\n")
            append(indent).append("@ViewComponent\n")
            append(indent).append("private ").append(fieldType.sourceType).append(' ')
                .append(request.componentId).append(';')
        }
        edits += WorkspaceTextEdit(
            startOffset = memberAnchor,
            endOffset = memberAnchor,
            expectedText = "",
            replacement = fieldText,
        )
        val identity = "${request.controllerLocator.relativePath}\u0000${request.componentId}\u0000${request.componentTag}\u0000${request.entityClass}"
        return ControllerChangeProposal(
            accepted = true,
            changeSet = WorkspaceChangeSet(
                id = "flowui-controller-injection:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Inject ${request.componentId} into ${controllerClass.name}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.controllerLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = loaded.fingerprint,
                        edits = edits.sortedBy(WorkspaceTextEdit::startOffset),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun proposeHandler(request: FlowUiControllerHandlerRequest): ControllerChangeProposal {
        when (request.kind) {
            FlowUiControllerHandlerKind.BUTTON_CLICK -> {
                if (request.componentId.isNullOrBlank() || request.componentTag?.substringAfter(':') != "button") {
                    return rejected(
                        "JVW-CONTROLLER-HANDLER-TARGET-INVALID",
                        "A button click handler requires a selected button with an id.",
                        request.controllerLocator.relativePath,
                    )
                }
            }
            FlowUiControllerHandlerKind.COMPONENT_TYPED_VALUE_CHANGE,
            FlowUiControllerHandlerKind.COMPONENT_VALUE_CHANGE,
            FlowUiControllerHandlerKind.COMPONENT_VALIDATOR -> {
                if (request.componentId.isNullOrBlank()) {
                    return rejected(
                        "JVW-CONTROLLER-HANDLER-TARGET-INVALID",
                        "This handler requires a selected component with an id.",
                        request.controllerLocator.relativePath,
                    )
                }
            }
            FlowUiControllerHandlerKind.ACTION_PERFORMED -> {
                if (request.targetId.isNullOrBlank()) {
                    return rejected(
                        "JVW-CONTROLLER-HANDLER-TARGET-INVALID",
                        "An action handler requires the connected action id.",
                        request.controllerLocator.relativePath,
                    )
                }
            }
            FlowUiControllerHandlerKind.COLLECTION_LOADER_PRE_LOAD,
            FlowUiControllerHandlerKind.COLLECTION_LOADER_POST_LOAD,
            FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE -> {
                if (request.componentId.isNullOrBlank() || request.componentTag?.substringAfter(':') != "loader") {
                    return rejected(
                        "JVW-CONTROLLER-HANDLER-TARGET-INVALID",
                        "A collection loader handler requires a selected loader with an id.",
                        request.controllerLocator.relativePath,
                    )
                }
                if (request.entityClass?.matches(JAVA_QUALIFIED_NAME) != true) {
                    return rejected(
                        "JVW-CONTROLLER-HANDLER-ENTITY-MISSING",
                        "The collection loader must resolve to a valid entity class before generating a typed handler.",
                        request.controllerLocator.relativePath,
                    )
                }
            }
            FlowUiControllerHandlerKind.DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE -> {
                if (request.componentId.isNullOrBlank() ||
                    request.componentTag?.substringAfter(':') != "instance"
                ) {
                    return rejected(
                        "JVW-CONTROLLER-HANDLER-TARGET-INVALID",
                        "A repository save delegate requires a selected instance data container with an id.",
                        request.controllerLocator.relativePath,
                    )
                }
                if (request.entityClass?.matches(JAVA_QUALIFIED_NAME) != true) {
                    return rejected(
                        "JVW-CONTROLLER-HANDLER-ENTITY-MISSING",
                        "The instance container must resolve to a valid entity class.",
                        request.controllerLocator.relativePath,
                    )
                }
            }
            else -> Unit
        }
        if (request.controllerLocator.relativePath.endsWith(".kt", ignoreCase = true)) {
            return if (request.kind in REPOSITORY_HANDLER_KINDS) {
                proposeKotlinRepositoryHandler(request)
            } else {
                rejected(
                    "JVW-CONTROLLER-KOTLIN-HANDLER-NOT-CERTIFIED",
                    "This Kotlin controller handler is not yet certified for source-safe generation.",
                    request.controllerLocator.relativePath,
                )
            }
        }
        val loaded = loadController(request.controllerLocator)
        val psiFile = loaded.psiFile
            ?: return ControllerChangeProposal(false, null, loaded.issues)
        val controllerClass = loaded.controllerClass
            ?: return ControllerChangeProposal(false, null, loaded.issues)
        val repositoryBinding = if (request.kind in REPOSITORY_HANDLER_KINDS) {
            val repository = loadRepositoryBinding(request, controllerClass)
            repository.binding
                ?: return ControllerChangeProposal(false, null, repository.issues)
        } else {
            null
        }
        val subscribeSubjectSupported = if (request.kind == FlowUiControllerHandlerKind.BUTTON_CLICK) {
            subscribeSubjectSupported()
                ?: return rejected(
                    "JVW-CONTROLLER-JMIX-API-UNRESOLVED",
                    "The Jmix @Subscribe API is not indexed. Complete the Gradle sync before generating a version-specific button handler.",
                    request.controllerLocator.relativePath,
                )
        } else {
            false
        }
        val handler = handlerTemplate(request, subscribeSubjectSupported, repositoryBinding)
        if (!SourceVersion.isIdentifier(handler.methodName) || SourceVersion.isKeyword(handler.methodName)) {
            return rejected(
                "JVW-CONTROLLER-METHOD-NAME-INVALID",
                "The generated handler method name is not a valid Java identifier.",
                request.controllerLocator.relativePath,
            )
        }
        val equivalent = controllerClass.methods.any { method ->
            val annotation = method.annotations.firstOrNull { it.shortName() == handler.annotationKind }
                ?: return@any false
            val target = when (handler.annotationKind) {
                "Install", "Supply" -> annotation.stringAttribute("to")
                else -> annotation.stringAttribute("id") ?: annotation.stringAttribute("value")
            }
            val subject = annotation.stringAttribute("subject")
            val targetScope = annotation.findDeclaredAttributeValue("target")?.text?.trim()
            target == handler.target &&
                (subject == handler.subject || (handler.allowMissingSubject && subject == null)) &&
                targetScopeMatches(targetScope, handler.targetScope) &&
                (handler.eventSimpleName == null || method.parameterList.parameters.any {
                    it.type.canonicalText.endsWith(".${handler.eventSimpleName}") ||
                        it.type.canonicalText == handler.eventSimpleName ||
                        it.type.canonicalText.contains("${handler.eventSimpleName}<")
                })
        }
        if (equivalent) return ControllerChangeProposal(true, null, emptyList())
        if (controllerClass.findMethodsByName(handler.methodName, false).isNotEmpty()) {
            return rejected(
                "JVW-CONTROLLER-METHOD-CONFLICT",
                "A method named '${handler.methodName}' already exists but is not the requested handler.",
                request.controllerLocator.relativePath,
            )
        }
        importConflict(psiFile, handler.imports)?.let { (simpleName, existingImport) ->
            return rejected(
                "JVW-CONTROLLER-IMPORT-COLLISION",
                "Cannot import '${handler.imports.first { it.substringAfterLast('.') == simpleName }}' because " +
                    "'$existingImport' already owns the simple name '$simpleName'.",
                request.controllerLocator.relativePath,
            )
        }

        val edits = mutableListOf<WorkspaceTextEdit>()
        addImportEdit(
            psiFile = psiFile,
            requiredImports = handler.imports,
        )?.let(edits::add)
        val rBrace = controllerClass.rBrace
            ?: return rejected(
                "JVW-CONTROLLER-CLASS-MALFORMED",
                "The controller class body cannot be edited safely.",
                request.controllerLocator.relativePath,
            )
        val indent = memberIndent(loaded.content, controllerClass)
        val methodText = buildString {
            repositoryBinding?.fieldText?.let { field ->
                append("\n\n")
                append(field.prependIndent(indent))
            }
            append("\n\n")
            append(indent).append(handler.annotation).append('\n')
            append(indent).append(handler.visibility).append(' ').append(handler.returnType).append(' ')
                .append(handler.methodName).append('(').append(handler.parameters).append(") {\n")
            handler.bodyLines.forEach { line ->
                append(indent).append("    ").append(line).append('\n')
            }
            append(indent).append("}\n")
        }
        edits += WorkspaceTextEdit(
            startOffset = rBrace.textRange.startOffset,
            endOffset = rBrace.textRange.startOffset,
            expectedText = "",
            replacement = methodText,
        )
        val identity = buildString {
            append(request.controllerLocator.relativePath)
            append('\u0000').append(request.kind)
            append('\u0000').append(request.componentId)
            append('\u0000').append(request.targetId)
            append('\u0000').append(request.entityClass)
            append('\u0000').append(request.repositoryQualifiedName)
            append('\u0000').append(request.repositoryLocator?.revisionFingerprint)
        }
        return ControllerChangeProposal(
            accepted = true,
            changeSet = WorkspaceChangeSet(
                id = "flowui-controller-handler:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Add ${handler.methodName} to ${controllerClass.name}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.controllerLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = loaded.fingerprint,
                        edits = edits.sortedBy(WorkspaceTextEdit::startOffset),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun proposeKotlinRepositoryHandler(
        request: FlowUiControllerHandlerRequest,
    ): ControllerChangeProposal {
        val loaded = loadKotlinController(request.controllerLocator)
        val psiClass = loaded.psiClass
            ?: return ControllerChangeProposal(false, null, loaded.issues)
        val syntheticController = JavaPsiFacade.getElementFactory(project)
            .createClass("__JmixKotlinRepositoryValidation")
        val repositoryValidation = loadRepositoryBinding(request, syntheticController)
        val repository = repositoryValidation.binding
            ?: return ControllerChangeProposal(false, null, repositoryValidation.issues)
        val qualifiedRepository = request.repositoryQualifiedName.orEmpty()
        val repositorySimpleName = qualifiedRepository.substringAfterLast('.')
        val source = loaded.content
        val imports = IMPORT_DECLARATION.findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        val controllerPackage = PACKAGE_DECLARATION.find(source)?.groupValues?.get(1).orEmpty()
        val injectionCandidates = descendants(psiClass)
            .filterIsInstance<PsiNamedElement>()
            .filter { element ->
                element.javaClass.simpleName in setOf("KtProperty", "KtParameter") &&
                    nearestKotlinClass(element) === psiClass &&
                    kotlinDeclaredType(element.text)?.let { type ->
                        type == qualifiedRepository ||
                            (
                                type == repositorySimpleName &&
                                    (
                                        qualifiedRepository in imports ||
                                            controllerPackage == qualifiedRepository.substringBeforeLast('.', "")
                                        )
                                )
                    } == true
            }
            .toList()
        if (injectionCandidates.size > 1) {
            return rejected(
                "JVW-CONTROLLER-REPOSITORY-INJECTION-AMBIGUOUS",
                "The Kotlin controller has multiple $repositorySimpleName injection candidates.",
                request.controllerLocator.relativePath,
            )
        }
        val preferredName = repositorySimpleName.replaceFirstChar(Char::lowercase)
        val existingInjection = injectionCandidates.singleOrNull()
        val fieldName = existingInjection?.name ?: preferredName
        val conflictingName = descendants(psiClass)
            .filterIsInstance<PsiNamedElement>()
            .firstOrNull { element ->
                element !== existingInjection &&
                    element.name == preferredName &&
                    element.javaClass.simpleName in setOf("KtProperty", "KtParameter")
            }
        if (conflictingName != null) {
            return rejected(
                "JVW-CONTROLLER-REPOSITORY-FIELD-CONFLICT",
                "A different Kotlin declaration already uses the name '$preferredName'.",
                request.controllerLocator.relativePath,
            )
        }
        val methodName = when (request.kind) {
            FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE ->
                "${lowercaseStem(request.componentId.orEmpty())}LoadFromRepositoryDelegate"
            FlowUiControllerHandlerKind.DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE ->
                "${fieldName}SaveDelegate"
            else -> error("Unsupported Kotlin repository handler: ${request.kind}")
        }
        val existingFunction = descendants(psiClass)
            .filterIsInstance<PsiNamedElement>()
            .firstOrNull { element ->
                element.javaClass.simpleName == "KtNamedFunction" &&
                    nearestKotlinClass(element) === psiClass &&
                    element.name == methodName
            }
        if (existingFunction != null) {
            val expectedTarget = request.componentId?.let(::escapeJavaString)
            val equivalent = existingFunction.text.contains("Install") &&
                existingFunction.text.contains(fieldName) &&
                (
                    request.kind == FlowUiControllerHandlerKind.DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE ||
                        existingFunction.text.contains("\"$expectedTarget\"")
                    )
            return if (equivalent) {
                ControllerChangeProposal(true, null, emptyList())
            } else {
                rejected(
                    "JVW-CONTROLLER-METHOD-CONFLICT",
                    "A Kotlin function named '$methodName' already exists but is not the requested delegate.",
                    request.controllerLocator.relativePath,
                )
            }
        }
        val classBody = descendants(psiClass)
            .firstOrNull { it.javaClass.simpleName == "KtClassBody" }
            ?: return rejected(
                "JVW-CONTROLLER-CLASS-MALFORMED",
                "The Kotlin controller class body cannot be edited safely.",
                request.controllerLocator.relativePath,
            )
        val rBraceOffset = classBody.textRange.endOffset - 1
        if (rBraceOffset !in source.indices || source[rBraceOffset] != '}') {
            return rejected(
                "JVW-CONTROLLER-CLASS-MALFORMED",
                "The Kotlin controller closing brace is unavailable.",
                request.controllerLocator.relativePath,
            )
        }
        val indent = kotlinMemberIndent(source, psiClass)
        val fieldSource = if (existingInjection == null) {
            """
            @org.springframework.beans.factory.annotation.Autowired
            private lateinit var $fieldName: $qualifiedRepository
            """.trimIndent()
        } else {
            null
        }
        val handlerSource = kotlinRepositoryHandlerSource(request, fieldName, methodName)
        val insertion = buildString {
            fieldSource?.let {
                append("\n\n")
                append(it.prependIndent(indent))
            }
            append("\n\n")
            append(handlerSource.prependIndent(indent))
            append('\n')
        }
        val edit = WorkspaceTextEdit(
            startOffset = rBraceOffset,
            endOffset = rBraceOffset,
            expectedText = "",
            replacement = insertion,
        )
        val resulting = source.replaceRange(edit.startOffset, edit.endOffset, edit.replacement)
        kotlinSyntaxIssue(request.controllerLocator.relativePath, resulting)?.let { issue ->
            return ControllerChangeProposal(false, null, listOf(issue))
        }
        val identity = listOf(
            request.controllerLocator.relativePath,
            request.controllerLocator.revisionFingerprint,
            request.kind,
            request.componentId,
            request.entityClass,
            request.repositoryQualifiedName,
            request.repositoryLocator?.revisionFingerprint,
        ).joinToString("\u0000")
        return ControllerChangeProposal(
            accepted = true,
            changeSet = WorkspaceChangeSet(
                id = "flowui-kotlin-repository-handler:" +
                    CanonicalDiscoveryJson.sha256(identity).take(24),
                label = "Add $methodName to ${psiClass.name}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.controllerLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = loaded.fingerprint,
                        edits = listOf(edit),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun handlerTemplate(
        request: FlowUiControllerHandlerRequest,
        subscribeSubjectSupported: Boolean,
        repositoryBinding: RepositoryControllerBinding?,
    ): ControllerHandlerTemplate =
        when (request.kind) {
            FlowUiControllerHandlerKind.VIEW_INIT -> ControllerHandlerTemplate(
                methodName = "onInit",
                annotation = "@Subscribe",
                annotationKind = "Subscribe",
                target = null,
                subject = null,
                targetScope = null,
                eventSimpleName = "InitEvent",
                parameters = "final InitEvent event",
                imports = setOf(SUBSCRIBE_IMPORT, "io.jmix.flowui.view.View.InitEvent"),
            )
            FlowUiControllerHandlerKind.VIEW_BEFORE_SHOW -> ControllerHandlerTemplate(
                methodName = "onBeforeShow",
                annotation = "@Subscribe",
                annotationKind = "Subscribe",
                target = null,
                subject = null,
                targetScope = null,
                eventSimpleName = "BeforeShowEvent",
                parameters = "final BeforeShowEvent event",
                imports = setOf(SUBSCRIBE_IMPORT, "io.jmix.flowui.view.View.BeforeShowEvent"),
            )
            FlowUiControllerHandlerKind.VIEW_READY -> ControllerHandlerTemplate(
                methodName = "onReady",
                annotation = "@Subscribe",
                annotationKind = "Subscribe",
                target = null,
                subject = null,
                targetScope = null,
                eventSimpleName = "ReadyEvent",
                parameters = "final ReadyEvent event",
                imports = setOf(SUBSCRIBE_IMPORT, "io.jmix.flowui.view.View.ReadyEvent"),
            )
            FlowUiControllerHandlerKind.VIEW_ATTACH -> ControllerHandlerTemplate(
                methodName = "onAttachEvent",
                annotation = "@Subscribe",
                annotationKind = "Subscribe",
                target = null,
                subject = null,
                targetScope = null,
                eventSimpleName = "AttachEvent",
                parameters = "final AttachEvent event",
                imports = setOf(SUBSCRIBE_IMPORT, "com.vaadin.flow.component.AttachEvent"),
            )
            FlowUiControllerHandlerKind.VIEW_BEFORE_CLOSE -> ControllerHandlerTemplate(
                methodName = "onBeforeClose",
                annotation = "@Subscribe",
                annotationKind = "Subscribe",
                target = null,
                subject = null,
                targetScope = null,
                eventSimpleName = "BeforeCloseEvent",
                parameters = "final BeforeCloseEvent event",
                imports = setOf(SUBSCRIBE_IMPORT, "io.jmix.flowui.view.View.BeforeCloseEvent"),
            )
            FlowUiControllerHandlerKind.VIEW_AFTER_CLOSE -> ControllerHandlerTemplate(
                methodName = "onAfterClose",
                annotation = "@Subscribe",
                annotationKind = "Subscribe",
                target = null,
                subject = null,
                targetScope = null,
                eventSimpleName = "AfterCloseEvent",
                parameters = "final AfterCloseEvent event",
                imports = setOf(SUBSCRIBE_IMPORT, "io.jmix.flowui.view.View.AfterCloseEvent"),
            )
            FlowUiControllerHandlerKind.VIEW_DETACH -> ControllerHandlerTemplate(
                methodName = "onDetachEvent",
                annotation = "@Subscribe",
                annotationKind = "Subscribe",
                target = null,
                subject = null,
                targetScope = null,
                eventSimpleName = "DetachEvent",
                parameters = "final DetachEvent event",
                imports = setOf(SUBSCRIBE_IMPORT, "com.vaadin.flow.component.DetachEvent"),
            )
            FlowUiControllerHandlerKind.VIEW_QUERY_PARAMETERS_CHANGE -> ControllerHandlerTemplate(
                methodName = "onQueryParametersChange",
                annotation = "@Subscribe",
                annotationKind = "Subscribe",
                target = null,
                subject = null,
                targetScope = null,
                eventSimpleName = "QueryParametersChangeEvent",
                parameters = "final QueryParametersChangeEvent event",
                imports = setOf(
                    SUBSCRIBE_IMPORT,
                    "io.jmix.flowui.view.View.QueryParametersChangeEvent",
                ),
            )
            FlowUiControllerHandlerKind.BUTTON_CLICK -> {
                val componentId = request.componentId.orEmpty()
                ControllerHandlerTemplate(
                    methodName = "on${handlerStem(componentId)}Click",
                    annotation = if (subscribeSubjectSupported) {
                        "@Subscribe(id = \"${escapeJavaString(componentId)}\", subject = \"clickListener\")"
                    } else {
                        "@Subscribe(\"${escapeJavaString(componentId)}\")"
                    },
                    annotationKind = "Subscribe",
                    target = componentId,
                    subject = "clickListener".takeIf { subscribeSubjectSupported },
                    targetScope = null,
                    eventSimpleName = "ClickEvent",
                    parameters = "final ClickEvent<JmixButton> event",
                    imports = setOf(
                        SUBSCRIBE_IMPORT,
                        "com.vaadin.flow.component.ClickEvent",
                        "io.jmix.flowui.kit.component.button.JmixButton",
                    ),
                )
            }
            FlowUiControllerHandlerKind.COMPONENT_TYPED_VALUE_CHANGE -> {
                val componentId = request.componentId.orEmpty()
                ControllerHandlerTemplate(
                    methodName = "on${handlerStem(componentId)}TypedValueChange",
                    annotation = "@Subscribe(\"${escapeJavaString(componentId)}\")",
                    annotationKind = "Subscribe",
                    target = componentId,
                    subject = null,
                    targetScope = null,
                    eventSimpleName = "TypedValueChangeEvent",
                    parameters = "final SupportsTypedValue.TypedValueChangeEvent<?, ?> event",
                    imports = setOf(
                        SUBSCRIBE_IMPORT,
                        "io.jmix.flowui.component.SupportsTypedValue",
                    ),
                )
            }
            FlowUiControllerHandlerKind.COMPONENT_VALUE_CHANGE -> {
                val componentId = request.componentId.orEmpty()
                ControllerHandlerTemplate(
                    methodName = "on${handlerStem(componentId)}ComponentValueChange",
                    annotation = "@Subscribe(\"${escapeJavaString(componentId)}\")",
                    annotationKind = "Subscribe",
                    target = componentId,
                    subject = null,
                    targetScope = null,
                    eventSimpleName = "ComponentValueChangeEvent",
                    parameters = "final AbstractField.ComponentValueChangeEvent<?, ?> event",
                    imports = setOf(
                        SUBSCRIBE_IMPORT,
                        "com.vaadin.flow.component.AbstractField",
                    ),
                )
            }
            FlowUiControllerHandlerKind.ACTION_PERFORMED -> {
                val targetId = request.targetId.orEmpty()
                ControllerHandlerTemplate(
                    methodName = "on${handlerStem(targetId)}",
                    annotation = "@Subscribe(\"${escapeJavaString(targetId)}\")",
                    annotationKind = "Subscribe",
                    target = targetId,
                    subject = null,
                    targetScope = null,
                    eventSimpleName = "ActionPerformedEvent",
                    parameters = "final ActionPerformedEvent event",
                    imports = setOf(
                        SUBSCRIBE_IMPORT,
                        "io.jmix.flowui.kit.action.ActionPerformedEvent",
                    ),
                )
            }
            FlowUiControllerHandlerKind.COLLECTION_LOADER_PRE_LOAD -> {
                val loaderId = request.componentId.orEmpty()
                val entityType = entityType(request.entityClass)
                ControllerHandlerTemplate(
                    methodName = "on${handlerStem(loaderId)}PreLoad",
                    annotation = "@Subscribe(id = \"${escapeJavaString(loaderId)}\", target = Target.DATA_LOADER)",
                    annotationKind = "Subscribe",
                    target = loaderId,
                    subject = null,
                    targetScope = "Target.DATA_LOADER",
                    eventSimpleName = "PreLoadEvent",
                    parameters = "final CollectionLoader.PreLoadEvent<${entityType.sourceType}> event",
                    imports = setOf(
                        SUBSCRIBE_IMPORT,
                        TARGET_IMPORT,
                        "io.jmix.flowui.model.CollectionLoader",
                    ) + entityType.imports,
                )
            }
            FlowUiControllerHandlerKind.COLLECTION_LOADER_POST_LOAD -> {
                val loaderId = request.componentId.orEmpty()
                val entityType = entityType(request.entityClass)
                ControllerHandlerTemplate(
                    methodName = "on${handlerStem(loaderId)}PostLoad",
                    annotation = "@Subscribe(id = \"${escapeJavaString(loaderId)}\", target = Target.DATA_LOADER)",
                    annotationKind = "Subscribe",
                    target = loaderId,
                    subject = null,
                    targetScope = "Target.DATA_LOADER",
                    eventSimpleName = "PostLoadEvent",
                    parameters = "final CollectionLoader.PostLoadEvent<${entityType.sourceType}> event",
                    imports = setOf(
                        SUBSCRIBE_IMPORT,
                        TARGET_IMPORT,
                        "io.jmix.flowui.model.CollectionLoader",
                    ) + entityType.imports,
                )
            }
            FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE -> {
                val loaderId = request.componentId.orEmpty()
                val entityType = entityType(request.entityClass)
                val repository = requireNotNull(repositoryBinding)
                ControllerHandlerTemplate(
                    methodName = "${lowercaseStem(loaderId)}LoadFromRepositoryDelegate",
                    annotation = "@Install(to = \"${escapeJavaString(loaderId)}\", " +
                        "target = Target.DATA_LOADER, subject = \"loadFromRepositoryDelegate\")",
                    annotationKind = "Install",
                    target = loaderId,
                    subject = "loadFromRepositoryDelegate",
                    targetScope = "Target.DATA_LOADER",
                    eventSimpleName = null,
                    visibility = "private",
                    returnType = "List<${entityType.sourceType}>",
                    parameters = "final Pageable pageable, final JmixDataRepositoryContext context",
                    imports = setOf(
                        INSTALL_IMPORT,
                        TARGET_IMPORT,
                        "io.jmix.core.repository.JmixDataRepositoryContext",
                        "org.springframework.data.domain.Pageable",
                        "java.util.List",
                    ) + entityType.imports + repository.imports,
                    bodyLines = listOf(
                        "return ${repository.fieldName}.findAll(pageable, context).getContent();",
                    ),
                )
            }
            FlowUiControllerHandlerKind.DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE -> {
                val entityType = entityType(request.entityClass)
                val repository = requireNotNull(repositoryBinding)
                ControllerHandlerTemplate(
                    methodName = "${repository.fieldName}SaveDelegate",
                    annotation = "@Install(target = Target.DATA_CONTEXT)",
                    annotationKind = "Install",
                    target = null,
                    subject = null,
                    targetScope = "Target.DATA_CONTEXT",
                    eventSimpleName = null,
                    visibility = "private",
                    returnType = "Set<Object>",
                    parameters = "final SaveContext saveContext",
                    imports = setOf(
                        INSTALL_IMPORT,
                        TARGET_IMPORT,
                        "io.jmix.core.SaveContext",
                        "java.util.Set",
                    ) + entityType.imports + repository.imports,
                    bodyLines = listOf(
                        "if (saveContext.getEntitiesToSave().size() != 1 || " +
                            "!saveContext.getEntitiesToRemove().isEmpty()) {",
                        "    throw new IllegalStateException(\"Repository save delegate supports one " +
                            "${escapeJavaString(entityType.sourceType)} and no removals. " +
                            "Use a transactional update service for aggregate saves.\");",
                        "}",
                        "final Object entity = saveContext.getEntitiesToSave().iterator().next();",
                        "if (!(entity instanceof ${entityType.sourceType} typedEntity)) {",
                        "    throw new IllegalStateException(\"Unexpected entity type in repository save delegate: \" + " +
                            "entity.getClass().getName());",
                        "}",
                        "return Set.of(${repository.fieldName}.save(typedEntity));",
                    ),
                )
            }
            FlowUiControllerHandlerKind.COMPONENT_VALIDATOR -> {
                val componentId = request.componentId.orEmpty()
                ControllerHandlerTemplate(
                    methodName = "${lowercaseStem(componentId)}Validator",
                    annotation = "@Install(to = \"${escapeJavaString(componentId)}\", subject = \"validator\")",
                    annotationKind = "Install",
                    target = componentId,
                    subject = "validator",
                    targetScope = null,
                    eventSimpleName = null,
                    visibility = "private",
                    parameters = "final Object value",
                    imports = setOf(INSTALL_IMPORT),
                )
            }
        }

    private fun loadRepositoryBinding(
        request: FlowUiControllerHandlerRequest,
        controllerClass: PsiClass,
    ): LoadedRepositoryBinding {
        val locator = request.repositoryLocator
            ?: return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-MISSING",
                "Choose the Jmix data repository that will back this loader.",
                request.controllerLocator.relativePath,
            )
        val expectedQualifiedName = request.repositoryQualifiedName
            ?.trim()
            ?.takeIf(JAVA_QUALIFIED_NAME::matches)
            ?: return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-NAME-INVALID",
                "The selected repository must have a valid qualified JVM type name.",
                locator.relativePath,
            )
        val validation = SourceNavigationPolicy.validate(
            locator.relativePath,
            locator.line,
            locator.column,
            locator.revisionFingerprint,
        )
        val validated = validation.locator
            ?: return LoadedRepositoryBinding.rejected(
                validation.errorCode ?: "JVW-CONTROLLER-REPOSITORY-PATH-REJECTED",
                validation.message,
                locator.relativePath,
            )
        val kotlin = validated.relativePath.endsWith(".kt", ignoreCase = true)
        if (!kotlin && !validated.relativePath.endsWith(".java", ignoreCase = true)) {
            return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-LANGUAGE-UNSUPPORTED",
                "Jmix repository delegates require a Java or Kotlin repository interface.",
                validated.relativePath,
            )
        }
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(validated.relativePath)
            ?: return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-SOURCE-MISSING",
                "The selected repository source no longer exists.",
                validated.relativePath,
            )
        val file = resolved.file
        if (file.isDirectory || !VfsUtilCore.isAncestor(resolved.root, file, false)) {
            return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-PATH-REJECTED",
                "The repository is outside registered project content roots.",
                validated.relativePath,
            )
        }
        if (file.length > MAX_REPOSITORY_BYTES) {
            return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-SOURCE-TOO-LARGE",
                "The repository exceeds the reviewed ${MAX_REPOSITORY_BYTES / (1024 * 1024)} MiB limit.",
                validated.relativePath,
            )
        }
        val content = runCatching { ProjectSourceText.read(file) }.getOrElse {
            return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-SOURCE-UNREADABLE",
                "The selected repository source cannot be read.",
                validated.relativePath,
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (!SourceNavigationPolicy.revisionMatches(validated, fingerprint)) {
            return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-SOURCE-STALE",
                "The repository changed after this view workspace was loaded. Refresh before generating the delegate.",
                validated.relativePath,
            )
        }
        val parsed = RepositorySourceParser.parse(content, kotlin)
            ?: return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-SOURCE-PARSE",
                "The selected type is not a supported JmixDataRepository interface.",
                validated.relativePath,
            )
        val packageName = PACKAGE_DECLARATION.find(content)?.groupValues?.get(1).orEmpty()
        val actualQualifiedName = if (packageName.isBlank()) {
            parsed.interfaceName
        } else {
            "$packageName.${parsed.interfaceName}"
        }
        if (actualQualifiedName != expectedQualifiedName) {
            return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-IDENTITY-MISMATCH",
                "The selected repository identity changed. Refresh before generating the delegate.",
                validated.relativePath,
            )
        }
        val requestedEntity = request.entityClass.orEmpty()
        if (!sourceTypeMatchesExpected(parsed.entityType, requestedEntity, content, packageName)) {
            return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-ENTITY-MISMATCH",
                "${parsed.interfaceName} targets ${parsed.entityType}, not $requestedEntity.",
                validated.relativePath,
            )
        }
        val constraintEvaluation = effectiveRepositoryConstraints(
            request.kind,
            parsed,
            file,
            actualQualifiedName,
        )
        if (constraintEvaluation.enabled != true) {
            return LoadedRepositoryBinding.rejected(
                if (constraintEvaluation.enabled == false) {
                    "JVW-CONTROLLER-REPOSITORY-SECURITY-BYPASS"
                } else {
                    "JVW-CONTROLLER-REPOSITORY-SECURITY-UNPROVEN"
                },
                constraintEvaluation.reason,
                validated.relativePath,
            )
        }

        val sameTypeFields = controllerClass.fields.filter { field ->
            fieldMatchesQualifiedType(field, actualQualifiedName, controllerClass.containingFile as PsiJavaFile)
        }
        if (sameTypeFields.size > 1) {
            return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-INJECTION-AMBIGUOUS",
                "The controller has multiple ${parsed.interfaceName} fields. Choose the injection in native code.",
                request.controllerLocator.relativePath,
            )
        }
        val existing = sameTypeFields.singleOrNull()
        val preferredName = parsed.interfaceName.replaceFirstChar(Char::lowercase)
        val conflicting = controllerClass.fields.firstOrNull {
            it.name == preferredName && it != existing
        }
        if (conflicting != null) {
            return LoadedRepositoryBinding.rejected(
                "JVW-CONTROLLER-REPOSITORY-FIELD-CONFLICT",
                "A different controller field already uses the name '$preferredName'.",
                request.controllerLocator.relativePath,
            )
        }
        val fieldName = existing?.name ?: preferredName
        val fieldText = if (existing == null) {
            "@Autowired\nprivate ${parsed.interfaceName} $fieldName;"
        } else {
            null
        }
        return LoadedRepositoryBinding(
            binding = RepositoryControllerBinding(
                fieldName = fieldName,
                imports = buildSet {
                    add(actualQualifiedName)
                    if (existing == null) add(AUTOWIRED_IMPORT)
                },
                fieldText = fieldText,
            ),
            issues = emptyList(),
        )
    }

    private fun effectiveRepositoryConstraints(
        handlerKind: FlowUiControllerHandlerKind,
        parsed: ParsedRepositorySource,
        repositoryFile: com.intellij.openapi.vfs.VirtualFile,
        repositoryQualifiedName: String,
    ): RepositoryConstraintEvaluation {
        val invocation = when (handlerKind) {
            FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE ->
                RepositoryInvocation.LOAD_PAGE_WITH_CONTEXT
            FlowUiControllerHandlerKind.DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE ->
                RepositoryInvocation.SAVE_ONE
            else -> return RepositoryConstraintEvaluation(
                null,
                "The generated repository invocation could not be identified.",
            )
        }
        parsed.config.methods.firstOrNull(invocation::matches)?.applyConstraints?.let { enabled ->
            return RepositoryConstraintEvaluation(
                enabled,
                "${parsed.interfaceName}.${invocation.presentableSignature} explicitly resolves to " +
                    "@ApplyConstraints($enabled).",
            )
        }

        val source = ProjectSourceText.read(repositoryFile)
        val hasAdditionalParent = repositoryHeaderHasAdditionalParent(source, parsed.interfaceName)
        val repositoryClass = if (hasAdditionalParent && !DumbService.isDumb(project)) {
            (PsiManager.getInstance(project).findFile(repositoryFile) as? PsiJavaFile)
                ?.classes
                ?.firstOrNull { it.qualifiedName == repositoryQualifiedName || it.name == parsed.interfaceName }
                ?: JavaPsiFacade.getInstance(project).findClass(
                    repositoryQualifiedName,
                    ProjectScope.getAllScope(project),
                )
        } else {
            null
        }
        if (hasAdditionalParent && repositoryClass != null) {
            inheritedMethodConstraint(repositoryClass, invocation)?.let { inherited ->
                return inherited
            }
            if (hasUnresolvedCustomRepositoryParent(repositoryClass)) {
                return RepositoryConstraintEvaluation(
                    null,
                    "The repository has an unresolved custom parent, so inherited method-level " +
                        "@ApplyConstraints policy cannot be proven.",
                )
            }
        } else if (hasAdditionalParent) {
            return RepositoryConstraintEvaluation(
                null,
                "The custom repository hierarchy is not available to IntelliJ indexes. Refresh Gradle and indexing " +
                    "before wiring a security-sensitive delegate.",
            )
        }

        parsed.repositoryApplyConstraints?.let { enabled ->
            return RepositoryConstraintEvaluation(
                enabled,
                "${parsed.interfaceName} explicitly declares @ApplyConstraints($enabled).",
            )
        }
        if (hasAdditionalParent && repositoryClass != null) {
            inheritedTypeConstraint(repositoryClass)?.let { inherited ->
                return inherited
            }
        }
        return RepositoryConstraintEvaluation(
            true,
            "${parsed.interfaceName}.${invocation.presentableSignature} uses Jmix's constrained default.",
        )
    }

    private fun inheritedMethodConstraint(
        repositoryClass: PsiClass,
        invocation: RepositoryInvocation,
    ): RepositoryConstraintEvaluation? {
        var level = repositoryClass.supers.toList()
        val visited = linkedSetOf<PsiClass>()
        while (level.isNotEmpty()) {
            val current = level.filter(visited::add)
            val values = current.flatMap { owner ->
                owner.methods.asSequence()
                    .filter(invocation::matches)
                    .mapNotNull(::explicitApplyConstraints)
                    .toList()
            }.distinct()
            if (values.size > 1) {
                return RepositoryConstraintEvaluation(
                    null,
                    "Conflicting inherited method-level @ApplyConstraints policies were found for " +
                        invocation.presentableSignature + ".",
                )
            }
            values.singleOrNull()?.let { enabled ->
                return RepositoryConstraintEvaluation(
                    enabled,
                    "Inherited ${invocation.presentableSignature} declares @ApplyConstraints($enabled).",
                )
            }
            level = current.flatMap { it.supers.toList() }
        }
        return null
    }

    private fun inheritedTypeConstraint(
        repositoryClass: PsiClass,
    ): RepositoryConstraintEvaluation? {
        var level = repositoryClass.supers.toList()
        val visited = linkedSetOf<PsiClass>()
        while (level.isNotEmpty()) {
            val current = level.filter(visited::add)
            val values = current.mapNotNull(::explicitApplyConstraints).distinct()
            if (values.size > 1) {
                return RepositoryConstraintEvaluation(
                    null,
                    "Conflicting inherited repository-level @ApplyConstraints policies were found.",
                )
            }
            values.singleOrNull()?.let { enabled ->
                return RepositoryConstraintEvaluation(
                    enabled,
                    "An inherited repository interface declares @ApplyConstraints($enabled).",
                )
            }
            level = current.flatMap { it.supers.toList() }
        }
        return null
    }

    private fun explicitApplyConstraints(owner: PsiModifierListOwner): Boolean? {
        val annotation = owner.modifierList?.annotations?.firstOrNull { candidate ->
            candidate.shortName() == "ApplyConstraints"
        } ?: return null
        val value = annotation.findAttributeValue("value")?.text?.trim()
        return value?.equals("false", ignoreCase = true)?.not() ?: true
    }

    private fun hasUnresolvedCustomRepositoryParent(repositoryClass: PsiClass): Boolean =
        generateSequence(listOf(repositoryClass)) { level ->
            level.flatMap { it.supers.toList() }.takeIf(List<PsiClass>::isNotEmpty)
        }.flatten().any { owner ->
            owner.extendsListTypes.any { type ->
                type.resolve() == null &&
                    type.className !in KNOWN_REPOSITORY_BASES
            }
        }

    private fun repositoryHeaderHasAdditionalParent(
        source: String,
        interfaceName: String,
    ): Boolean {
        val header = source.substringAfter("interface $interfaceName", "")
            .substringBefore('{')
        var genericDepth = 0
        return header.any { character ->
            when (character) {
                '<' -> genericDepth++
                '>' -> genericDepth--
                ',' -> if (genericDepth == 0) return true
            }
            false
        }
    }

    private fun sourceTypeMatchesExpected(
        declaredType: String,
        expectedQualifiedName: String,
        source: String,
        sourcePackage: String,
    ): Boolean {
        val normalized = declaredType.trim().removeSuffix("?")
        if (normalized == expectedQualifiedName) return true
        if ('.' in normalized || normalized != expectedQualifiedName.substringAfterLast('.')) {
            return false
        }
        val expectedPackage = expectedQualifiedName.substringBeforeLast('.', "")
        val imports = IMPORT_DECLARATION.findAll(source)
            .map { it.groupValues[1] }
            .toList()
        val explicitSameSimpleName = imports.firstOrNull { imported ->
            !imported.endsWith(".*") &&
                imported.substringAfterLast('.') == normalized
        }
        if (explicitSameSimpleName != null) {
            return explicitSameSimpleName == expectedQualifiedName
        }
        if (sourcePackage == expectedPackage) return true
        return "$expectedPackage.*" in imports
    }

    private fun fieldMatchesQualifiedType(
        field: PsiField,
        expectedQualifiedName: String,
        psiFile: PsiJavaFile,
    ): Boolean {
        val resolvedName = (field.type as? PsiClassType)?.resolve()?.qualifiedName
        if (resolvedName != null) return resolvedName == expectedQualifiedName
        val canonical = field.type.canonicalText.trim().removeSuffix("?")
        if (canonical == expectedQualifiedName) return true
        if ('.' in canonical || canonical != expectedQualifiedName.substringAfterLast('.')) {
            return false
        }
        val expectedPackage = expectedQualifiedName.substringBeforeLast('.', "")
        val explicitSameSimpleName = psiFile.importList?.allImportStatements.orEmpty()
            .filterNot { it.isOnDemand }
            .mapNotNull { it.importReference?.qualifiedName }
            .firstOrNull { it.substringAfterLast('.') == canonical }
        if (explicitSameSimpleName != null) {
            return explicitSameSimpleName == expectedQualifiedName
        }
        return psiFile.packageName == expectedPackage || psiFile.hasImport(expectedQualifiedName)
    }

    private fun addImportEdit(
        psiFile: PsiJavaFile,
        requiredImports: Set<String>,
    ): WorkspaceTextEdit? {
        val missing = requiredImports.filterNot { psiFile.hasImport(it) }
        if (missing.isEmpty()) return null
        val importList = psiFile.importList ?: return null
        val existingImports = importList.allImportStatements
        val importOffset = existingImports.lastOrNull()?.textRange?.endOffset
            ?: psiFile.packageStatement?.textRange?.endOffset
            ?: 0
        val importText = missing.sorted().joinToString("\n") { "import $it;" }
        val replacement = when {
            existingImports.isNotEmpty() -> "\n$importText"
            importOffset > 0 -> "\n\n$importText"
            else -> "$importText\n\n"
        }
        return WorkspaceTextEdit(importOffset, importOffset, "", replacement)
    }

    private fun importConflict(
        psiFile: PsiJavaFile,
        requiredImports: Set<String>,
    ): Pair<String, String>? {
        val explicitImports = psiFile.importList?.allImportStatements.orEmpty()
            .filterNot { it.isOnDemand }
            .mapNotNull { it.importReference?.qualifiedName }
            .associateBy { it.substringAfterLast('.') }
        return requiredImports.firstNotNullOfOrNull { required ->
            val simpleName = required.substringAfterLast('.')
            explicitImports[simpleName]
                ?.takeIf { it != required }
                ?.let { simpleName to it }
        }
    }

    private fun escapeJavaString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun handlerStem(value: String): String {
        val parts = value.split(Regex("[^A-Za-z0-9_$]+")).filter(String::isNotBlank)
        return parts.joinToString("") { part ->
            part.replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase() else character.toString()
            }
        }.ifBlank { "Target" }
    }

    private fun lowercaseStem(value: String): String =
        handlerStem(value).replaceFirstChar { it.lowercaseChar() }

    private fun entityType(qualifiedName: String?): ControllerFieldType {
        val validName = qualifiedName?.takeIf(JAVA_QUALIFIED_NAME::matches) ?: return ControllerFieldType(
            sourceType = "Object",
            imports = emptySet(),
        )
        return ControllerFieldType(
            sourceType = validName.substringAfterLast('.'),
            imports = setOf(validName).filterTo(linkedSetOf()) { '.' in it },
        )
    }

    private fun targetScopeMatches(actual: String?, expected: String?): Boolean =
        when {
            expected == null -> actual == null
            actual == null -> false
            else -> actual == expected || actual.substringAfterLast('.') == expected.substringAfterLast('.')
        }

    private fun subscribeSubjectSupported(): Boolean? =
        JavaPsiFacade.getInstance(project)
            .findClass(
                SUBSCRIBE_IMPORT,
                ProjectScope.getContentScope(project)
                    .uniteWith(ProjectScope.getLibrariesScope(project)),
            )
            ?.findMethodsByName("subject", false)
            ?.isNotEmpty()

    private fun loadKotlinController(locator: SourceLocator): LoadedKotlinController {
        val validation = SourceNavigationPolicy.validate(
            locator.relativePath,
            locator.line,
            locator.column,
            locator.revisionFingerprint,
        )
        val validated = validation.locator
            ?: return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    validation.errorCode ?: "JVW-CONTROLLER-PATH-REJECTED",
                    validation.message,
                    locator.relativePath,
                ),
            )
        if (!validated.relativePath.endsWith(".kt", ignoreCase = true)) {
            return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-LANGUAGE-UNSUPPORTED",
                    "Expected a Kotlin controller source file.",
                    validated.relativePath,
                ),
            )
        }
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(validated.relativePath)
            ?: return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-SOURCE-MISSING",
                    "The connected Kotlin controller no longer exists.",
                    validated.relativePath,
                ),
            )
        val file = resolved.file
        if (file.isDirectory || !VfsUtilCore.isAncestor(resolved.root, file, false)) {
            return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-PATH-REJECTED",
                    "The Kotlin controller is outside registered project content roots.",
                    validated.relativePath,
                ),
            )
        }
        if (file.length > MAX_CONTROLLER_BYTES) {
            return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-SOURCE-TOO-LARGE",
                    "The Kotlin controller exceeds the reviewed " +
                        "${MAX_CONTROLLER_BYTES / (1024 * 1024)} MiB mutation limit.",
                    validated.relativePath,
                ),
            )
        }
        val content = runCatching { ProjectSourceText.read(file) }.getOrElse {
            return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-SOURCE-UNREADABLE",
                    "The Kotlin controller source cannot be read.",
                    validated.relativePath,
                ),
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (!SourceNavigationPolicy.revisionMatches(validated, fingerprint)) {
            return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-SOURCE-STALE",
                    "The Kotlin controller changed after this workspace was loaded. Refresh before editing.",
                    validated.relativePath,
                ),
            )
        }
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-PSI-UNAVAILABLE",
                    "IntelliJ could not build a Kotlin PSI model for this controller.",
                    validated.relativePath,
                ),
            )
        if (psiFile.javaClass.simpleName != "KtFile") {
            return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-PSI-UNAVAILABLE",
                    "The selected source is not recognized as a Kotlin PSI file.",
                    validated.relativePath,
                ),
            )
        }
        val classes = descendants(psiFile)
            .filterIsInstance<PsiNamedElement>()
            .filter { it.javaClass.simpleName in setOf("KtClass", "KtObjectDeclaration") }
            .filter { nearestKotlinClass(it.parent) == null }
            .toList()
        val controllerClass = classes.firstOrNull { candidate ->
            candidate.text.substringBefore('{').contains("ViewController")
        } ?: classes.firstOrNull()
            ?: return LoadedKotlinController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-CLASS-MISSING",
                    "No Kotlin controller class or object was found.",
                    validated.relativePath,
                ),
            )
        return LoadedKotlinController(
            content,
            fingerprint,
            controllerClass,
            emptyList(),
        )
    }

    private fun kotlinRepositoryHandlerSource(
        request: FlowUiControllerHandlerRequest,
        fieldName: String,
        methodName: String,
    ): String {
        val entityType = requireNotNull(request.entityClass)
        return when (request.kind) {
            FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE -> """
                @io.jmix.flowui.view.Install(
                    to = "${escapeJavaString(request.componentId.orEmpty())}",
                    target = io.jmix.flowui.view.Target.DATA_LOADER,
                    subject = "loadFromRepositoryDelegate",
                )
                private fun $methodName(
                    pageable: org.springframework.data.domain.Pageable,
                    context: io.jmix.core.repository.JmixDataRepositoryContext,
                ): List<$entityType> {
                    return $fieldName.findAll(pageable, context).content
                }
            """.trimIndent()
            FlowUiControllerHandlerKind.DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE -> """
                @io.jmix.flowui.view.Install(target = io.jmix.flowui.view.Target.DATA_CONTEXT)
                private fun $methodName(
                    saveContext: io.jmix.core.SaveContext,
                ): Set<Any> {
                    if (saveContext.getEntitiesToSave().size != 1 ||
                        saveContext.getEntitiesToRemove().isNotEmpty()
                    ) {
                        error(
                            "Repository save delegate supports one ${escapeJavaString(entityType.substringAfterLast('.'))} " +
                                "and no removals. Use a transactional update service for aggregate saves.",
                        )
                    }
                    val entity = saveContext.getEntitiesToSave().first()
                    require(entity is $entityType) {
                        "Unexpected entity type in repository save delegate: " + entity.javaClass.name
                    }
                    return setOf($fieldName.save(entity))
                }
            """.trimIndent()
            else -> error("Unsupported Kotlin repository handler: ${request.kind}")
        }
    }

    private fun kotlinDeclaredType(source: String): String? =
        KOTLIN_DECLARED_TYPE.find(source)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.removeSuffix("?")

    private fun nearestKotlinClass(element: com.intellij.psi.PsiElement?): PsiNamedElement? =
        generateSequence(element) { it.parent }
            .filterIsInstance<PsiNamedElement>()
            .firstOrNull {
                it.javaClass.simpleName in setOf("KtClass", "KtObjectDeclaration")
            }

    private fun descendants(root: com.intellij.psi.PsiElement): Sequence<com.intellij.psi.PsiElement> =
        sequence {
            root.children.forEach { child ->
                yield(child)
                yieldAll(descendants(child))
            }
        }

    private fun kotlinMemberIndent(source: String, psiClass: PsiNamedElement): String {
        val member = descendants(psiClass)
            .filterIsInstance<PsiNamedElement>()
            .firstOrNull { element ->
                element.javaClass.simpleName in setOf("KtProperty", "KtNamedFunction") &&
                    nearestKotlinClass(element.parent) === psiClass
            }
        if (member != null) {
            val lineStart = source.lastIndexOf('\n', member.textOffset - 1).let { it + 1 }
            val indentation = source.substring(lineStart, member.textOffset).takeWhile(Char::isWhitespace)
            if (indentation.isNotEmpty()) return indentation
        }
        val classLineStart = source.lastIndexOf('\n', psiClass.textOffset - 1).let { it + 1 }
        val classIndent = source.substring(classLineStart, psiClass.textOffset).takeWhile(Char::isWhitespace)
        return "$classIndent    "
    }

    private fun kotlinSyntaxIssue(
        fileName: String,
        content: String,
    ): WorkspaceChangeIssue? {
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension("kt")
            .takeIf { it.name.contains("kotlin", ignoreCase = true) }
            ?: return WorkspaceChangeIssue(
                "JVW-CONTROLLER-KOTLIN-MISSING",
                "Kotlin controller updates require the bundled IntelliJ Kotlin plugin.",
                fileName,
            )
        val psi = PsiFileFactory.getInstance(project).createFileFromText(
            fileName.substringAfterLast('/'),
            fileType,
            content,
        )
        val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java) ?: return null
        return WorkspaceChangeIssue(
            "JVW-CONTROLLER-GENERATED-SYNTAX",
            "Proposed Kotlin controller source is invalid: ${error.errorDescription}.",
            fileName,
        )
    }

    private fun loadController(locator: SourceLocator): LoadedJavaController {
        val validation = SourceNavigationPolicy.validate(
            locator.relativePath,
            locator.line,
            locator.column,
            locator.revisionFingerprint,
        )
        val validated = validation.locator
            ?: return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    validation.errorCode ?: "JVW-CONTROLLER-PATH-REJECTED",
                    validation.message,
                    locator.relativePath,
                ),
            )
        if (!validated.relativePath.endsWith(".java", ignoreCase = true)) {
            return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-LANGUAGE-UNSUPPORTED",
                    "PSI-safe controller mutation is currently available for Java controllers.",
                    validated.relativePath,
                ),
            )
        }
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(validated.relativePath)
            ?: return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-SOURCE-MISSING",
                    "The connected controller no longer exists.",
                    validated.relativePath,
                ),
            )
        val file = resolved.file
        if (file.isDirectory || !VfsUtilCore.isAncestor(resolved.root, file, false)) {
            return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-PATH-REJECTED",
                    "The controller is outside the registered project content roots.",
                    validated.relativePath,
                ),
            )
        }
        if (file.length > MAX_CONTROLLER_BYTES) {
            return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-SOURCE-TOO-LARGE",
                    "The controller exceeds the reviewed ${MAX_CONTROLLER_BYTES / (1024 * 1024)} MiB mutation limit.",
                    validated.relativePath,
                ),
            )
        }
        val content = runCatching {
            String(file.contentsToByteArray(false), file.charset)
        }.getOrElse {
            return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-SOURCE-UNREADABLE",
                    "The controller source cannot be read.",
                    validated.relativePath,
                ),
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (!SourceNavigationPolicy.revisionMatches(validated, fingerprint)) {
            return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-SOURCE-STALE",
                    "The controller changed after this workspace was loaded. Refresh before editing.",
                    validated.relativePath,
                ),
            )
        }
        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile
            ?: return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-PSI-UNAVAILABLE",
                    "IntelliJ could not build a Java PSI model for this controller.",
                    validated.relativePath,
                ),
            )
        val controllerClass = psiFile.classes.firstOrNull {
            it.annotations.any { annotation -> annotation.shortName() == "ViewController" }
        } ?: psiFile.classes.firstOrNull()
            ?: return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-CLASS-MISSING",
                    "No Java controller class was found.",
                    validated.relativePath,
                ),
            )
        return LoadedJavaController(content, fingerprint, psiFile, controllerClass, emptyList())
    }

    private fun PsiJavaFile.hasImport(qualifiedName: String): Boolean {
        val packageName = qualifiedName.substringBeforeLast('.')
        return importList?.allImportStatements.orEmpty().any { statement ->
            statement.importReference?.qualifiedName == qualifiedName ||
                (statement.isOnDemand && statement.importReference?.qualifiedName == packageName)
        }
    }

    private fun PsiAnnotation.shortName(): String =
        qualifiedName?.substringAfterLast('.') ?: nameReferenceElement?.referenceName.orEmpty()

    private fun PsiAnnotation.stringAttribute(name: String): String? =
        findDeclaredAttributeValue(name)?.text?.trim()?.removeSurrounding("\"")

    private fun memberIndent(content: String, psiClass: PsiClass): String {
        val member = psiClass.fields.firstOrNull() ?: psiClass.methods.firstOrNull()
        if (member != null) {
            val lineStart = content.lastIndexOf('\n', member.textOffset - 1).let { it + 1 }
            val indentation = content.substring(lineStart, member.textOffset).takeWhile(Char::isWhitespace)
            if (indentation.isNotEmpty()) return indentation
        }
        val classLineStart = content.lastIndexOf('\n', psiClass.textOffset - 1).let { it + 1 }
        val classIndent = content.substring(classLineStart, psiClass.textOffset).takeWhile(Char::isWhitespace)
        return "$classIndent    "
    }

    private fun controllerFieldType(tag: String, entityClass: String?): ControllerFieldType {
        val entity = entityClass
            ?.takeIf { JAVA_QUALIFIED_NAME.matches(it) }
            ?.substringAfterLast('.')
            ?: "Object"
        val entityImport = entityClass
            ?.takeIf { JAVA_QUALIFIED_NAME.matches(it) && '.' in it }
            .orEmpty()
        return when (tag.substringAfter(':')) {
            "button" -> ControllerFieldType(
                "JmixButton",
                setOf("io.jmix.flowui.kit.component.button.JmixButton"),
            )
            "textField" -> ControllerFieldType(
                "TypedTextField<String>",
                setOf("io.jmix.flowui.component.textfield.TypedTextField"),
            )
            "textArea" -> ControllerFieldType(
                "TextArea",
                setOf("com.vaadin.flow.component.textfield.TextArea"),
            )
            "emailField" -> ControllerFieldType(
                "EmailField",
                setOf("com.vaadin.flow.component.textfield.EmailField"),
            )
            "passwordField" -> ControllerFieldType(
                "PasswordField",
                setOf("com.vaadin.flow.component.textfield.PasswordField"),
            )
            "integerField" -> ControllerFieldType(
                "TypedTextField<Integer>",
                setOf("io.jmix.flowui.component.textfield.TypedTextField"),
            )
            "bigDecimalField", "numberField" -> ControllerFieldType(
                "TypedTextField<BigDecimal>",
                setOf("io.jmix.flowui.component.textfield.TypedTextField", "java.math.BigDecimal"),
            )
            "dataGrid" -> ControllerFieldType(
                "DataGrid<$entity>",
                setOf("io.jmix.flowui.component.grid.DataGrid") +
                    listOfNotNull(entityImport.takeIf(String::isNotBlank)),
            )
            else -> ControllerFieldType(
                "Component",
                setOf("com.vaadin.flow.component.Component"),
            )
        }
    }

    private fun rejected(code: String, message: String, path: String): ControllerChangeProposal =
        ControllerChangeProposal(false, null, listOf(WorkspaceChangeIssue(code, message, path)))

    companion object {
        private const val VIEW_COMPONENT_IMPORT = "io.jmix.flowui.view.ViewComponent"
        private const val SUBSCRIBE_IMPORT = "io.jmix.flowui.view.Subscribe"
        private const val INSTALL_IMPORT = "io.jmix.flowui.view.Install"
        private const val TARGET_IMPORT = "io.jmix.flowui.view.Target"
        private const val AUTOWIRED_IMPORT = "org.springframework.beans.factory.annotation.Autowired"
        private const val MAX_CONTROLLER_BYTES = 2L * 1024 * 1024
        private const val MAX_REPOSITORY_BYTES = 2L * 1024 * 1024
        private val REPOSITORY_HANDLER_KINDS = setOf(
            FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE,
            FlowUiControllerHandlerKind.DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE,
        )
        private val JAVA_QUALIFIED_NAME = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
        private val PACKAGE_DECLARATION = Regex(
            """(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;?""",
        )
        private val IMPORT_DECLARATION = Regex(
            """(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$*][\w$*]*)*)\s*;?""",
        )
        private val KOTLIN_DECLARED_TYPE = Regex(
            """:\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\??""",
        )
        private val KNOWN_REPOSITORY_BASES = setOf(
            "JmixDataRepository",
            "PagingAndSortingRepository",
            "ListPagingAndSortingRepository",
            "CrudRepository",
            "ListCrudRepository",
            "Repository",
        )

        fun getInstance(project: Project): FlowUiControllerChangeService =
            project.getService(FlowUiControllerChangeService::class.java)
    }
}

private enum class RepositoryInvocation(
    val presentableSignature: String,
) {
    LOAD_PAGE_WITH_CONTEXT("findAll(Pageable, JmixDataRepositoryContext)"),
    SAVE_ONE("save(entity)"),
    ;

    fun matches(method: RepositoryMethod): Boolean {
        val parameterTypes = method.parameters.map { parameter ->
            parameter.type.removeSuffix("?").substringAfterLast('.').substringBefore('<')
        }
        return when (this) {
            LOAD_PAGE_WITH_CONTEXT ->
                method.name == "findAll" &&
                    parameterTypes == listOf("Pageable", "JmixDataRepositoryContext")
            SAVE_ONE -> method.name == "save" && parameterTypes.size == 1
        }
    }

    fun matches(method: PsiMethod): Boolean {
        val parameterTypes = method.parameterList.parameters.map { parameter ->
            parameter.type.presentableText.substringAfterLast('.').substringBefore('<')
        }
        return when (this) {
            LOAD_PAGE_WITH_CONTEXT ->
                method.name == "findAll" &&
                    parameterTypes == listOf("Pageable", "JmixDataRepositoryContext")
            SAVE_ONE -> method.name == "save" && parameterTypes.size == 1
        }
    }
}

private data class RepositoryConstraintEvaluation(
    val enabled: Boolean?,
    val reason: String,
)

data class FlowUiControllerInjectionRequest(
    val controllerLocator: SourceLocator,
    val componentId: String,
    val componentTag: String,
    val entityClass: String? = null,
)

data class FlowUiControllerInjectionApplyRequest(
    val change: FlowUiControllerInjectionRequest,
    val expectedPlanDigest: String,
)

enum class FlowUiControllerHandlerKind {
    VIEW_INIT,
    VIEW_BEFORE_SHOW,
    VIEW_READY,
    VIEW_ATTACH,
    VIEW_BEFORE_CLOSE,
    VIEW_AFTER_CLOSE,
    VIEW_DETACH,
    VIEW_QUERY_PARAMETERS_CHANGE,
    BUTTON_CLICK,
    COMPONENT_TYPED_VALUE_CHANGE,
    COMPONENT_VALUE_CHANGE,
    ACTION_PERFORMED,
    COLLECTION_LOADER_PRE_LOAD,
    COLLECTION_LOADER_POST_LOAD,
    COLLECTION_LOADER_LOAD_DELEGATE,
    DATA_CONTEXT_REPOSITORY_SAVE_DELEGATE,
    COMPONENT_VALIDATOR,
}

data class FlowUiControllerHandlerRequest(
    val controllerLocator: SourceLocator,
    val kind: FlowUiControllerHandlerKind,
    val componentId: String? = null,
    val componentTag: String? = null,
    val targetId: String? = null,
    val entityClass: String? = null,
    val repositoryLocator: SourceLocator? = null,
    val repositoryQualifiedName: String? = null,
)

data class FlowUiControllerHandlerApplyRequest(
    val change: FlowUiControllerHandlerRequest,
    val expectedPlanDigest: String,
)

private data class ControllerFieldType(
    val sourceType: String,
    val imports: Set<String>,
)

private data class ControllerHandlerTemplate(
    val methodName: String,
    val annotation: String,
    val annotationKind: String,
    val target: String?,
    val subject: String?,
    val targetScope: String?,
    val eventSimpleName: String?,
    val visibility: String = "public",
    val returnType: String = "void",
    val parameters: String,
    val imports: Set<String>,
    val bodyLines: List<String> = emptyList(),
    val allowMissingSubject: Boolean = false,
)

private data class RepositoryControllerBinding(
    val fieldName: String,
    val imports: Set<String>,
    val fieldText: String?,
)

private data class LoadedRepositoryBinding(
    val binding: RepositoryControllerBinding?,
    val issues: List<WorkspaceChangeIssue>,
) {
    companion object {
        fun rejected(code: String, message: String, path: String) = LoadedRepositoryBinding(
            binding = null,
            issues = listOf(WorkspaceChangeIssue(code, message, path)),
        )
    }
}

private data class ControllerChangeProposal(
    val accepted: Boolean,
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
) {
    fun preview(prefix: String, label: String) = WorkspaceChangePreviewResponse(
        accepted = accepted,
        changeSetId = changeSet?.id ?: if (accepted) "$prefix:no-change" else "$prefix:rejected",
        label = changeSet?.label ?: if (accepted) "No controller source change required" else "$label rejected",
        planDigest = null,
        files = emptyList(),
        issues = issues,
    )
}

private data class LoadedJavaController(
    val content: String,
    val fingerprint: String,
    val psiFile: PsiJavaFile?,
    val controllerClass: PsiClass?,
    val issues: List<WorkspaceChangeIssue>,
) {
    companion object {
        fun rejected(issue: WorkspaceChangeIssue) =
            LoadedJavaController("", "", null, null, listOf(issue))
    }
}

private data class LoadedKotlinController(
    val content: String,
    val fingerprint: String,
    val psiClass: PsiNamedElement?,
    val issues: List<WorkspaceChangeIssue>,
) {
    companion object {
        fun rejected(issue: WorkspaceChangeIssue) =
            LoadedKotlinController("", "", null, listOf(issue))
    }
}
