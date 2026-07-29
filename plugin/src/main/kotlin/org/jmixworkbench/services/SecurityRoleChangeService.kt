package org.jmixworkbench.services

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.RoleGenerator
import org.jmixworkbench.model.EntityAttributePolicyAction
import org.jmixworkbench.model.EntityAttributePolicyModel
import org.jmixworkbench.model.EntityPolicyAction
import org.jmixworkbench.model.EntityPolicyModel
import org.jmixworkbench.model.MenuPolicyModel
import org.jmixworkbench.model.RoleModel
import org.jmixworkbench.model.RoleScope
import org.jmixworkbench.model.RowLevelPolicyAction
import org.jmixworkbench.model.RowLevelPolicyModel
import org.jmixworkbench.model.RowLevelPolicyType
import org.jmixworkbench.model.SpecificPolicyModel
import org.jmixworkbench.model.ViewPolicyModel
import com.google.gson.annotations.SerializedName

@Service(Service.Level.PROJECT)
class SecurityRoleChangeService(
    private val project: Project,
) {
    fun destinations(): SecurityRoleDestinationsResponse {
        val graph = runCatching { ApplicationGraphService.getInstance(project).graph() }.getOrNull()
        val fallbackPackage = JmixProjectService.getInstance(project).getConfig()?.basePackage
            ?.takeIf(String::isNotBlank)
            ?: "com.example.app"
        val candidates = graph?.let { indexedGraph ->
            ProjectSourceDestinationService.getInstance(project)
                .productionJava(indexedGraph)
                .map { source ->
                    val defaultPackage = inferBasePackage(
                        moduleId = source.moduleId,
                        sourceRoot = source.sourceRoot,
                        graph = graph,
                        fallbackPackage = fallbackPackage,
                    )
                    val roleCount = graph.artifacts.count { artifact ->
                        artifact.owner.moduleId == source.moduleId &&
                            artifact.kind in ROLE_ARTIFACT_KINDS &&
                            artifact.sourceLocator.relativePath.startsWith("${source.sourceRoot}/")
                    }
                    SecurityRoleDestinationCandidate(
                        snapshot = SecurityRoleDestinationSnapshot(
                            id = destinationId(source.moduleId, source.sourceRoot),
                            moduleId = source.moduleId,
                            sourceRoot = source.sourceRoot,
                            defaultPackage = "$defaultPackage.security",
                            recommended = false,
                        ),
                        existingRoleCount = roleCount,
                        conventionalJavaRoot = source.sourceRoot.endsWith("/src/main/java") ||
                            source.sourceRoot == "src/main/java",
                    )
                }
        }.orEmpty()
            .distinctBy { it.snapshot.id }
        val fallback = if (candidates.isEmpty()) {
            JmixProjectService.getInstance(project).getConfig()?.let { config ->
                val root = config.sourceRoot.trim().trimEnd('/', '\\')
                SecurityRoleDestinationCandidate(
                    snapshot = SecurityRoleDestinationSnapshot(
                        id = destinationId("root", root),
                        moduleId = "root",
                        sourceRoot = root,
                        defaultPackage = "${config.basePackage}.security",
                        recommended = false,
                    ),
                    existingRoleCount = 0,
                    conventionalJavaRoot = true,
                )
            }?.let(::listOf).orEmpty()
        } else {
            candidates
        }
        val sorted = fallback.sortedWith(
            compareByDescending<SecurityRoleDestinationCandidate> { it.existingRoleCount > 0 }
                .thenByDescending { it.existingRoleCount }
                .thenByDescending { it.conventionalJavaRoot }
                .thenBy { it.snapshot.moduleId }
                .thenBy { it.snapshot.sourceRoot },
        )
        val defaultId = sorted.firstOrNull()?.snapshot?.id
        return SecurityRoleDestinationsResponse(
            destinations = sorted.map { candidate ->
                candidate.snapshot.copy(recommended = candidate.snapshot.id == defaultId)
            },
            defaultDestinationId = defaultId,
            issues = if (sorted.isEmpty()) {
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-ROLE-DESTINATION-MISSING",
                        "No production Java source root is available. Import the Gradle modules in IntelliJ and refresh.",
                    ),
                )
            } else {
                emptyList()
            },
        )
    }

    fun previewCreate(request: SecurityRoleCreateRequest): WorkspaceChangePreviewResponse {
        val proposal = proposeCreate(request)
        val changeSet = proposal.changeSet
        if (changeSet == null) {
            return proposal.preview()
        }
        return WorkspaceChangeService.getInstance(project).preview(changeSet)
    }

    fun prepareCreate(request: SecurityRoleCreateApplyRequest): PreparedWorkspaceChange {
        val proposal = proposeCreate(request.change)
        val changeSet = proposal.changeSet
        if (changeSet == null) {
            return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "security-role-create:rejected",
                    label = "Security role creation rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = proposal.issues,
                ),
                baseDir = null,
            )
        }
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    fun previewPolicyAddition(request: SecurityRolePolicyChangeRequest): WorkspaceChangePreviewResponse {
        val proposal = proposePolicyAddition(request)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: proposal.preview("security-role-policy:rejected", "Security policy change rejected")
    }

    fun preparePolicyAddition(request: SecurityRolePolicyChangeApplyRequest): PreparedWorkspaceChange {
        val proposal = proposePolicyAddition(request.change)
        val changeSet = proposal.changeSet
            ?: return rejectedPrepared(
                id = "security-role-policy:rejected",
                label = "Security policy change rejected",
                issues = proposal.issues,
            )
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    fun inspectPolicies(
        request: SecurityRolePolicyInspectionRequest,
    ): SecurityRolePolicyInspectionResponse {
        val loaded = loadRoleSource(request.roleLocator, request.roleClassName)
        val context = loaded.context
            ?: return SecurityRolePolicyInspectionResponse(
                accepted = false,
                policies = emptyList(),
                issues = listOfNotNull(loaded.issue),
            )
        val policies = policyTargets(context)
            .filter { it.method.containingClass == context.roleClass }
            .map { target ->
                val parsed = parsePolicy(target, context)
                SecurityRolePolicyEditorSnapshot(
                    id = target.locator.symbol.orEmpty(),
                    locator = target.locator,
                    type = target.type,
                    methodName = target.method.name,
                    annotationText = target.annotation.text,
                    policy = parsed.policy,
                    editable = parsed.issue == null,
                    editIssue = parsed.issue,
                )
            }
        return SecurityRolePolicyInspectionResponse(
            accepted = true,
            policies = policies,
            issues = emptyList(),
        )
    }

    fun previewPolicyReplacement(
        request: SecurityRolePolicyReplacementRequest,
    ): WorkspaceChangePreviewResponse {
        val proposal = proposePolicyReplacement(request)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: proposal.preview("security-role-policy-replace:rejected", "Security policy replacement rejected")
    }

    fun preparePolicyReplacement(
        request: SecurityRolePolicyReplacementApplyRequest,
    ): PreparedWorkspaceChange {
        val proposal = proposePolicyReplacement(request.change)
        val changeSet = proposal.changeSet
            ?: return rejectedPrepared(
                id = "security-role-policy-replace:rejected",
                label = "Security policy replacement rejected",
                issues = proposal.issues,
            )
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    fun previewPolicyRemoval(
        request: SecurityRolePolicyRemovalRequest,
    ): WorkspaceChangePreviewResponse {
        val proposal = proposePolicyRemoval(request)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: proposal.preview("security-role-policy-remove:rejected", "Security policy removal rejected")
    }

    fun preparePolicyRemoval(
        request: SecurityRolePolicyRemovalApplyRequest,
    ): PreparedWorkspaceChange {
        val proposal = proposePolicyRemoval(request.change)
        val changeSet = proposal.changeSet
            ?: return rejectedPrepared(
                id = "security-role-policy-remove:rejected",
                label = "Security policy removal rejected",
                issues = proposal.issues,
            )
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    /**
     * Builds one source-safe role edit for several existing attribute policies.
     * This is intentionally internal to compound workspace operations: callers
     * must still present the security-expanding change as an explicit,
     * unselected-by-default review target.
     */
    internal fun proposeAttributePropagation(
        request: SecurityRoleAttributePropagationRequest,
    ): SecurityRoleAttributePropagationProposal {
        val loaded = loadRoleSource(request.roleLocator, request.roleClassName)
        val context = loaded.context ?: return SecurityRoleAttributePropagationProposal(
            null,
            listOfNotNull(loaded.issue),
        )
        if (context.scope != RoleScope.RESOURCE) {
            return SecurityRoleAttributePropagationProposal(
                null,
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-ROLE-ATTRIBUTE-PROPAGATION-SCOPE",
                        "Entity attribute grants can be propagated only in a resource role.",
                        request.roleLocator.relativePath,
                    ),
                ),
            )
        }
        val attributeNames = request.attributeNames.map(String::trim).distinct()
        if (
            attributeNames.isEmpty() ||
            attributeNames.size != request.attributeNames.size ||
            attributeNames.any { !JAVA_IDENTIFIER.matches(it) }
        ) {
            return SecurityRoleAttributePropagationProposal(
                null,
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-ROLE-ATTRIBUTE-PROPAGATION-INVALID",
                        "Propagated attribute names must be unique Java identifiers.",
                        request.roleLocator.relativePath,
                    ),
                ),
            )
        }
        if (request.policyLocators.isEmpty()) {
            return SecurityRoleAttributePropagationProposal(
                null,
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-ROLE-ATTRIBUTE-PROPAGATION-EMPTY",
                        "Select at least one exact entity attribute policy.",
                        request.roleLocator.relativePath,
                    ),
                ),
            )
        }
        val edits = mutableListOf<WorkspaceTextEdit>()
        val missingImports = mutableSetOf<String>()
        val resultingAnnotations = mutableSetOf<String>()
        val selectedSymbols = request.policyLocators.mapNotNull(SourceLocator::symbol).toSet()
        val unselectedAnnotationKeys = policyTargets(context)
            .filterNot { it.locator.symbol in selectedSymbols }
            .map { normalizeJavaFragment(it.annotation.text) }
            .toSet()
        request.policyLocators.forEach { locator ->
            val target = findPolicyTarget(context, locator)
                ?: return SecurityRoleAttributePropagationProposal(
                    null,
                    listOf(
                        WorkspaceChangeIssue(
                            "JVW-ROLE-POLICY-TARGET-STALE",
                            "An entity attribute policy no longer matches this role revision.",
                            request.roleLocator.relativePath,
                        ),
                    ),
                )
            if (target.type != SecurityRolePolicyType.ENTITY_ATTRIBUTE) {
                return SecurityRoleAttributePropagationProposal(
                    null,
                    listOf(
                        WorkspaceChangeIssue(
                            "JVW-ROLE-ATTRIBUTE-PROPAGATION-KIND",
                            "Only exact entity attribute policies can receive propagated attributes.",
                            request.roleLocator.relativePath,
                        ),
                    ),
                )
            }
            val parsed = parsePolicy(target, context)
            val current = parsed.policy
                ?: return SecurityRoleAttributePropagationProposal(
                    null,
                    listOf(
                        WorkspaceChangeIssue(
                            "JVW-ROLE-ATTRIBUTE-PROPAGATION-CUSTOM",
                            parsed.issue ?: "The selected attribute policy cannot be represented safely.",
                            request.roleLocator.relativePath,
                        ),
                    ),
                )
            if (current.entityClass != request.entityQualifiedName || current.allowWildcard) {
                return SecurityRoleAttributePropagationProposal(
                    null,
                    listOf(
                        WorkspaceChangeIssue(
                            "JVW-ROLE-ATTRIBUTE-PROPAGATION-ENTITY",
                            "The selected policy is not an exact non-wildcard policy for ${request.entityQualifiedName}.",
                            request.roleLocator.relativePath,
                        ),
                    ),
                )
            }
            val replacement = current.copy(
                attributes = (current.attributes + attributeNames).distinct().sorted().toMutableList(),
            )
            val generatedResult = generatePolicyMethod(
                policy = replacement,
                scope = context.scope,
                packageName = context.psiFile.packageName,
            )
            val generated = generatedResult.policy
                ?: return SecurityRoleAttributePropagationProposal(
                    null,
                    listOfNotNull(generatedResult.issue),
                )
            val adapted = adaptMethodToExistingImports(
                methodText = generated.annotationText,
                requestedImports = generated.imports,
                existingFile = context.psiFile,
            )
            missingImports += adapted.missingImports
            val normalized = normalizeJavaFragment(adapted.methodText)
            if (normalized in unselectedAnnotationKeys || !resultingAnnotations.add(normalized)) {
                return SecurityRoleAttributePropagationProposal(
                    null,
                    listOf(
                        WorkspaceChangeIssue(
                            "JVW-ROLE-POLICY-DUPLICATE",
                            "Propagation would create duplicate entity attribute policies.",
                            request.roleLocator.relativePath,
                        ),
                    ),
                )
            }
            edits += WorkspaceTextEdit(
                startOffset = target.annotation.textRange.startOffset,
                endOffset = target.annotation.textRange.endOffset,
                expectedText = target.annotation.text,
                replacement = adapted.methodText,
            )
        }
        importEdit(context.psiFile, missingImports.toSortedSet())?.let(edits::add)
        val result = applyEdits(context.content, edits)
        javaSyntaxError(context.fileNameWithoutExtension, result)?.let { syntax ->
            return SecurityRoleAttributePropagationProposal(
                null,
                listOf(
                    WorkspaceChangeIssue(
                        "JVW-ROLE-SOURCE-SYNTAX",
                        "Attribute policy propagation would produce invalid Java: ${syntax.errorDescription}",
                        request.roleLocator.relativePath,
                    ),
                ),
            )
        }
        val identity = buildString {
            append(request.roleLocator.relativePath).append('\u0000').append(context.fingerprint)
            request.policyLocators.sortedBy { it.symbol }.forEach {
                append('\u0000').append(it.symbol)
            }
            attributeNames.sorted().forEach { append('\u0000').append(it) }
        }
        return SecurityRoleAttributePropagationProposal(
            WorkspaceChangeSet(
                id = "security-role-attribute-propagation:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Propagate entity attributes in ${context.roleClass.name}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.roleLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = context.fingerprint,
                        edits = edits,
                    ),
                ),
            ),
            emptyList(),
        )
    }

    private fun proposeCreate(request: SecurityRoleCreateRequest): SecurityRoleChangeProposal {
        val availableDestinations = destinations()
        val destination = request.destinationId
            ?.let { requested -> availableDestinations.destinations.find { it.id == requested } }
            ?: availableDestinations.defaultDestinationId
                ?.let { fallback -> availableDestinations.destinations.find { it.id == fallback } }
            ?: return rejected(
                availableDestinations.issues.firstOrNull()?.code ?: "JVW-ROLE-DESTINATION-MISSING",
                availableDestinations.issues.firstOrNull()?.message
                    ?: "No production Java source root is available.",
            )
        val packageName = request.role.packageName?.trim().orEmpty()
            .ifBlank { destination.defaultPackage }
        val content = runCatching {
            RoleGenerator.generate(request.role, packageName)
        }.getOrElse { failure ->
            return rejected(
                code = failure.message?.substringBefore(':')?.takeIf { it.startsWith("JVW-") }
                    ?: "JVW-ROLE-GENERATION-INVALID",
                message = failure.message?.substringAfter(": ", failure.message.orEmpty())
                    ?: "The role definition is invalid.",
            )
        }
        val syntaxError = javaSyntaxError(request.role.className, content)
        if (syntaxError != null) {
            return rejected(
                "JVW-ROLE-SOURCE-SYNTAX",
                "Generated Java is not syntactically valid: ${syntaxError.errorDescription}",
            )
        }
        val relativePath = buildString {
            append(destination.sourceRoot.trimEnd('/', '\\'))
            append('/')
            append(packageName.replace('.', '/'))
            append('/')
            append(request.role.className)
            append(".java")
        }
        val identity = listOf(relativePath, content).joinToString("\u0000")
        return SecurityRoleChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "security-role-create:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Create Jmix role ${request.role.className}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = relativePath,
                        mode = WorkspaceFileChangeMode.CREATE,
                        baseRevisionFingerprint = null,
                        createContent = content,
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun javaSyntaxError(className: String, content: String): PsiErrorElement? {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            "$className.java",
            JavaFileType.INSTANCE,
            content,
        )
        return PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
    }

    private fun proposePolicyAddition(
        request: SecurityRolePolicyChangeRequest,
    ): SecurityRoleChangeProposal {
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(request.roleLocator.relativePath)
            ?: return rejected("JVW-ROLE-SOURCE-MISSING", "The indexed role source no longer exists.")
        val file = resolved.file
        if (
            file.isDirectory ||
            !VfsUtilCore.isAncestor(resolved.root, file, false) ||
            file.extension != "java"
        ) {
            return rejected(
                "JVW-ROLE-SOURCE-INVALID",
                "Existing role edits require a Java source inside a registered project content root.",
            )
        }
        val content = runCatching {
            String(file.contentsToByteArray(false), file.charset)
        }.getOrElse {
            return rejected("JVW-ROLE-SOURCE-UNREADABLE", "The existing role source cannot be read.")
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (request.roleLocator.revisionFingerprint != fingerprint) {
            return rejected(
                "JVW-ROLE-SOURCE-STALE",
                "The role source changed after it was indexed. Refresh security before editing.",
            )
        }
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
            file.name,
            JavaFileType.INSTANCE,
            content,
        ) as? PsiJavaFile
            ?: return rejected("JVW-ROLE-SOURCE-PARSE", "The role source is not a Java compilation unit.")
        PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)?.let { syntax ->
            return rejected(
                "JVW-ROLE-SOURCE-PARSE",
                "The existing role contains a Java syntax error: ${syntax.errorDescription}",
            )
        }
        val roleClass = findRoleClass(psiFile, request.roleClassName)
            ?: return rejected(
                "JVW-ROLE-CLASS-MISSING",
                "The indexed role interface ${request.roleClassName} is no longer present in the source.",
            )
        if (!roleClass.isInterface) {
            return rejected("JVW-ROLE-CLASS-INVALID", "A design-time Jmix role must be a Java interface.")
        }
        val annotationText = roleClass.modifierList?.text.orEmpty()
        val actualScope = when {
            Regex("""@\s*(?:[\w.]+\.)?ResourceRole\b""").containsMatchIn(annotationText) -> RoleScope.RESOURCE
            Regex("""@\s*(?:[\w.]+\.)?RowLevelRole\b""").containsMatchIn(annotationText) -> RoleScope.ROW_LEVEL
            else -> return rejected(
                "JVW-ROLE-ANNOTATION-MISSING",
                "The selected interface has no current @ResourceRole or @RowLevelRole annotation.",
            )
        }
        if (request.policy.type.scope != actualScope) {
            return rejected(
                "JVW-ROLE-POLICY-KIND-MISMATCH",
                "${request.policy.type.displayName} cannot be added to a ${actualScope.name.lowercase()} role.",
            )
        }
        val packageName = psiFile.packageName
        val generatedRole = runCatching {
            request.policy.toSinglePolicyRole(actualScope, packageName)
        }.getOrElse { failure ->
            return rejected(
                "JVW-ROLE-POLICY-INVALID",
                failure.message ?: "The proposed policy is invalid.",
            )
        }
        val generatedSource = runCatching {
            RoleGenerator.generate(generatedRole, packageName)
        }.getOrElse { failure ->
            return rejected(
                failure.message?.substringBefore(':')?.takeIf { it.startsWith("JVW-") }
                    ?: "JVW-ROLE-POLICY-INVALID",
                failure.message?.substringAfter(": ", failure.message.orEmpty())
                    ?: "The proposed policy is invalid.",
            )
        }
        val generatedPsi = PsiFileFactory.getInstance(project).createFileFromText(
            "GeneratedPolicyRole.java",
            JavaFileType.INSTANCE,
            generatedSource,
        ) as PsiJavaFile
        val generatedMethod = generatedPsi.classes.singleOrNull()?.methods?.singleOrNull()
            ?: return rejected(
                "JVW-ROLE-POLICY-GENERATION",
                "The policy generator did not produce exactly one source method.",
            )
        val generatedAnnotation = generatedMethod.modifierList.annotations.singleOrNull()?.text
        if (generatedAnnotation != null) {
            val key = normalizeJavaFragment(generatedAnnotation)
            val duplicate = roleClass.methods
                .flatMap { it.modifierList.annotations.asList() }
                .any { normalizeJavaFragment(it.text) == key }
            if (duplicate) {
                return rejected(
                    "JVW-ROLE-POLICY-DUPLICATE",
                    "An identical ${request.policy.type.displayName} already exists in this role.",
                )
            }
        }
        val imports = generatedPsi.importList?.importStatements.orEmpty()
            .mapNotNull { it.qualifiedName }
        val adapted = adaptMethodToExistingImports(
            methodText = generatedMethod.text,
            requestedImports = imports,
            existingFile = psiFile,
        )
        var methodText = adapted.methodText
        val existingMethodNames = roleClass.methods.mapNotNull { it.name }.toSet()
        val generatedMethodName = generatedMethod.name
        val uniqueMethodName = uniqueExistingMethodName(generatedMethodName, existingMethodNames)
        if (uniqueMethodName != generatedMethodName) {
            methodText = methodText.replaceFirst(
                Regex("""\b${Regex.escape(generatedMethodName)}\s*\("""),
                "$uniqueMethodName(",
            )
        }
        val rightBrace = roleClass.rBrace
            ?: return rejected("JVW-ROLE-SOURCE-PARSE", "The role interface closing brace is missing.")
        val edits = mutableListOf<WorkspaceTextEdit>()
        importEdit(psiFile, adapted.missingImports)?.let(edits::add)
        edits += WorkspaceTextEdit(
            startOffset = rightBrace.textOffset,
            endOffset = rightBrace.textOffset,
            expectedText = "",
            replacement = "\n\n" + methodText.lineSequence()
                .joinToString("\n") { line -> if (line.isBlank()) line else "    $line" } + "\n",
        )
        val result = applyEdits(content, edits)
        javaSyntaxError(file.nameWithoutExtension, result)?.let { syntax ->
            return rejected(
                "JVW-ROLE-SOURCE-SYNTAX",
                "The policy insertion would produce invalid Java: ${syntax.errorDescription}",
            )
        }
        val identity = listOf(
            request.roleLocator.relativePath,
            fingerprint,
            request.policy.type.name,
            result,
        ).joinToString("\u0000")
        return SecurityRoleChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "security-role-policy:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Add ${request.policy.type.displayName} to ${roleClass.name}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.roleLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = fingerprint,
                        edits = edits,
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun proposePolicyReplacement(
        request: SecurityRolePolicyReplacementRequest,
    ): SecurityRoleChangeProposal {
        val loaded = loadRoleSource(request.roleLocator, request.roleClassName)
        val context = loaded.context ?: return rejected(
            loaded.issue?.code ?: "JVW-ROLE-SOURCE-INVALID",
            loaded.issue?.message ?: "The role source could not be loaded.",
        )
        val target = findPolicyTarget(context, request.policyLocator)
            ?: return rejected(
                "JVW-ROLE-POLICY-TARGET-STALE",
                "The selected policy no longer matches this exact role revision. Refresh security and select it again.",
            )
        if (request.replacement.type.scope != context.scope) {
            return rejected(
                "JVW-ROLE-POLICY-KIND-MISMATCH",
                "${request.replacement.type.displayName} cannot be used in a ${context.scope.name.lowercase()} role.",
            )
        }
        val generatedResult = generatePolicyMethod(
            policy = request.replacement,
            scope = context.scope,
            packageName = context.psiFile.packageName,
        )
        val generated = generatedResult.policy ?: return rejected(
            generatedResult.issue?.code ?: "JVW-ROLE-POLICY-INVALID",
            generatedResult.issue?.message ?: "The replacement policy is invalid.",
        )
        val adaptedMethod = adaptMethodToExistingImports(
            methodText = generated.methodText,
            requestedImports = generated.imports,
            existingFile = context.psiFile,
        )
        val adaptedAnnotation = adaptMethodToExistingImports(
            methodText = generated.annotationText,
            requestedImports = generated.imports,
            existingFile = context.psiFile,
        )
        val duplicateKey = normalizeJavaFragment(adaptedAnnotation.methodText)
        val duplicate = context.roleClass.methods
            .flatMap { it.modifierList.annotations.asList() }
            .filter { it != target.annotation }
            .any { normalizeJavaFragment(it.text) == duplicateKey }
        if (duplicate) {
            return rejected(
                "JVW-ROLE-POLICY-DUPLICATE",
                "An identical ${request.replacement.type.displayName} already exists in this role.",
            )
        }

        val replacesPredicateMethod =
            target.type == SecurityRolePolicyType.PREDICATE_ROW ||
                request.replacement.type == SecurityRolePolicyType.PREDICATE_ROW
        val edits = mutableListOf<WorkspaceTextEdit>()
        importEdit(context.psiFile, adaptedMethod.missingImports)?.let(edits::add)
        if (replacesPredicateMethod) {
            val securityAnnotations = target.method.modifierList.annotations
                .filter { annotationPolicyType(it) != null }
            if (securityAnnotations.size != 1) {
                return rejected(
                    "JVW-ROLE-POLICY-COMPOSITE-METHOD",
                    "Predicate conversion is blocked because this method declares multiple security policies.",
                )
            }
            var replacementMethod = adaptedMethod.methodText
            val generatedName = generated.methodName
            val existingName = target.method.name
            if (generatedName != existingName) {
                replacementMethod = replacementMethod.replaceFirst(
                    Regex("""\b${Regex.escape(generatedName)}\s*\("""),
                    "$existingName(",
                )
            }
            replacementMethod = indentContinuationLines(
                replacementMethod,
                lineIndent(context.content, target.annotation.textRange.startOffset),
            )
            val replacementStart = target.annotation.textRange.startOffset
            edits += WorkspaceTextEdit(
                startOffset = replacementStart,
                endOffset = target.method.textRange.endOffset,
                expectedText = context.content.substring(
                    replacementStart,
                    target.method.textRange.endOffset,
                ),
                replacement = replacementMethod,
            )
        } else {
            edits += WorkspaceTextEdit(
                startOffset = target.annotation.textRange.startOffset,
                endOffset = target.annotation.textRange.endOffset,
                expectedText = target.annotation.text,
                replacement = adaptedAnnotation.methodText,
            )
        }
        val result = applyEdits(context.content, edits)
        if (normalizeJavaFragment(result) == normalizeJavaFragment(context.content)) {
            return rejected("JVW-ROLE-POLICY-NOOP", "The replacement is identical to the current policy.")
        }
        javaSyntaxError(context.fileNameWithoutExtension, result)?.let { syntax ->
            return rejected(
                "JVW-ROLE-SOURCE-SYNTAX",
                "The policy replacement would produce invalid Java: ${syntax.errorDescription}",
            )
        }
        val identity = listOf(
            request.roleLocator.relativePath,
            context.fingerprint,
            target.locator.symbol,
            request.replacement.type.name,
            result,
        ).joinToString("\u0000")
        return SecurityRoleChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "security-role-policy-replace:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Replace ${target.type.displayName} in ${context.roleClass.name}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.roleLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = context.fingerprint,
                        edits = edits,
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun proposePolicyRemoval(
        request: SecurityRolePolicyRemovalRequest,
    ): SecurityRoleChangeProposal {
        val loaded = loadRoleSource(request.roleLocator, request.roleClassName)
        val context = loaded.context ?: return rejected(
            loaded.issue?.code ?: "JVW-ROLE-SOURCE-INVALID",
            loaded.issue?.message ?: "The role source could not be loaded.",
        )
        val target = findPolicyTarget(context, request.policyLocator)
            ?: return rejected(
                "JVW-ROLE-POLICY-TARGET-STALE",
                "The selected policy no longer matches this exact role revision. Refresh security and select it again.",
            )
        val recognizedAnnotations = target.method.modifierList.annotations
            .filter { annotationPolicyType(it) != null }
        val edit = if (
            recognizedAnnotations.size == 1 &&
            target.method.modifierList.annotations.size == 1
        ) {
            val range = expandedMethodDeletionRange(
                context.content,
                target.annotation.textRange.startOffset,
                target.method.textRange.endOffset,
            )
            WorkspaceTextEdit(
                startOffset = range.first,
                endOffset = range.last + 1,
                expectedText = context.content.substring(range.first, range.last + 1),
                replacement = "",
            )
        } else {
            val range = expandedAnnotationDeletionRange(
                context.content,
                target.annotation.textRange.startOffset,
                target.annotation.textRange.endOffset,
            )
            WorkspaceTextEdit(
                startOffset = range.first,
                endOffset = range.last + 1,
                expectedText = context.content.substring(range.first, range.last + 1),
                replacement = "",
            )
        }
        val result = applyEdits(context.content, listOf(edit))
        javaSyntaxError(context.fileNameWithoutExtension, result)?.let { syntax ->
            return rejected(
                "JVW-ROLE-SOURCE-SYNTAX",
                "The policy removal would produce invalid Java: ${syntax.errorDescription}",
            )
        }
        val identity = listOf(
            request.roleLocator.relativePath,
            context.fingerprint,
            target.locator.symbol,
            result,
        ).joinToString("\u0000")
        return SecurityRoleChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "security-role-policy-remove:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Remove ${target.type.displayName} from ${context.roleClass.name}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = request.roleLocator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = context.fingerprint,
                        edits = listOf(edit),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun loadRoleSource(
        locator: SourceLocator,
        roleClassName: String,
    ): RoleSourceLoadResult {
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath)
            ?: return roleLoadIssue("JVW-ROLE-SOURCE-MISSING", "The indexed role source no longer exists.")
        val file = resolved.file
        if (
            file.isDirectory ||
            !VfsUtilCore.isAncestor(resolved.root, file, false) ||
            file.extension != "java"
        ) {
            return roleLoadIssue(
                "JVW-ROLE-SOURCE-INVALID",
                "Existing role edits require a Java source inside a registered project content root.",
            )
        }
        val content = runCatching {
            String(file.contentsToByteArray(false), file.charset)
        }.getOrElse {
            return roleLoadIssue(
                "JVW-ROLE-SOURCE-UNREADABLE",
                "The existing role source cannot be read.",
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (locator.revisionFingerprint != fingerprint) {
            return roleLoadIssue(
                "JVW-ROLE-SOURCE-STALE",
                "The role source changed after it was indexed. Refresh security before editing.",
            )
        }
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
            file.name,
            JavaFileType.INSTANCE,
            content,
        ) as? PsiJavaFile
            ?: return roleLoadIssue(
                "JVW-ROLE-SOURCE-PARSE",
                "The role source is not a Java compilation unit.",
            )
        PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)?.let { syntax ->
            return roleLoadIssue(
                "JVW-ROLE-SOURCE-PARSE",
                "The existing role contains a Java syntax error: ${syntax.errorDescription}",
            )
        }
        val roleClass = findRoleClass(psiFile, roleClassName)
            ?: return roleLoadIssue(
                "JVW-ROLE-CLASS-MISSING",
                "The indexed role interface $roleClassName is no longer present in the source.",
            )
        if (!roleClass.isInterface) {
            return roleLoadIssue(
                "JVW-ROLE-CLASS-INVALID",
                "A design-time Jmix role must be a Java interface.",
            )
        }
        val scope = roleScope(roleClass)
            ?: return roleLoadIssue(
                "JVW-ROLE-ANNOTATION-MISSING",
                "The selected interface has no current @ResourceRole or @RowLevelRole annotation.",
            )
        return RoleSourceLoadResult(
            context = RoleSourceContext(
                content = content,
                fingerprint = fingerprint,
                fileNameWithoutExtension = file.nameWithoutExtension,
                psiFile = psiFile,
                roleClass = roleClass,
                scope = scope,
                relativePath = locator.relativePath,
            ),
            issue = null,
        )
    }

    private fun roleLoadIssue(code: String, message: String): RoleSourceLoadResult =
        RoleSourceLoadResult(
            context = null,
            issue = WorkspaceChangeIssue(code, message),
        )

    private fun roleScope(roleClass: PsiClass): RoleScope? {
        val text = roleClass.modifierList?.text.orEmpty()
        return when {
            Regex("""@\s*(?:[\w.]+\.)?ResourceRole\b""").containsMatchIn(text) -> RoleScope.RESOURCE
            Regex("""@\s*(?:[\w.]+\.)?RowLevelRole\b""").containsMatchIn(text) -> RoleScope.ROW_LEVEL
            else -> null
        }
    }

    private fun policyTargets(context: RoleSourceContext): List<PolicyTarget> =
        PsiTreeUtil.findChildrenOfType(context.psiFile, PsiAnnotation::class.java)
            .mapNotNull { annotation ->
                val type = annotationPolicyType(annotation) ?: return@mapNotNull null
                val method = PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java, false)
                    ?: return@mapNotNull null
                annotation to (type to method)
            }
            .sortedBy { (annotation) -> annotation.textRange.startOffset }
            .mapIndexed { index, (annotation, typeAndMethod) ->
                val (type, method) = typeAndMethod
                val typeName = annotationTypeName(annotation)
                val symbol = "${context.roleClass.qualifiedName ?: context.roleClass.name}#$typeName-${index + 1}"
                PolicyTarget(
                    annotation = annotation,
                    method = method,
                    type = type,
                    locator = sourceLocatorAt(
                        relativePath = context.relativePath,
                        symbol = symbol,
                        fingerprint = context.fingerprint,
                        content = context.content,
                        offset = annotation.textRange.startOffset,
                    ),
                )
            }

    private fun findPolicyTarget(
        context: RoleSourceContext,
        locator: SourceLocator,
    ): PolicyTarget? {
        if (
            locator.relativePath != context.relativePath ||
            locator.revisionFingerprint != context.fingerprint
        ) {
            return null
        }
        val roleName = context.roleClass.qualifiedName ?: context.roleClass.name ?: return null
        val match = Regex(
            """^${Regex.escape(roleName)}#([A-Za-z][A-Za-z0-9]*)-(\d+)$""",
        ).matchEntire(locator.symbol.orEmpty()) ?: return null
        val expectedTypeName = match.groupValues[1]
        val index = match.groupValues[2].toIntOrNull()?.minus(1) ?: return null
        val target = policyTargets(context).getOrNull(index) ?: return null
        return target.takeIf {
            annotationTypeName(it.annotation) == expectedTypeName &&
                it.method.containingClass == context.roleClass
        }
    }

    private fun generatePolicyMethod(
        policy: SecurityRolePolicyModel,
        scope: RoleScope,
        packageName: String,
    ): GeneratedPolicyResult {
        val generatedRole = runCatching {
            policy.toSinglePolicyRole(scope, packageName)
        }.getOrElse { failure ->
            return generatedPolicyIssue(
                "JVW-ROLE-POLICY-INVALID",
                failure.message ?: "The proposed policy is invalid.",
            )
        }
        val generatedSource = runCatching {
            RoleGenerator.generate(generatedRole, packageName)
        }.getOrElse { failure ->
            return generatedPolicyIssue(
                failure.message?.substringBefore(':')?.takeIf { it.startsWith("JVW-") }
                    ?: "JVW-ROLE-POLICY-INVALID",
                failure.message?.substringAfter(": ", failure.message.orEmpty())
                    ?: "The proposed policy is invalid.",
            )
        }
        val generatedPsi = PsiFileFactory.getInstance(project).createFileFromText(
            "GeneratedPolicyRole.java",
            JavaFileType.INSTANCE,
            generatedSource,
        ) as PsiJavaFile
        val method = generatedPsi.classes.singleOrNull()?.methods?.singleOrNull()
            ?: return generatedPolicyIssue(
                "JVW-ROLE-POLICY-GENERATION",
                "The policy generator did not produce exactly one source method.",
            )
        val annotation = method.modifierList.annotations.singleOrNull()
            ?: return generatedPolicyIssue(
                "JVW-ROLE-POLICY-GENERATION",
                "The policy generator did not produce exactly one annotation.",
            )
        return GeneratedPolicyResult(
            policy = GeneratedPolicyMethod(
                methodText = method.text,
                methodName = method.name,
                annotationText = annotation.text,
                imports = generatedPsi.importList?.importStatements.orEmpty()
                    .mapNotNull { it.qualifiedName },
            ),
            issue = null,
        )
    }

    private fun generatedPolicyIssue(code: String, message: String): GeneratedPolicyResult =
        GeneratedPolicyResult(
            policy = null,
            issue = WorkspaceChangeIssue(code, message),
        )

    private fun parsePolicy(
        target: PolicyTarget,
        context: RoleSourceContext,
    ): ParsedEditorPolicy {
        val annotation = target.annotation
        val entityClass = when (target.type) {
            SecurityRolePolicyType.ENTITY,
            SecurityRolePolicyType.ENTITY_ATTRIBUTE,
            SecurityRolePolicyType.JPQL_ROW,
            SecurityRolePolicyType.PREDICATE_ROW,
            -> resolveClassLiteral(
                annotation.findDeclaredAttributeValue("entityClass"),
                context.psiFile,
            )
            else -> null
        }
        if (
            target.type in ENTITY_POLICY_TYPES &&
            entityClass == null
        ) {
            return ParsedEditorPolicy(
                policy = null,
                issue = "Entity class could not be resolved safely. Removal remains available; edit the source directly or add an explicit import.",
            )
        }
        val enumNames = when (target.type) {
            SecurityRolePolicyType.ENTITY ->
                enumNames(annotation.findDeclaredAttributeValue("actions"), ENTITY_ACTION_NAMES)
            SecurityRolePolicyType.PREDICATE_ROW ->
                enumNames(annotation.findDeclaredAttributeValue("actions"), ROW_ACTION_NAMES)
            else -> emptyList()
        }
        val predicate = if (target.type == SecurityRolePolicyType.PREDICATE_ROW) {
            predicateExpression(target.method)
        } else {
            null
        }
        if (target.type == SecurityRolePolicyType.PREDICATE_ROW && predicate == null) {
            return ParsedEditorPolicy(
                policy = null,
                issue = "This predicate contains custom Java statements. It is protected from visual replacement; removal and source navigation remain available.",
            )
        }
        val policy = SecurityRolePolicyModel(
            type = target.type,
            entityClass = entityClass,
            entityActions = enumNames.mapNotNull {
                runCatching { EntityPolicyAction.valueOf(it) }.getOrNull()
            }.filterNot { it == EntityPolicyAction.ALL }.toMutableList(),
            allEntityActions = "ALL" in enumNames,
            attributes = stringValues(annotation.findDeclaredAttributeValue("attributes")).toMutableList(),
            attributeAction = enumNames(
                annotation.findDeclaredAttributeValue("action"),
                ATTRIBUTE_ACTION_NAMES,
            ).firstOrNull()?.let {
                runCatching { EntityAttributePolicyAction.valueOf(it) }.getOrNull()
            } ?: EntityAttributePolicyAction.VIEW,
            resources = when (target.type) {
                SecurityRolePolicyType.MENU ->
                    stringValues(annotation.findDeclaredAttributeValue("menuIds"))
                SecurityRolePolicyType.VIEW ->
                    stringValues(annotation.findDeclaredAttributeValue("viewIds"))
                SecurityRolePolicyType.SPECIFIC ->
                    stringValues(annotation.findDeclaredAttributeValue("resources"))
                else -> emptyList()
            }.toMutableList(),
            rowActions = enumNames.mapNotNull {
                runCatching { RowLevelPolicyAction.valueOf(it) }.getOrNull()
            }.toMutableList(),
            whereClause = constantString(annotation.findDeclaredAttributeValue("where")),
            joinClause = constantString(annotation.findDeclaredAttributeValue("join")),
            predicateExpression = predicate,
            allowWildcard = stringValues(annotation.findDeclaredAttributeValue("attributes")).contains("*") ||
                stringValues(annotation.findDeclaredAttributeValue("menuIds")).contains("*") ||
                stringValues(annotation.findDeclaredAttributeValue("viewIds")).contains("*") ||
                stringValues(annotation.findDeclaredAttributeValue("resources")).contains("*"),
        )
        return ParsedEditorPolicy(policy = policy, issue = null)
    }

    private fun resolveClassLiteral(
        value: PsiAnnotationMemberValue?,
        file: PsiJavaFile,
    ): String? {
        val expression = value?.text?.trim()?.removeSuffix(".class")?.trim()
            ?.takeIf(String::isNotBlank) ?: return null
        if (expression.contains('.') && expression.firstOrNull()?.isLowerCase() == true) {
            return expression
        }
        val firstSegment = expression.substringBefore('.')
        val suffix = expression.removePrefix(firstSegment)
        val imported = file.importList?.importStatements.orEmpty()
            .filterNot { it.isOnDemand }
            .mapNotNull { it.qualifiedName }
            .firstOrNull { it.substringAfterLast('.') == firstSegment }
        if (imported != null) return imported + suffix
        return file.packageName.takeIf(String::isNotBlank)?.let { "$it.$expression" }
    }

    private fun stringValues(value: PsiAnnotationMemberValue?): List<String> {
        if (value == null) return emptyList()
        val direct = (value as? PsiLiteralExpression)?.value as? String
        if (direct != null) return listOf(direct)
        return PsiTreeUtil.findChildrenOfType(value, PsiLiteralExpression::class.java)
            .mapNotNull { it.value as? String }
    }

    private fun constantString(value: PsiAnnotationMemberValue?): String? {
        if (value == null) return null
        return ((value as? PsiLiteralExpression)?.value as? String)
            ?: runCatching {
                JavaPsiFacade.getInstance(project).constantEvaluationHelper
                    .computeConstantExpression(value) as? String
            }.getOrNull()
    }

    private fun enumNames(
        value: PsiAnnotationMemberValue?,
        allowed: Set<String>,
    ): List<String> {
        val text = value?.text.orEmpty()
        return Regex("""\b([A-Z][A-Z0-9_]*)\b""")
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { it in allowed }
            .toList()
            .distinct()
    }

    private fun predicateExpression(method: PsiMethod): String? {
        val returns = PsiTreeUtil.findChildrenOfType(method, PsiReturnStatement::class.java)
        val expression = returns.singleOrNull()?.returnValue as? PsiLambdaExpression ?: return null
        val body = expression.body ?: return null
        return body.text.takeIf { body !is com.intellij.psi.PsiCodeBlock }
    }

    private fun annotationPolicyType(annotation: PsiAnnotation): SecurityRolePolicyType? =
        when (annotationTypeName(annotation)) {
            "EntityPolicy" -> SecurityRolePolicyType.ENTITY
            "EntityAttributePolicy" -> SecurityRolePolicyType.ENTITY_ATTRIBUTE
            "MenuPolicy" -> SecurityRolePolicyType.MENU
            "ViewPolicy" -> SecurityRolePolicyType.VIEW
            "SpecificPolicy" -> SecurityRolePolicyType.SPECIFIC
            "JpqlRowLevelPolicy" -> SecurityRolePolicyType.JPQL_ROW
            "PredicateRowLevelPolicy" -> SecurityRolePolicyType.PREDICATE_ROW
            else -> null
        }

    private fun annotationTypeName(annotation: PsiAnnotation): String =
        annotation.nameReferenceElement?.referenceName.orEmpty()

    private fun sourceLocatorAt(
        relativePath: String,
        symbol: String,
        fingerprint: String,
        content: String,
        offset: Int,
    ): SourceLocator {
        val safeOffset = offset.coerceIn(0, content.length)
        val line = content.take(safeOffset).count { it == '\n' } + 1
        val lineStart = content.lastIndexOf('\n', safeOffset - 1)
            .let { if (it < 0) 0 else it + 1 }
        return SourceLocator(
            relativePath = relativePath,
            symbol = symbol,
            line = line,
            column = safeOffset - lineStart + 1,
            revisionFingerprint = fingerprint,
        )
    }

    private fun lineIndent(content: String, offset: Int): String {
        val lineStart = content.lastIndexOf('\n', offset - 1)
            .let { if (it < 0) 0 else it + 1 }
        return content.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
    }

    private fun indentContinuationLines(value: String, indent: String): String =
        value.lineSequence().mapIndexed { index, line ->
            if (index == 0 || line.isBlank()) line else indent + line
        }.joinToString("\n")

    private fun expandedMethodDeletionRange(content: String, start: Int, end: Int): IntRange {
        val lineStart = content.lastIndexOf('\n', start - 1)
            .let { if (it < 0) 0 else it + 1 }
        val safeStart = if (content.substring(lineStart, start).isBlank()) lineStart else start
        var safeEnd = end
        if (safeEnd < content.length && content[safeEnd] == '\r') safeEnd++
        if (safeEnd < content.length && content[safeEnd] == '\n') safeEnd++
        return safeStart until safeEnd
    }

    private fun expandedAnnotationDeletionRange(content: String, start: Int, end: Int): IntRange {
        val lineStart = content.lastIndexOf('\n', start - 1)
            .let { if (it < 0) 0 else it + 1 }
        val lineEnd = content.indexOf('\n', end).let { if (it < 0) content.length else it + 1 }
        val before = content.substring(lineStart, start)
        val after = content.substring(end, lineEnd).trimEnd('\r', '\n')
        return if (before.isBlank() && after.isBlank()) {
            lineStart until lineEnd
        } else {
            start until end
        }
    }

    private fun inferBasePackage(
        moduleId: String,
        sourceRoot: String,
        graph: ApplicationGraphResponse?,
        fallbackPackage: String,
    ): String {
        val packages = graph?.artifacts.orEmpty()
            .asSequence()
            .filter { artifact ->
                artifact.owner.moduleId == moduleId &&
                    artifact.sourceLocator.relativePath.startsWith("$sourceRoot/") &&
                    artifact.sourceLocator.relativePath.endsWith(".java")
            }
            .mapNotNull { artifact ->
                val symbol = artifact.semanticKey
                    .substringBefore('#')
                    .substringBefore("::")
                    .substringBefore('(')
                symbol.takeIf(QUALIFIED_TYPE_PATTERN::matches)?.substringBeforeLast('.')
            }
            .distinct()
            .toList()
        if (packages.isEmpty()) return fallbackPackage
        val common = packages
            .map { it.split('.') }
            .reduce { left, right ->
                left.zip(right).takeWhile { (a, b) -> a == b }.map { it.first }
            }
            .joinToString(".")
        if (common.count { it == '.' } >= 1) return common
        val only = packages.first()
        return only.substringBeforeLast('.').takeIf(String::isNotBlank) ?: only
    }

    private fun destinationId(moduleId: String, sourceRoot: String): String =
        "security-role-destination:" +
            CanonicalDiscoveryJson.sha256("$moduleId\u0000$sourceRoot").take(24)

    private fun findRoleClass(file: PsiJavaFile, qualifiedName: String): PsiClass? =
        PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java).firstOrNull { candidate ->
            candidate.qualifiedName == qualifiedName ||
                candidate.name == qualifiedName.substringAfterLast('.')
        }

    private fun adaptMethodToExistingImports(
        methodText: String,
        requestedImports: List<String>,
        existingFile: PsiJavaFile,
    ): AdaptedMethod {
        val importStatements = existingFile.importList?.importStatements.orEmpty()
        val explicitImports = importStatements
            .filterNot { it.isOnDemand }
            .mapNotNull { it.qualifiedName }
        val wildcardPackages = importStatements
            .filter { it.isOnDemand }
            .mapNotNull { it.qualifiedName }
            .toSet()
        val declaredNames = PsiTreeUtil.findChildrenOfType(existingFile, PsiClass::class.java)
            .mapNotNull(PsiClass::getName)
            .toSet()
        var adaptedText = methodText
        val missing = linkedSetOf<String>()
        requestedImports.distinct().sorted().forEach { qualifiedName ->
            val packageName = qualifiedName.substringBeforeLast('.', "")
            val simpleName = qualifiedName.substringAfterLast('.')
            if (packageName == existingFile.packageName || packageName == "java.lang") {
                return@forEach
            }
            val conflictingImport = explicitImports.any {
                it.substringAfterLast('.') == simpleName && it != qualifiedName
            }
            val conflictsWithDeclaredType = simpleName in declaredNames
            if (conflictingImport || conflictsWithDeclaredType) {
                adaptedText = replaceSimpleType(adaptedText, simpleName, qualifiedName)
            } else {
                val alreadyAvailable = qualifiedName in explicitImports || packageName in wildcardPackages
                if (!alreadyAvailable) missing += qualifiedName
            }
        }
        return AdaptedMethod(adaptedText, missing)
    }

    private fun replaceSimpleType(source: String, simpleName: String, qualifiedName: String): String =
        source.replace(
            Regex("""(?<![\w$.])${Regex.escape(simpleName)}(?![\w$])"""),
            qualifiedName,
        )

    private fun importEdit(file: PsiJavaFile, imports: Set<String>): WorkspaceTextEdit? {
        if (imports.isEmpty()) return null
        val lines = imports.sorted().joinToString("\n") { "import $it;" }
        val importList = file.importList
        val normalImports = importList?.importStatements.orEmpty()
        val staticImports = importList?.importStaticStatements.orEmpty()
        val packageStatement = file.packageStatement
        val (offset, replacement) = when {
            normalImports.isNotEmpty() -> {
                normalImports.last().textRange.endOffset to "\n$lines"
            }
            staticImports.isNotEmpty() -> {
                staticImports.first().textRange.startOffset to "$lines\n"
            }
            packageStatement != null -> {
                packageStatement.textRange.endOffset to "\n\n$lines"
            }
            else -> 0 to "$lines\n\n"
        }
        return WorkspaceTextEdit(
            startOffset = offset,
            endOffset = offset,
            expectedText = "",
            replacement = replacement,
        )
    }

    private fun uniqueExistingMethodName(seed: String, existing: Set<String>): String {
        if (seed !in existing) return seed
        var suffix = 2
        var candidate = "$seed$suffix"
        while (candidate in existing) {
            candidate = "$seed${++suffix}"
        }
        return candidate
    }

    private fun normalizeJavaFragment(value: String): String =
        value.replace(Regex("""\s+"""), "")

    private fun applyEdits(content: String, edits: List<WorkspaceTextEdit>): String =
        StringBuilder(content).apply {
            edits.sortedByDescending(WorkspaceTextEdit::startOffset).forEach { edit ->
                replace(edit.startOffset, edit.endOffset, edit.replacement)
            }
        }.toString()

    private fun rejectedPrepared(
        id: String,
        label: String,
        issues: List<WorkspaceChangeIssue>,
    ): PreparedWorkspaceChange =
        PreparedWorkspaceChange(
            plan = WorkspaceChangePlan(
                accepted = false,
                changeSetId = id,
                label = label,
                planDigest = null,
                files = emptyList(),
                issues = issues,
            ),
            baseDir = null,
        )

    private fun rejected(code: String, message: String): SecurityRoleChangeProposal =
        SecurityRoleChangeProposal(
            changeSet = null,
            issues = listOf(WorkspaceChangeIssue(code, message)),
        )

    companion object {
        private val JAVA_IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")

        fun getInstance(project: Project): SecurityRoleChangeService =
            project.getService(SecurityRoleChangeService::class.java)
    }
}

