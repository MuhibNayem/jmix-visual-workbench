package org.jmixworkbench.services

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.ide.jmixJavaFlowUiControllerIssues

object FlowUiControllerPsiReader {
    fun read(
        project: Project,
        contextArtifacts: List<ArtifactSnapshot>,
    ): FlowUiControllerWorkspaceSnapshot? {
        val controllerArtifact = contextArtifacts.firstOrNull { it.kind == ArtifactKind.VIEW_CONTROLLER }
            ?: return null
        val locator = controllerArtifact.sourceLocator
        if (!locator.relativePath.endsWith(".java", ignoreCase = true)) {
            return FlowUiControllerWorkspaceSnapshot(
                relativePath = locator.relativePath,
                revisionFingerprint = locator.revisionFingerprint,
                className = controllerArtifact.displayName,
                language = locator.relativePath.substringAfterLast('.', "unknown"),
                psiSupported = false,
                injections = emptyList(),
                handlers = emptyList(),
                message = "Controller symbols are indexed, but PSI mutation is currently enabled only for Java controllers.",
            )
        }
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath)
            ?: return unavailable(controllerArtifact, "The connected view controller no longer exists.")
        val file = resolved.file
        if (file.isDirectory || !VfsUtilCore.isAncestor(resolved.root, file, false)) {
            return unavailable(
                controllerArtifact,
                "The connected view controller is outside the registered project content roots.",
            )
        }
        val currentText = runCatching {
            String(file.contentsToByteArray(false), file.charset)
        }.getOrElse {
            return unavailable(controllerArtifact, "The connected view controller cannot be read.")
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(currentText)
        if (fingerprint != locator.revisionFingerprint) {
            return unavailable(controllerArtifact, "The controller changed after the application graph snapshot. Refresh before editing.")
        }
        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile
            ?: return unavailable(controllerArtifact, "IntelliJ could not build a Java PSI model for the controller.")
        val controllerClass = psiFile.classes.firstOrNull(::isViewController)
            ?: psiFile.classes.firstOrNull()
            ?: return unavailable(controllerArtifact, "No controller class was found in the connected Java source.")
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return unavailable(controllerArtifact, "The controller document is unavailable.")
        val semanticIssues = jmixJavaFlowUiControllerIssues(controllerClass)

        val injections = controllerClass.fields.mapNotNull { field ->
            val annotation = field.annotations.firstOrNull { it.shortName() == "ViewComponent" }
                ?: return@mapNotNull null
            val componentId = annotation.stringAttribute("value").orEmpty().ifBlank { field.name }
            FlowUiControllerInjectionSnapshot(
                fieldName = field.name,
                componentId = componentId,
                type = field.type.canonicalText,
                visibility = field.modifierList?.text
                    ?.substringBefore(field.typeElement?.text.orEmpty())
                    ?.replace(annotation.text, "")
                    ?.trim()
                    ?.ifBlank { null },
                sourceLocator = locatorFor(
                    locator = locator,
                    document = document,
                    offset = field.textOffset,
                    symbol = "${controllerClass.qualifiedName}.${field.name}",
                    fingerprint = fingerprint,
                ),
                issues = semanticIssues[field]
                    .orEmpty()
                    .map(FlowUiControllerIssueSnapshot::from),
            )
        }.distinctBy(FlowUiControllerInjectionSnapshot::fieldName)

        val handlers = controllerClass.methods.mapNotNull { method ->
            val annotation = method.annotations.firstOrNull {
                it.shortName() in CONTROLLER_HANDLER_ANNOTATIONS
            } ?: return@mapNotNull null
            val kind = annotation.shortName()
            val target = when (kind) {
                "Install", "Supply" -> annotation.stringAttribute("to")
                else -> annotation.stringAttribute("value")
                    ?: annotation.stringAttribute("id")
            }
            FlowUiControllerHandlerSnapshot(
                methodName = method.name,
                kind = kind,
                target = target,
                subject = annotation.stringAttribute("subject"),
                targetScope = annotation.attributeText("target"),
                returnType = method.returnType?.canonicalText,
                parameterTypes = method.parameterList.parameters.map { it.type.canonicalText },
                sourceLocator = locatorFor(
                    locator = locator,
                    document = document,
                    offset = method.textOffset,
                    symbol = "${controllerClass.qualifiedName}#${method.name}",
                    fingerprint = fingerprint,
                ),
                issues = semanticIssues[method]
                    .orEmpty()
                    .map(FlowUiControllerIssueSnapshot::from),
            )
        }.distinctBy { "${it.kind}:${it.methodName}:${it.target}:${it.subject}" }

        return FlowUiControllerWorkspaceSnapshot(
            relativePath = locator.relativePath,
            revisionFingerprint = fingerprint,
            className = controllerClass.qualifiedName ?: controllerClass.name.orEmpty(),
            language = "java",
            psiSupported = true,
            injections = injections,
            handlers = handlers,
            message = null,
        )
    }

