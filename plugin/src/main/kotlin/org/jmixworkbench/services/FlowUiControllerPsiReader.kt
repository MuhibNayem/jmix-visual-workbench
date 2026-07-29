package org.jmixworkbench.services

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.ide.jmixJavaFlowUiControllerIssues
import org.jmixworkbench.ide.jmixKotlinFlowUiControllerIssues

object FlowUiControllerPsiReader {
    fun read(
        project: Project,
        contextArtifacts: List<ArtifactSnapshot>,
    ): FlowUiControllerWorkspaceSnapshot? {
        val controllerArtifact = contextArtifacts.firstOrNull { it.kind == ArtifactKind.VIEW_CONTROLLER }
            ?: return null
        val locator = controllerArtifact.sourceLocator
        val isJava = locator.relativePath.endsWith(".java", ignoreCase = true)
        val isKotlin = locator.relativePath.endsWith(".kt", ignoreCase = true)
        if (!isJava && !isKotlin) {
            return unavailable(
                controllerArtifact,
                "Controller symbols are indexed, but this source language is read-only.",
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
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return unavailable(
                controllerArtifact,
                "IntelliJ could not build a PSI model for the controller.",
            )
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return unavailable(controllerArtifact, "The controller document is unavailable.")
        if (isKotlin) {
            return readKotlin(
                controllerArtifact = controllerArtifact,
                psiFile = psiFile,
                document = document,
                fingerprint = fingerprint,
            )
        }
        val javaFile = psiFile as? PsiJavaFile
            ?: return unavailable(controllerArtifact, "IntelliJ could not build a Java PSI model for the controller.")
        val controllerClass = javaFile.classes.firstOrNull(::isViewController)
            ?: javaFile.classes.firstOrNull()
            ?: return unavailable(controllerArtifact, "No controller class was found in the connected Java source.")
        val semanticIssues = jmixJavaFlowUiControllerIssues(controllerClass)

        val fieldInjections = controllerClass.fields.mapNotNull { field ->
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
        }
        val methodInjections = controllerClass.methods.mapNotNull { method ->
            val annotation = method.annotations.firstOrNull {
                it.shortName() == "ViewComponent"
            } ?: return@mapNotNull null
            val parameter = method.parameterList.parameters.singleOrNull()
            val inferredName = method.name
                .removePrefix("set")
                .replaceFirstChar(Char::lowercaseChar)
            val componentId = annotation.stringAttribute("value")
                .orEmpty()
                .ifBlank { inferredName }
            FlowUiControllerInjectionSnapshot(
                fieldName = "${method.name}()",
                componentId = componentId,
                type = parameter?.type?.canonicalText ?: "invalid signature",
                visibility = method.modifierList.text
                    .substringBefore(method.returnTypeElement?.text.orEmpty())
                    .replace(annotation.text, "")
                    .trim()
                    .ifBlank { null },
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
        }
        val injections = (fieldInjections + methodInjections)
            .distinctBy(FlowUiControllerInjectionSnapshot::fieldName)

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

    private fun readKotlin(
        controllerArtifact: ArtifactSnapshot,
        psiFile: com.intellij.psi.PsiFile,
        document: Document,
        fingerprint: String,
    ): FlowUiControllerWorkspaceSnapshot {
        val locator = controllerArtifact.sourceLocator
        val controller = PsiTreeUtil.findChildrenOfType(
            psiFile,
            PsiNamedElement::class.java,
        ).firstOrNull { candidate ->
            candidate.javaClass.simpleName in setOf(
                "KtClass",
                "KtObjectDeclaration",
            ) && KOTLIN_CONTROLLER_ANNOTATION.containsMatchIn(
                candidate.text.substringBefore('{'),
            )
        } ?: return unavailable(
            controllerArtifact,
            "No Jmix Kotlin view or fragment controller was found.",
        )
        val semanticIssues = jmixKotlinFlowUiControllerIssues(controller)
        val declarations = PsiTreeUtil.findChildrenOfType(
            controller,
            PsiNamedElement::class.java,
        ).filter { declaration ->
            declaration.nearestKotlinController() === controller
        }

        val propertyInjections = declarations.asSequence()
            .filter { it.javaClass.simpleName == "KtProperty" }
            .mapNotNull { property ->
                val annotation = KOTLIN_VIEW_COMPONENT.find(
                    property.text.substringBeforeKotlinDeclaration(),
                ) ?: return@mapNotNull null
                val name = property.name?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val arguments = annotation.groupValues.getOrNull(1).orEmpty()
                val componentId = KOTLIN_VALUE_ARGUMENT.find(arguments)
                    ?.groupValues
                    ?.get(1)
                    ?: KOTLIN_POSITIONAL_STRING.find(arguments)
                        ?.groupValues
                        ?.get(1)
                    ?: name
                val type = Regex(
                    """\b(?:val|var)\s+${Regex.escape(name)}\s*:\s*""" +
                        """([A-Za-z_][A-Za-z0-9_$.<>?, ]*)""",
                ).find(property.text)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?: "unknown"
                FlowUiControllerInjectionSnapshot(
                    fieldName = name,
                    componentId = componentId,
                    type = type,
                    visibility = KOTLIN_VISIBILITY.find(property.text)
                        ?.groupValues
                        ?.get(1),
                    sourceLocator = locatorFor(
                        locator = locator,
                        document = document,
                        offset = property.textOffset,
                        symbol = "${controller.name}.$name",
                        fingerprint = fingerprint,
                    ),
                    issues = semanticIssues
                        .filter { issue ->
                            PsiTreeUtil.isAncestor(property, issue.element, false) ||
                                issue.element === property
                        }
                        .map(FlowUiControllerIssueSnapshot::from),
                )
            }
            .toList()
        val functionInjections = declarations.asSequence()
            .filter { it.javaClass.simpleName == "KtNamedFunction" }
            .mapNotNull { function ->
                val annotation = KOTLIN_VIEW_COMPONENT.find(
                    function.text.substringBeforeKotlinFunction(),
                ) ?: return@mapNotNull null
                val name = function.name?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val arguments = annotation.groupValues.getOrNull(1).orEmpty()
                val inferredName = name
                    .removePrefix("set")
                    .replaceFirstChar(Char::lowercaseChar)
                val componentId = KOTLIN_VALUE_ARGUMENT.find(arguments)
                    ?.groupValues
                    ?.get(1)
                    ?: KOTLIN_POSITIONAL_STRING.find(arguments)
                        ?.groupValues
                        ?.get(1)
                    ?: inferredName
                val signature = kotlinFunctionSignature(function.text)
                FlowUiControllerInjectionSnapshot(
                    fieldName = "$name()",
                    componentId = componentId,
                    type = signature.first.singleOrNull()
                        ?: "invalid signature",
                    visibility = KOTLIN_VISIBILITY.find(function.text)
                        ?.groupValues
                        ?.get(1),
                    sourceLocator = locatorFor(
                        locator = locator,
                        document = document,
                        offset = function.textOffset,
                        symbol = "${controller.name}#$name",
                        fingerprint = fingerprint,
                    ),
                    issues = semanticIssues
                        .filter { issue ->
                            PsiTreeUtil.isAncestor(function, issue.element, false) ||
                                issue.element === function
                        }
                        .map(FlowUiControllerIssueSnapshot::from),
                )
            }
            .toList()
        val injections = (propertyInjections + functionInjections)
            .distinctBy(FlowUiControllerInjectionSnapshot::fieldName)

        val handlers = declarations.asSequence()
            .filter { it.javaClass.simpleName == "KtNamedFunction" }
            .mapNotNull { function ->
                val annotation = KOTLIN_HANDLER_ANNOTATION.find(
                    function.text.substringBeforeKotlinFunction(),
                ) ?: return@mapNotNull null
                val name = function.name?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val kind = annotation.groupValues[1]
                val arguments = annotation.groupValues.getOrNull(2).orEmpty()
                val target = if (kind == "Subscribe") {
                    KOTLIN_SUBSCRIBE_TARGET.find(arguments)
                        ?.groupValues
                        ?.get(1)
                        ?: KOTLIN_POSITIONAL_STRING.find(arguments)
                            ?.groupValues
                            ?.get(1)
                } else {
                    KOTLIN_INSTALL_TARGET.find(arguments)
                        ?.groupValues
                        ?.get(1)
                }
                val signature = kotlinFunctionSignature(function.text)
                FlowUiControllerHandlerSnapshot(
                    methodName = name,
                    kind = kind,
                    target = target,
                    subject = KOTLIN_SUBJECT.find(arguments)
                        ?.groupValues
                        ?.get(1),
                    targetScope = KOTLIN_TARGET_SCOPE.find(arguments)
                        ?.groupValues
                        ?.get(1),
                    returnType = signature.second,
                    parameterTypes = signature.first,
                    sourceLocator = locatorFor(
                        locator = locator,
                        document = document,
                        offset = function.textOffset,
                        symbol = "${controller.name}#$name",
                        fingerprint = fingerprint,
                    ),
                    issues = semanticIssues
                        .filter { issue ->
                            PsiTreeUtil.isAncestor(function, issue.element, false) ||
                                issue.element === function
                        }
                        .map(FlowUiControllerIssueSnapshot::from),
                )
            }
            .distinctBy { "${it.kind}:${it.methodName}:${it.target}:${it.subject}" }
            .toList()

        val packageName = Regex("""(?m)^\s*package\s+([\w.]+)""")
            .find(psiFile.text)
            ?.groupValues
            ?.get(1)
        val className = listOfNotNull(
            packageName,
            controller.name,
        ).joinToString(".")
        return FlowUiControllerWorkspaceSnapshot(
            relativePath = locator.relativePath,
            revisionFingerprint = fingerprint,
            className = className.ifBlank { controllerArtifact.displayName },
            language = "kotlin",
            psiSupported = false,
            injections = injections,
            handlers = handlers,
            message = "Native Kotlin references and contract diagnostics are connected. " +
                "Visual controller mutation remains read-only.",
        )
    }

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
    private val KOTLIN_CONTROLLER_ANNOTATION =
        Regex("""@(?:[\w.]+\.)?(?:ViewController|FragmentDescriptor|UiController)\b""")
    private val KOTLIN_VIEW_COMPONENT =
        Regex("""@(?:[\w.]+\.)?ViewComponent(?:\s*\(([^)]*)\))?""")
    private val KOTLIN_HANDLER_ANNOTATION =
        Regex("""@(?:[\w.]+\.)?(Subscribe|Install|Supply)\b(?:\s*\(([\s\S]*?)\))?""")
    private val KOTLIN_VALUE_ARGUMENT =
        Regex("""\bvalue\s*=\s*"([^"$]+)"""")
    private val KOTLIN_POSITIONAL_STRING =
        Regex("""^\s*"([^"$]+)"""")
    private val KOTLIN_SUBSCRIBE_TARGET =
        Regex("""\b(?:id|value)\s*=\s*"([^"$]+)"""")
    private val KOTLIN_INSTALL_TARGET =
        Regex("""\bto\s*=\s*"([^"$]+)"""")
    private val KOTLIN_SUBJECT =
        Regex("""\bsubject\s*=\s*"([^"$]+)"""")
    private val KOTLIN_TARGET_SCOPE =
        Regex("""\btarget\s*=\s*(?:Target\.)?(\w+)""")
    private val KOTLIN_VISIBILITY =
        Regex("""\b(private|protected|public|internal)\b""")
}

private fun PsiElement.nearestKotlinController(): PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName == "KtClass" ||
                it.javaClass.simpleName == "KtObjectDeclaration"
        }

private fun String.substringBeforeKotlinDeclaration(): String {
    val declaration = Regex("""\b(?:val|var)\b""").find(this)
        ?: return this
    return substring(0, declaration.range.first)
}

private fun String.substringBeforeKotlinFunction(): String {
    val declaration = Regex("""\bfun\b""").find(this)
        ?: return this
    return substring(0, declaration.range.first)
}

private fun kotlinFunctionSignature(
    source: String,
): Pair<List<String>, String?> {
    val function = Regex("""\bfun\s+[A-Za-z_][A-Za-z0-9_]*\s*\(""")
        .find(source)
        ?: return emptyList<String>() to null
    val open = source.indexOf('(', function.range.first)
    if (open < 0) return emptyList<String>() to null
    var depth = 0
    var close = -1
    for (index in open until source.length) {
        when (source[index]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) {
                    close = index
                    break
                }
            }
        }
    }
    if (close <= open) return emptyList<String>() to null
    val parameters = splitKotlinParameters(
        source.substring(open + 1, close),
    ).mapNotNull { parameter ->
        parameter.substringAfter(':', "")
            .substringBefore('=')
            .trim()
            .takeIf(String::isNotBlank)
    }
    val tail = source.substring(close + 1)
        .substringBefore('{')
        .substringBefore('=')
    val returnType = Regex(""":\s*([A-Za-z_][A-Za-z0-9_$.<>?, ]*)""")
        .find(tail)
        ?.groupValues
        ?.get(1)
        ?.trim()
    return parameters to returnType
}

private fun splitKotlinParameters(source: String): List<String> {
    if (source.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    var start = 0
    var angle = 0
    var parentheses = 0
    source.forEachIndexed { index, character ->
        when (character) {
            '<' -> angle++
            '>' -> if (angle > 0) angle--
            '(' -> parentheses++
            ')' -> if (parentheses > 0) parentheses--
            ',' -> if (angle == 0 && parentheses == 0) {
                source.substring(start, index)
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(result::add)
                start = index + 1
            }
        }
    }
    source.substring(start)
        .trim()
        .takeIf(String::isNotBlank)
        ?.let(result::add)
    return result
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