data class SecurityRoleCreateRequest(
    val role: RoleModel,
    val destinationId: String? = null,
)

data class SecurityRoleCreateApplyRequest(
    val change: SecurityRoleCreateRequest,
    val expectedPlanDigest: String,
)

data class SecurityRolePolicyChangeRequest(
    val roleLocator: SourceLocator,
    val roleClassName: String,
    val policy: SecurityRolePolicyModel,
)

data class SecurityRolePolicyChangeApplyRequest(
    val change: SecurityRolePolicyChangeRequest,
    val expectedPlanDigest: String,
)

data class SecurityRolePolicyInspectionRequest(
    val roleLocator: SourceLocator,
    val roleClassName: String,
)

data class SecurityRolePolicyInspectionResponse(
    val accepted: Boolean,
    val policies: List<SecurityRolePolicyEditorSnapshot>,
    val issues: List<WorkspaceChangeIssue>,
)

data class SecurityRolePolicyEditorSnapshot(
    val id: String,
    val locator: SourceLocator,
    val type: SecurityRolePolicyType,
    val methodName: String,
    val annotationText: String,
    val policy: SecurityRolePolicyModel?,
    val editable: Boolean,
    val editIssue: String?,
)

data class SecurityRolePolicyReplacementRequest(
    val roleLocator: SourceLocator,
    val roleClassName: String,
    val policyLocator: SourceLocator,
    val replacement: SecurityRolePolicyModel,
)