    private fun isViewController(psiClass: PsiClass): Boolean =
        psiClass.annotations.any { it.shortName() == "ViewController" }

    private fun PsiAnnotation.shortName(): String =
        qualifiedName?.substringAfterLast('.') ?: nameReferenceElement?.referenceName.orEmpty()

    private fun PsiAnnotation.stringAttribute(name: String): String? =
        findDeclaredAttributeValue(name)?.text
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf(String::isNotBlank)

    private fun PsiAnnotation.attributeText(name: String): String? =
        findDeclaredAttributeValue(name)?.text?.trim()?.takeIf(String::isNotBlank)

    private fun locatorFor(
        locator: SourceLocator,
        document: Document,
        offset: Int,
        symbol: String,
        fingerprint: String,
    ): SourceLocator {
        val safeOffset = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(safeOffset)
        return SourceLocator(
            relativePath = locator.relativePath,
            symbol = symbol,
            line = line + 1,
            column = safeOffset - document.getLineStartOffset(line) + 1,
            revisionFingerprint = fingerprint,
        )
    }

    private fun unavailable(artifact: ArtifactSnapshot, message: String) =
        FlowUiControllerWorkspaceSnapshot(
            relativePath = artifact.sourceLocator.relativePath,
            revisionFingerprint = artifact.sourceLocator.revisionFingerprint,
            className = artifact.displayName,
            language = "java",
            psiSupported = false,
            injections = emptyList(),
            handlers = emptyList(),
            message = message,
        )

    private val CONTROLLER_HANDLER_ANNOTATIONS = setOf("Subscribe", "Install", "Supply")
}

data class FlowUiControllerInjectionSnapshot(
    val fieldName: String,
    val componentId: String,
    val type: String,
    val visibility: String?,
    val sourceLocator: SourceLocator,
    val issues: List<FlowUiControllerIssueSnapshot> = emptyList(),
)

data class FlowUiControllerHandlerSnapshot(
    val methodName: String,
    val kind: String,
    val target: String?,
    val subject: String?,
    val targetScope: String?,
    val returnType: String?,
    val parameterTypes: List<String>,
    val sourceLocator: SourceLocator,
    val issues: List<FlowUiControllerIssueSnapshot> = emptyList(),
)

data class FlowUiControllerIssueSnapshot(
    val code: String,
    val message: String,
    val severity: String,
) {
    companion object {
        internal fun from(
            issue: org.jmixworkbench.ide.JmixFlowUiControllerIssue,
        ): FlowUiControllerIssueSnapshot =
            FlowUiControllerIssueSnapshot(
                code = issue.code,
                message = issue.message,
                severity = if (
                    issue.severity == ProblemHighlightType.GENERIC_ERROR_OR_WARNING ||
                    issue.severity == ProblemHighlightType.WEAK_WARNING
                ) {
                    "WARNING"
                } else {
                    "ERROR"
                },
            )
    }
}

data class FlowUiControllerWorkspaceSnapshot(
    val relativePath: String,
    val revisionFingerprint: String,
    val className: String,
    val language: String,
    val psiSupported: Boolean,
    val injections: List<FlowUiControllerInjectionSnapshot>,
    val handlers: List<FlowUiControllerHandlerSnapshot>,
    val message: String?,
)
