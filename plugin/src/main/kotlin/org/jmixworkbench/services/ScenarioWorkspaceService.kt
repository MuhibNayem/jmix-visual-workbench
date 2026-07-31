package org.jmixworkbench.services

import com.google.gson.Gson
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.change.WorkspaceChangeIssue
import org.jmixworkbench.discovery.change.WorkspaceChangePlan
import org.jmixworkbench.discovery.change.WorkspaceChangeSet
import org.jmixworkbench.discovery.change.WorkspaceFileChange
import org.jmixworkbench.discovery.change.WorkspaceFileChangeMode
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.ArtifactKind
import org.jmixworkbench.discovery.model.ArtifactSnapshot
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.ScenarioTestGenerator
import org.jmixworkbench.model.ScenarioActorMode
import org.jmixworkbench.model.ScenarioAssertionOperator
import org.jmixworkbench.model.ScenarioStepKind
import org.jmixworkbench.model.ScenarioStepModel
import org.jmixworkbench.model.ScenarioTestModel
import org.jmixworkbench.model.ScenarioValueModel
import org.jmixworkbench.model.ScenarioValueType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Multi-module visual scenario workspace and source-safe Jmix integration-test lifecycle.
 */
@Service(Service.Level.PROJECT)
class ScenarioWorkspaceService(private val project: Project) {
    private val gson = Gson()