data class SecurityRolePolicyReplacementApplyRequest(
    val change: SecurityRolePolicyReplacementRequest,
    val expectedPlanDigest: String,
)

data class SecurityRolePolicyRemovalRequest(
    val roleLocator: SourceLocator,
    val roleClassName: String,
    val policyLocator: SourceLocator,
)

data class SecurityRolePolicyRemovalApplyRequest(
    val change: SecurityRolePolicyRemovalRequest,
    val expectedPlanDigest: String,
)

data class SecurityRoleAttributePropagationRequest(
    val roleLocator: SourceLocator,
    val roleClassName: String,
    val entityQualifiedName: String,
    val policyLocators: List<SourceLocator>,
    val attributeNames: List<String>,
)

internal data class SecurityRoleAttributePropagationProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)

data class SecurityRolePolicyModel(
    val type: SecurityRolePolicyType,
    val entityClass: String? = null,
    val entityActions: MutableList<EntityPolicyAction> = mutableListOf(),
    val allEntityActions: Boolean = false,
    val attributes: MutableList<String> = mutableListOf(),
    val attributeAction: EntityAttributePolicyAction = EntityAttributePolicyAction.VIEW,
    val resources: MutableList<String> = mutableListOf(),
    val rowActions: MutableList<RowLevelPolicyAction> = mutableListOf(),
    val whereClause: String? = null,
    val joinClause: String? = null,
    val predicateExpression: String? = null,
    val allowWildcard: Boolean = false,
) {
    fun toSinglePolicyRole(scope: RoleScope, packageName: String): RoleModel {
        val role = RoleModel(
            className = "GeneratedPolicyRole",
            packageName = packageName,
            name = "Generated policy",
            code = "generated-policy",
            scope = scope,
            securityScopes = mutableListOf("UI"),
            allowWildcardPolicies = allowWildcard,
        )
        when (type) {
            SecurityRolePolicyType.ENTITY -> role.entityPolicies += EntityPolicyModel(
                entityClass = entityClass.orEmpty(),
                actions = entityActions,
                allActions = allEntityActions,
            )
            SecurityRolePolicyType.ENTITY_ATTRIBUTE -> role.entityAttributePolicies +=
                EntityAttributePolicyModel(
                    entityClass = entityClass.orEmpty(),
                    attributes = attributes,
                    action = attributeAction,
                )
            SecurityRolePolicyType.MENU -> role.menuPolicies += resources.map { MenuPolicyModel(it) }
            SecurityRolePolicyType.VIEW -> role.viewPolicies += resources.map { ViewPolicyModel(it) }
            SecurityRolePolicyType.SPECIFIC -> role.specificPolicies += resources.map { SpecificPolicyModel(it) }
            SecurityRolePolicyType.JPQL_ROW -> role.rowLevelPolicies += RowLevelPolicyModel(
                entityClass = entityClass.orEmpty(),
                type = RowLevelPolicyType.JPQL,
                whereClause = whereClause,
                joinClause = joinClause,
            )
            SecurityRolePolicyType.PREDICATE_ROW -> role.rowLevelPolicies += RowLevelPolicyModel(
                entityClass = entityClass.orEmpty(),
                type = RowLevelPolicyType.PREDICATE,
                actions = rowActions,
                predicateExpression = predicateExpression,
            )
        }
        return role
    }
}

