package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.ProjectScope
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.discovery.navigation.SourceNavigationPolicy
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
                if (request.kind == FlowUiControllerHandlerKind.COLLECTION_LOADER_LOAD_DELEGATE) {
                    return rejected(
                        "JVW-CONTROLLER-DELEGATE-BODY-REQUIRED",
                        "A load delegate changes runtime data semantics and requires a complete typed implementation; placeholder delegates are never generated.",
                        request.controllerLocator.relativePath,
                    )
                }
            }
            else -> Unit
        }
        val loaded = loadController(request.controllerLocator)
        val psiFile = loaded.psiFile
            ?: return ControllerChangeProposal(false, null, loaded.issues)
        val controllerClass = loaded.controllerClass
            ?: return ControllerChangeProposal(false, null, loaded.issues)
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
        val handler = handlerTemplate(request, subscribeSubjectSupported)
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

    private fun handlerTemplate(
        request: FlowUiControllerHandlerRequest,
        subscribeSubjectSupported: Boolean,
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
                ControllerHandlerTemplate(
                    methodName = "${lowercaseStem(loaderId)}LoadDelegate",
                    annotation = "@Install(to = \"${escapeJavaString(loaderId)}\", target = Target.DATA_LOADER)",
                    annotationKind = "Install",
                    target = loaderId,
                    subject = null,
                    targetScope = "Target.DATA_LOADER",
                    eventSimpleName = null,
                    visibility = "private",
                    returnType = "List<${entityType.sourceType}>",
                    parameters = "final LoadContext<${entityType.sourceType}> loadContext",
                    imports = setOf(
                        INSTALL_IMPORT,
                        TARGET_IMPORT,
                        "io.jmix.core.LoadContext",
                        "java.util.List",
                    ) + entityType.imports,
                    bodyLines = listOf(
                        "throw new IllegalStateException(\"JVW invariant violation: unvalidated load delegate\");",
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
        private const val MAX_CONTROLLER_BYTES = 2L * 1024 * 1024
        private val JAVA_QUALIFIED_NAME = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*""")

        fun getInstance(project: Project): FlowUiControllerChangeService =
            project.getService(FlowUiControllerChangeService::class.java)
    }
}

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
    COMPONENT_VALIDATOR,
}

data class FlowUiControllerHandlerRequest(
    val controllerLocator: SourceLocator,
    val kind: FlowUiControllerHandlerKind,
    val componentId: String? = null,
    val componentTag: String? = null,
    val targetId: String? = null,
    val entityClass: String? = null,
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