    fun load(forceRefresh: Boolean = false): ScenarioWorkspaceResponse {
        val graph = ApplicationGraphService.getInstance(project).graph(forceRefresh)
        val destinations = destinations(graph)
        return ScenarioWorkspaceResponse(
            graphDigest = graph.snapshotDigest,
            destinations = destinations,
            defaultDestinationId = destinations.firstOrNull { it.recommended }?.id,
            contextArtifacts = graph.artifacts.filter { it.kind in CONTEXT_KINDS },
            existingScenarios = discoverExisting(destinations),
            issues = buildList {
                if (!graph.indexHealth.complete) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-SCENARIO-GRAPH-PARTIAL",
                            "The application index is partial. Scenario generation remains previewable, but refresh and resolve index diagnostics before trusting impact coverage.",
                        ),
                    )
                }
                if (destinations.isEmpty()) {
                    add(
                        WorkspaceChangeIssue(
                            "JVW-SCENARIO-DESTINATION-MISSING",
                            "No Java test source destination is available. Import the Gradle modules and refresh the project.",
                        ),
                    )
                }
            },
        )
    }

    fun preview(model: ScenarioTestModel): WorkspaceChangePreviewResponse {
        val proposal = propose(model)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal.issues)
    }

    fun prepare(request: ScenarioTestApplyRequest): PreparedWorkspaceChange {
        val proposal = propose(request.scenario)
        val changeSet = proposal.changeSet
            ?: return PreparedWorkspaceChange(
                plan = WorkspaceChangePlan(
                    accepted = false,
                    changeSetId = "scenario-test:rejected",
                    label = "Scenario test generation rejected",
                    planDigest = null,
                    files = emptyList(),
                    issues = proposal.issues,
                ),
                baseDir = null,
            )
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    internal fun propose(model: ScenarioTestModel): ScenarioTestProposal {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val destinations = destinations(graph)
        val destination = destinations.firstOrNull { it.id == model.destinationId }
            ?: return rejected(
                "JVW-SCENARIO-DESTINATION-INVALID",
                "The selected test destination is no longer registered. Refresh the scenario workspace.",
            )
        val issues = validate(model, graph.artifacts)
        if (issues.isNotEmpty()) {
            return ScenarioTestProposal(null, issues)
        }

        val storedModel = model.copy(sourceLocator = null)
        val encoded = encode(storedModel)
        val content = ScenarioTestGenerator.generate(storedModel, encoded)
        javaSyntaxError("${model.className}.java", content)?.let { syntax ->
            return rejected(
                "JVW-SCENARIO-GENERATED-SYNTAX",
                "Generated Java is not syntactically valid: ${syntax.errorDescription}",
            )
        }
        val targetPath = buildString {
            append(destination.testSourceRoot.trimEnd('/', '\\'))
            append('/')
            append(model.packageName.replace('.', '/'))
            append('/')
            append(model.className)
            append(".java")
        }
        val change = if (model.sourceLocator == null) {
            WorkspaceFileChange(
                relativePath = targetPath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = content,
            )
        } else {
            val existing = loadOwnedScenario(model.sourceLocator)
                ?: return rejected(
                    "JVW-SCENARIO-SOURCE-NOT-OWNED",
                    "The existing test is not an unchanged workbench-owned scenario. Manual code is never overwritten.",
                    model.sourceLocator.relativePath,
                )
            if (existing.locator.relativePath != targetPath) {
                return rejected(
                    "JVW-SCENARIO-MOVE-UNSUPPORTED",
                    "Changing package, class, or module would move the existing test. Create a new scenario instead.",
                    existing.locator.relativePath,
                )
            }
            if (model.sourceLocator.revisionFingerprint != existing.locator.revisionFingerprint) {
                return rejected(
                    "JVW-SCENARIO-SOURCE-STALE",
                    "The scenario source changed after it was loaded. Refresh before editing.",
                    existing.locator.relativePath,
                )
            }
            WorkspaceFileChange(
                relativePath = targetPath,
                mode = WorkspaceFileChangeMode.MODIFY,
                baseRevisionFingerprint = existing.locator.revisionFingerprint,
                edits = listOf(
                    WorkspaceTextEdit(
                        startOffset = 0,
                        endOffset = existing.content.length,
                        expectedText = existing.content,
                        replacement = content,
                    ),
                ),
            )
        }
        val identity = listOf(targetPath, content, model.sourceLocator?.revisionFingerprint.orEmpty())
            .joinToString("\u0000")
        return ScenarioTestProposal(
            changeSet = WorkspaceChangeSet(
                id = "scenario-test:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "${if (model.sourceLocator == null) "Create" else "Update"} Jmix scenario ${model.name}",
                files = listOf(change),
            ),
            issues = emptyList(),
        )
    }

    private fun destinations(graph: ApplicationGraphResponse): List<ScenarioDestinationSnapshot> {
        val fallbackBasePackage = JmixProjectService.getInstance(project).getConfig()?.basePackage
            ?.takeIf(String::isNotBlank)
            ?: "com.example.app"
        val candidates = ProjectSourceDestinationService.getInstance(project)
            .testJava(graph)
            .map { source ->
                ScenarioDestinationSnapshot(
                    id = destinationId(source.moduleId, source.sourceRoot),
                    moduleId = source.moduleId,
                    testSourceRoot = source.sourceRoot,
                    defaultPackage = "${inferModuleBasePackage(source.moduleId, graph, fallbackBasePackage)}.scenario",
                    recommended = false,
                )
            }
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<ScenarioDestinationSnapshot> {
                    graph.artifacts.any { artifact ->
                        artifact.owner.moduleId == it.moduleId && artifact.kind == ArtifactKind.ENTITY
                    }
                }.thenBy { it.moduleId }.thenBy { it.testSourceRoot },
            )
        return candidates.mapIndexed { index, destination ->
            destination.copy(recommended = index == 0)
        }
    }

    private fun inferModuleBasePackage(
        moduleId: String,
        graph: ApplicationGraphResponse,
        fallback: String,
    ): String {
        val packages = graph.artifacts.asSequence()
            .filter { artifact ->
                artifact.owner.moduleId == moduleId &&
                    artifact.kind in setOf(
                        ArtifactKind.ENTITY,
                        ArtifactKind.SERVICE,
                        ArtifactKind.VIEW_CONTROLLER,
                        ArtifactKind.REST_CONTROLLER,
                    )
            }
            .map { artifact -> artifact.semanticKey.substringBefore('#').substringBeforeLast('.', "") }
            .filter(String::isNotBlank)
            .map(::trimConventionalPackage)
            .groupingBy { it }
            .eachCount()
        return packages.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length },
        )?.key ?: fallback
    }

    private fun trimConventionalPackage(packageName: String): String {
        val segments = packageName.split('.').toMutableList()
        while (segments.lastOrNull() in CONVENTIONAL_PACKAGE_SUFFIXES) {
            segments.removeLast()
        }
        return segments.joinToString(".").ifBlank { packageName }
    }

    private fun discoverExisting(
        destinations: List<ScenarioDestinationSnapshot>,
    ): List<ScenarioDocumentSnapshot> {
        val resolver = ProjectFileResolver.getInstance(project)
        val discovered = mutableListOf<ScenarioDocumentSnapshot>()
        var visited = 0
        destinations.forEach { destination ->
            if (visited >= MAX_SCENARIO_FILES) return@forEach
            val target = resolver.resolveTarget(destination.testSourceRoot) ?: return@forEach
            val root = if (target.relativePath.isBlank()) {
                target.root
            } else {
                target.root.findFileByRelativePath(target.relativePath)
            } ?: return@forEach
            visitJavaFiles(root) { file ->
                if (visited++ >= MAX_SCENARIO_FILES || file.length > MAX_SCENARIO_BYTES) {
                    return@visitJavaFiles
                }
                val relativePath = resolver.locatorPath(file) ?: return@visitJavaFiles
                val content = runCatching {
                    String(file.contentsToByteArray(false), file.charset)
                }.getOrNull() ?: return@visitJavaFiles
                val decoded = decode(content) ?: return@visitJavaFiles
                val fingerprint = CanonicalDiscoveryJson.sha256(content)
                val locator = SourceLocator(
                    relativePath = relativePath,
                    symbol = decoded.className,
                    revisionFingerprint = fingerprint,
                )
                val owned = ScenarioTestGenerator.generate(decoded, encode(decoded)) == content
                discovered += ScenarioDocumentSnapshot(
                    locator = locator,
                    model = decoded.copy(sourceLocator = locator),
                    editable = owned,
                    issue = if (owned) null else "Manual source changes were detected; visual overwrite is disabled.",
                )
            }
        }
        return discovered.distinctBy { it.locator.relativePath }.sortedBy { it.locator.relativePath }
    }

    private fun loadOwnedScenario(locator: SourceLocator): OwnedScenarioSource? {
        val resolved = ProjectFileResolver.getInstance(project).resolveFile(locator.relativePath) ?: return null
        val content = runCatching {
            String(resolved.file.contentsToByteArray(false), resolved.file.charset)
        }.getOrNull() ?: return null
        val fingerprint = CanonicalDiscoveryJson.sha256(content)
        if (fingerprint != locator.revisionFingerprint) return null
        val decoded = decode(content) ?: return null
        if (ScenarioTestGenerator.generate(decoded, encode(decoded)) != content) return null
        return OwnedScenarioSource(
            locator = locator.copy(revisionFingerprint = fingerprint),
            content = content,
        )
    }

    private fun visitJavaFiles(root: VirtualFile, consumer: (VirtualFile) -> Unit) {
        if (root.isDirectory) {
            root.children.sortedBy { it.name }.forEach { child -> visitJavaFiles(child, consumer) }
        } else if (root.extension.equals("java", ignoreCase = true)) {
            consumer(root)
        }
    }

    private fun validate(
        model: ScenarioTestModel,
        artifacts: List<ArtifactSnapshot>,
    ): List<WorkspaceChangeIssue> {
        val issues = mutableListOf<WorkspaceChangeIssue>()
        if (!PACKAGE.matches(model.packageName)) {
            issues += issue("JVW-SCENARIO-PACKAGE-INVALID", "Enter a valid Java package.")
        }
        if (!IDENTIFIER.matches(model.className) || !model.className.endsWith("Test")) {
            issues += issue("JVW-SCENARIO-CLASS-INVALID", "Use a valid Java class name ending in Test.")
        }
        if (model.name.isBlank() || model.name.length > 160) {
            issues += issue("JVW-SCENARIO-NAME-INVALID", "Enter a scenario name of at most 160 characters.")
        }
        if (model.description.length > 2_000) {
            issues += issue("JVW-SCENARIO-DESCRIPTION-INVALID", "Scenario descriptions are limited to 2,000 characters.")
        }
        if (model.steps.isEmpty() || model.steps.size > MAX_STEPS) {
            issues += issue(
                "JVW-SCENARIO-STEP-COUNT",
                "A scenario requires between 1 and $MAX_STEPS steps.",
            )
            return issues
        }
        model.steps.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach {
            issues += issue("JVW-SCENARIO-STEP-ID-DUPLICATE", "Scenario step IDs must be unique.")
        }
        val indexedEntities = artifacts
            .filter { it.kind == ArtifactKind.ENTITY || it.kind == ArtifactKind.DTO }
            .flatMap { artifact -> listOf(artifact.semanticKey, artifact.displayName) }
            .toSet()
        val variables = linkedSetOf<String>()
        model.steps.forEachIndexed { index, step ->
            val prefix = "Step ${index + 1}"
            if (!IDENTIFIER.matches(step.id) || step.label.isBlank() || step.label.length > 160) {
                issues += issue("JVW-SCENARIO-STEP-IDENTITY", "$prefix needs a stable ID and concise label.")
            }
            validateActor(step, prefix, issues)
            when (step.kind) {
                ScenarioStepKind.SEED_ENTITY -> {
                    val variable = step.variableName.orEmpty()
                    val entityClass = step.entityClass.orEmpty()
                    if (!IDENTIFIER.matches(variable) || variable in variables) {
                        issues += issue("JVW-SCENARIO-VARIABLE-INVALID", "$prefix needs a unique result variable.")
                    }
                    if (!QUALIFIED_NAME.matches(entityClass) || entityClass !in indexedEntities) {
                        issues += issue(
                            "JVW-SCENARIO-ENTITY-NOT-INDEXED",
                            "$prefix must target an indexed entity using its qualified class name.",
                        )
                    }
                    step.fields.groupingBy { it.property }.eachCount().filterValues { it > 1 }.keys.forEach {
                        issues += issue("JVW-SCENARIO-FIELD-DUPLICATE", "$prefix assigns the same field more than once.")
                    }
                    step.fields.forEach { field ->
                        if (!IDENTIFIER.matches(field.property)) {
                            issues += issue("JVW-SCENARIO-FIELD-INVALID", "$prefix contains an invalid entity field.")
                        }
                        validateValue(field.value, variables, "$prefix field ${field.property}", issues)
                    }
                    if (IDENTIFIER.matches(variable) && variable !in variables) variables += variable
                }
                ScenarioStepKind.INVOKE_SERVICE -> {
                    if (!BEAN_NAME.matches(step.beanName.orEmpty())) {
                        issues += issue("JVW-SCENARIO-BEAN-INVALID", "$prefix needs a valid Spring bean name.")
                    }
                    if (!IDENTIFIER.matches(step.methodName.orEmpty())) {
                        issues += issue("JVW-SCENARIO-METHOD-INVALID", "$prefix needs a valid Java method name.")
                    }
                    step.arguments.forEach { validateValue(it, variables, "$prefix argument", issues) }
                    step.resultVariable?.takeIf(String::isNotBlank)?.let { result ->
                        if (!IDENTIFIER.matches(result) || result in variables) {
                            issues += issue(
                                "JVW-SCENARIO-VARIABLE-INVALID",
                                "$prefix result variable must be unique.",
                            )
                        } else {
                            variables += result
                        }
                    }
                }
                ScenarioStepKind.ASSERT_PROPERTY -> {
                    if (step.targetVariable !in variables) {
                        issues += issue(
                            "JVW-SCENARIO-ASSERT-VARIABLE-MISSING",
                            "$prefix references a variable that has not been created.",
                        )
                    }
                    if (!PROPERTY_PATH.matches(step.propertyPath.orEmpty())) {
                        issues += issue("JVW-SCENARIO-PROPERTY-INVALID", "$prefix needs a valid entity property path.")
                    }
                    if (step.operator == null) {
                        issues += issue("JVW-SCENARIO-ASSERTION-MISSING", "$prefix needs an assertion operator.")
                    } else if (step.operator in EXPECTED_VALUE_OPERATORS) {
                        val expected = step.expected
                        if (expected == null) {
                            issues += issue("JVW-SCENARIO-EXPECTED-MISSING", "$prefix needs an expected value.")
                        } else {
                            validateValue(expected, variables, "$prefix expected value", issues)
                        }
                    }
                }
                ScenarioStepKind.ASSERT_VALUE -> {
                    if (step.targetVariable !in variables) {
                        issues += issue(
                            "JVW-SCENARIO-ASSERT-VARIABLE-MISSING",
                            "$prefix references a variable that has not been created.",
                        )
                    }
                    validateAssertion(step, variables, prefix, issues)
                }
                ScenarioStepKind.ASSERT_ENTITY_COUNT -> {
                    val entityClass = step.entityClass.orEmpty()
                    if (!QUALIFIED_NAME.matches(entityClass) || entityClass !in indexedEntities) {
                        issues += issue(
                            "JVW-SCENARIO-ENTITY-NOT-INDEXED",
                            "$prefix must target an indexed entity using its qualified class name.",
                        )
                    }
                    val query = step.jpql.orEmpty().trim()
                    if (query.isBlank() || query.length > MAX_QUERY_LENGTH || MUTATING_QUERY.containsMatchIn(query)) {
                        issues += issue(
                            "JVW-SCENARIO-QUERY-INVALID",
                            "$prefix requires a bounded read-only JPQL query.",
                        )
                    }
                    if (JPQL_PARAMETER.containsMatchIn(query)) {
                        issues += issue(
                            "JVW-SCENARIO-QUERY-PARAMETER-UNSUPPORTED",
                            "$prefix contains JPQL parameters. Use a service step or literal test value in this release.",
                        )
                    }
                    if (step.expectedCount == null || step.expectedCount < 0) {
                        issues += issue("JVW-SCENARIO-COUNT-INVALID", "$prefix needs a non-negative expected count.")
                    }
                }
                ScenarioStepKind.ASSERT_SERVICE_FAILURE -> {
                    if (!BEAN_NAME.matches(step.beanName.orEmpty())) {
                        issues += issue("JVW-SCENARIO-BEAN-INVALID", "$prefix needs a valid Spring bean name.")
                    }
                    if (!IDENTIFIER.matches(step.methodName.orEmpty())) {
                        issues += issue("JVW-SCENARIO-METHOD-INVALID", "$prefix needs a valid Java method name.")
                    }
                    step.arguments.forEach { validateValue(it, variables, "$prefix argument", issues) }
                    if (!QUALIFIED_NAME.matches(step.expectedExceptionClass.orEmpty())) {
                        issues += issue(
                            "JVW-SCENARIO-EXCEPTION-INVALID",
                            "$prefix needs the qualified class name of the expected exception.",
                        )
                    }
                    if (step.messageContains.orEmpty().length > MAX_VALUE_LENGTH) {
                        issues += issue(
                            "JVW-SCENARIO-EXCEPTION-MESSAGE-INVALID",
                            "$prefix expected message fragment is too long.",
                        )
                    }
                }
            }
        }
        return issues.distinct()
    }

    private fun validateAssertion(
        step: ScenarioStepModel,
        variables: Set<String>,
        prefix: String,
        issues: MutableList<WorkspaceChangeIssue>,
    ) {
        if (step.operator == null) {
            issues += issue("JVW-SCENARIO-ASSERTION-MISSING", "$prefix needs an assertion operator.")
        } else if (step.operator in EXPECTED_VALUE_OPERATORS) {
            val expected = step.expected
            if (expected == null) {
                issues += issue("JVW-SCENARIO-EXPECTED-MISSING", "$prefix needs an expected value.")
            } else {
                validateValue(expected, variables, "$prefix expected value", issues)
            }
        }
    }

    private fun validateActor(
        step: ScenarioStepModel,
        prefix: String,
        issues: MutableList<WorkspaceChangeIssue>,
    ) {
        if (step.actorMode == ScenarioActorMode.USER &&
            (step.username.isNullOrBlank() || step.username.length > 255)
        ) {
            issues += issue("JVW-SCENARIO-USERNAME-INVALID", "$prefix needs a username for user-scoped execution.")
        }
    }

    private fun validateValue(
        value: ScenarioValueModel,
        variables: Set<String>,
        label: String,
        issues: MutableList<WorkspaceChangeIssue>,
    ) {
        val raw = value.value.orEmpty()
        val valid = when (value.type) {
            ScenarioValueType.STRING -> raw.length <= MAX_VALUE_LENGTH
            ScenarioValueType.INTEGER -> raw.toIntOrNull() != null
            ScenarioValueType.LONG -> raw.toLongOrNull() != null
            ScenarioValueType.DECIMAL -> runCatching { BigDecimal(raw) }.isSuccess
            ScenarioValueType.BOOLEAN -> raw == "true" || raw == "false"
            ScenarioValueType.UUID -> runCatching { UUID.fromString(raw) }.isSuccess
            ScenarioValueType.LOCAL_DATE -> runCatching { LocalDate.parse(raw) }.isSuccess
            ScenarioValueType.LOCAL_DATETIME -> runCatching { LocalDateTime.parse(raw) }.isSuccess
            ScenarioValueType.OFFSET_DATETIME -> runCatching { OffsetDateTime.parse(raw) }.isSuccess
            ScenarioValueType.INSTANT -> runCatching { Instant.parse(raw) }.isSuccess
            ScenarioValueType.ENUM ->
                QUALIFIED_NAME.matches(value.javaType.orEmpty()) && IDENTIFIER.matches(raw)
            ScenarioValueType.NULL -> true
            ScenarioValueType.VARIABLE -> IDENTIFIER.matches(raw) && raw in variables
        }
        if (!valid) {
            issues += issue(
                "JVW-SCENARIO-VALUE-INVALID",
                "$label has an invalid ${value.type.name.lowercase().replace('_', ' ')} value.",
            )
        }
    }

    private fun javaSyntaxError(fileName: String, content: String): PsiErrorElement? {
        val file = PsiFileFactory.getInstance(project).createFileFromText(
            fileName,
            JavaFileType.INSTANCE,
            content,
        )
        return PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
    }

    private fun encode(model: ScenarioTestModel): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            gson.toJson(model.copy(sourceLocator = null)).toByteArray(Charsets.UTF_8),
        )

    private fun decode(content: String): ScenarioTestModel? {
        val marker = content.lineSequence()
            .firstOrNull { it.startsWith(ScenarioTestGenerator.markerPrefix()) }
            ?.removePrefix(ScenarioTestGenerator.markerPrefix())
            ?.trim()
            ?.takeIf { it.length <= MAX_MARKER_LENGTH }
            ?: return null
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(marker), Charsets.UTF_8)
            gson.fromJson(json, ScenarioTestModel::class.java).copy(sourceLocator = null)
        }.getOrNull()
    }

    private fun destinationId(moduleId: String, testRoot: String): String =
        CanonicalDiscoveryJson.sha256("$moduleId\u0000$testRoot").take(24)

    private fun rejected(
        code: String,
        message: String,
        relativePath: String? = null,
    ): ScenarioTestProposal =
        ScenarioTestProposal(null, listOf(WorkspaceChangeIssue(code, message, relativePath)))

    private fun rejectedPreview(issues: List<WorkspaceChangeIssue>): WorkspaceChangePreviewResponse =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "scenario-test:rejected",
            label = "Scenario test generation rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    private fun issue(code: String, message: String): WorkspaceChangeIssue =
        WorkspaceChangeIssue(code, message)

    companion object {
        private const val MAX_STEPS = 200
        private const val MAX_QUERY_LENGTH = 20_000
        private const val MAX_VALUE_LENGTH = 20_000
        private const val MAX_SCENARIO_FILES = 10_000
        private const val MAX_SCENARIO_BYTES = 4L * 1024 * 1024
        private const val MAX_MARKER_LENGTH = 1_000_000
        private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
        private val QUALIFIED_NAME = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""")
        private val PACKAGE = QUALIFIED_NAME
        private val PROPERTY_PATH = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
        private val BEAN_NAME = Regex("""[A-Za-z0-9_$][A-Za-z0-9_.$:-]*""")
        private val MUTATING_QUERY = Regex("""(?i)\b(update|delete|insert|merge|truncate|drop|alter)\b""")
        private val JPQL_PARAMETER = Regex(""":[A-Za-z_$][A-Za-z0-9_$]*""")
        private val EXPECTED_VALUE_OPERATORS = setOf(
            ScenarioAssertionOperator.EQUALS,
            ScenarioAssertionOperator.NOT_EQUALS,
            ScenarioAssertionOperator.GREATER_THAN,
            ScenarioAssertionOperator.LESS_THAN,
            ScenarioAssertionOperator.CONTAINS,
        )
        private val CONTEXT_KINDS = setOf(
            ArtifactKind.ENTITY,
            ArtifactKind.DTO,
            ArtifactKind.SERVICE,
            ArtifactKind.SERVICE_METHOD,
            ArtifactKind.REST_ENDPOINT,
            ArtifactKind.REST_SERVICE_METHOD,
            ArtifactKind.WORKFLOW_PROCESS,
            ArtifactKind.WORKFLOW_STATE,
            ArtifactKind.RESOURCE_ROLE,
            ArtifactKind.ROW_ROLE,
        )
        private val CONVENTIONAL_PACKAGE_SUFFIXES = setOf(
            "entity",
            "service",
            "view",
            "security",
            "rest",
            "controller",
        )

        fun getInstance(project: Project): ScenarioWorkspaceService =
            project.getService(ScenarioWorkspaceService::class.java)
    }
}

data class ScenarioDestinationSnapshot(
    val id: String,
    val moduleId: String,
    val testSourceRoot: String,
    val defaultPackage: String,
    val recommended: Boolean,
)

data class ScenarioDocumentSnapshot(
    val locator: SourceLocator,
    val model: ScenarioTestModel,
    val editable: Boolean,
    val issue: String?,
)

data class ScenarioWorkspaceResponse(
    val graphDigest: String,
    val destinations: List<ScenarioDestinationSnapshot>,
    val defaultDestinationId: String?,
    val contextArtifacts: List<ArtifactSnapshot>,
    val existingScenarios: List<ScenarioDocumentSnapshot>,
    val issues: List<WorkspaceChangeIssue>,
)

data class ScenarioTestApplyRequest(
    val scenario: ScenarioTestModel,
    val expectedPlanDigest: String,
)

data class ScenarioTestProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)

private data class OwnedScenarioSource(
    val locator: SourceLocator,
    val content: String,
)