enum class SecurityRolePolicyType(
    val scope: RoleScope,
    val displayName: String,
) {
    @SerializedName("entity")
    ENTITY(RoleScope.RESOURCE, "entity policy"),

    @SerializedName("entityAttribute")
    ENTITY_ATTRIBUTE(RoleScope.RESOURCE, "entity attribute policy"),

    @SerializedName("menu")
    MENU(RoleScope.RESOURCE, "menu policy"),

    @SerializedName("view")
    VIEW(RoleScope.RESOURCE, "view policy"),

    @SerializedName("specific")
    SPECIFIC(RoleScope.RESOURCE, "specific policy"),

    @SerializedName("jpqlRow")
    JPQL_ROW(RoleScope.ROW_LEVEL, "JPQL row-level policy"),

    @SerializedName("predicateRow")
    PREDICATE_ROW(RoleScope.ROW_LEVEL, "predicate row-level policy"),
}

data class SecurityRoleDestinationSnapshot(
    val id: String,
    val moduleId: String,
    val sourceRoot: String,
    val defaultPackage: String,
    val recommended: Boolean,
)

data class SecurityRoleDestinationsResponse(
    val destinations: List<SecurityRoleDestinationSnapshot>,
    val defaultDestinationId: String?,
    val issues: List<WorkspaceChangeIssue>,
)

