package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
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
        if (request.kind == FlowUiControllerHandlerKind.BUTTON_CLICK &&
            (request.componentId.isNullOrBlank() || request.componentTag?.substringAfter(':') != "button")
        ) {
            return rejected(
                "JVW-CONTROLLER-HANDLER-TARGET-INVALID",
                "A button click handler requires a selected button with an id.",
                request.controllerLocator.relativePath,
            )
        }
        val loaded = loadController(request.controllerLocator)
        val psiFile = loaded.psiFile
            ?: return ControllerChangeProposal(false, null, loaded.issues)
        val controllerClass = loaded.controllerClass
            ?: return ControllerChangeProposal(false, null, loaded.issues)
        val handler = handlerTemplate(request)
        if (!SourceVersion.isIdentifier(handler.methodName) || SourceVersion.isKeyword(handler.methodName)) {
            return rejected(
                "JVW-CONTROLLER-METHOD-NAME-INVALID",
                "The generated handler method name is not a valid Java identifier.",
                request.controllerLocator.relativePath,
            )
        }
        val equivalent = controllerClass.methods.any { method ->
            val subscribe = method.annotations.firstOrNull { it.shortName() == "Subscribe" }
                ?: return@any false
            val target = subscribe.stringAttribute("id")
                ?: subscribe.stringAttribute("value")
            val subject = subscribe.stringAttribute("subject")
            when (request.kind) {
                FlowUiControllerHandlerKind.BUTTON_CLICK ->
                    target == request.componentId &&
                        (subject == null || subject == "clickListener") &&
                        method.parameterList.parameters.any {
                            it.type.canonicalText.endsWith("ClickEvent") ||
                                it.type.canonicalText.contains("ClickEvent<")
                        }
                else ->
                    target.isNullOrBlank() &&
                        method.parameterList.parameters.any {
                            it.type.canonicalText.endsWith(".${handler.eventSimpleName}") ||
                                it.type.canonicalText == handler.eventSimpleName
                        }
            }
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
            append(indent).append("public void ").append(handler.methodName)
                .append("(final ").append(handler.eventType).append(" event) {\n")
            append(indent).append("}\n")
        }
        edits += WorkspaceTextEdit(
            startOffset = rBrace.textRange.startOffset,
            endOffset = rBrace.textRange.startOffset,
            expectedText = "",
            replacement = methodText,
        )
        val identity = "${request.controllerLocator.relativePath}\u0000${request.kind}\u0000${request.componentId}"
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

    private fun handlerTemplate(request: FlowUiControllerHandlerRequest): ControllerHandlerTemplate =
        when (request.kind) {
            FlowUiControllerHandlerKind.VIEW_INIT -> ControllerHandlerTemplate(
                methodName = "onInit",
                annotation = "@Subscribe",
                eventType = "InitEvent",
                eventSimpleName = "InitEvent",
                imports = setOf(SUBSCRIBE_IMPORT, "io.jmix.flowui.view.View.InitEvent"),
            )
            FlowUiControllerHandlerKind.VIEW_BEFORE_SHOW -> ControllerHandlerTemplate(
                methodName = "onBeforeShow",
                annotation = "@Subscribe",
                eventType = "BeforeShowEvent",
                eventSimpleName = "BeforeShowEvent",
                imports = setOf(SUBSCRIBE_IMPORT, "io.jmix.flowui.view.View.BeforeShowEvent"),
            )
            FlowUiControllerHandlerKind.VIEW_READY -> ControllerHandlerTemplate(
                methodName = "onReady",
                annotation = "@Subscribe",
                eventType = "ReadyEvent",
                eventSimpleName = "ReadyEvent",
                imports = setOf(SUBSCRIBE_IMPORT, "io.jmix.flowui.view.View.ReadyEvent"),
            )
            FlowUiControllerHandlerKind.BUTTON_CLICK -> {
                val componentId = request.componentId.orEmpty()
                ControllerHandlerTemplate(
                    methodName = "on${componentId.replaceFirstChar { it.uppercaseChar() }}Click",
                    annotation = "@Subscribe(id = \"${escapeJavaString(componentId)}\", subject = \"clickListener\")",
                    eventType = "ClickEvent<JmixButton>",
                    eventSimpleName = "ClickEvent",
                    imports = setOf(
                        SUBSCRIBE_IMPORT,
                        "com.vaadin.flow.component.ClickEvent",
                        "io.jmix.flowui.kit.component.button.JmixButton",
                    ),
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
        val baseDir = project.basePath?.let(LocalFileSystem.getInstance()::findFileByPath)
            ?: return LoadedJavaController.rejected(
                WorkspaceChangeIssue("JVW-CONTROLLER-PROJECT-MISSING", "The project root is unavailable."),
            )
        val file = baseDir.findFileByRelativePath(validated.relativePath)
            ?: return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-SOURCE-MISSING",
                    "The connected controller no longer exists.",
                    validated.relativePath,
                ),
            )
        if (file.isDirectory || !VfsUtilCore.isAncestor(baseDir, file, false)) {
            return LoadedJavaController.rejected(
                WorkspaceChangeIssue(
                    "JVW-CONTROLLER-PATH-REJECTED",
                    "The controller is outside the open project.",
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
    BUTTON_CLICK,
}

data class FlowUiControllerHandlerRequest(
    val controllerLocator: SourceLocator,
    val kind: FlowUiControllerHandlerKind,
    val componentId: String? = null,
    val componentTag: String? = null,
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
    val eventType: String,
    val eventSimpleName: String,
    val imports: Set<String>,
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