private data class SecurityRoleDestinationCandidate(
    val snapshot: SecurityRoleDestinationSnapshot,
    val existingRoleCount: Int,
    val conventionalJavaRoot: Boolean,
)

private data class AdaptedMethod(
    val methodText: String,
    val missingImports: Set<String>,
)

private data class RoleSourceContext(
    val content: String,
    val fingerprint: String,
    val fileNameWithoutExtension: String,
    val psiFile: PsiJavaFile,
    val roleClass: PsiClass,
    val scope: RoleScope,
    val relativePath: String,
)

private data class RoleSourceLoadResult(
    val context: RoleSourceContext?,
    val issue: WorkspaceChangeIssue?,
)

private data class PolicyTarget(
    val annotation: PsiAnnotation,
    val method: PsiMethod,
    val type: SecurityRolePolicyType,
    val locator: SourceLocator,
)

private data class ParsedEditorPolicy(
    val policy: SecurityRolePolicyModel?,
    val issue: String?,
)

private data class GeneratedPolicyMethod(
    val methodText: String,
    val methodName: String,
    val annotationText: String,
    val imports: List<String>,
)

private data class GeneratedPolicyResult(
    val policy: GeneratedPolicyMethod?,
    val issue: WorkspaceChangeIssue?,
)

private data class SecurityRoleChangeProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
) {
    fun preview(
        changeSetId: String = "security-role-create:rejected",
        label: String = "Security role creation rejected",
    ): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = changeSetId,
            label = label,
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )
}

private val QUALIFIED_TYPE_PATTERN =
    Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+""")

private val ROLE_ARTIFACT_KINDS =
    setOf(ArtifactKind.RESOURCE_ROLE, ArtifactKind.ROW_ROLE)

private val ENTITY_POLICY_TYPES = setOf(
    SecurityRolePolicyType.ENTITY,
    SecurityRolePolicyType.ENTITY_ATTRIBUTE,
    SecurityRolePolicyType.JPQL_ROW,
    SecurityRolePolicyType.PREDICATE_ROW,
)

private val ENTITY_ACTION_NAMES = setOf("ALL", "CREATE", "READ", "UPDATE", "DELETE")
private val ROW_ACTION_NAMES = setOf("CREATE", "READ", "UPDATE", "DELETE")
private val ATTRIBUTE_ACTION_NAMES = setOf("VIEW", "MODIFY")
