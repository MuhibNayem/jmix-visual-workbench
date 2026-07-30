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
import org.jmixworkbench.discovery.change.WorkspaceTextEdit
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator
import org.jmixworkbench.generator.DataRepositoryGenerator
import org.jmixworkbench.generator.KotlinDataRepositoryGenerator
import org.jmixworkbench.model.DataRepositoryConfig
import org.jmixworkbench.model.EntityModel
import org.jmixworkbench.model.EntitySourceLanguage
import org.jmixworkbench.model.EntityType
import org.jmixworkbench.model.IdConfig

/**
 * Revision-bound repository creation and additive handwritten-source updates.
 *
 * Existing members are immutable evidence in this tranche. New abstract
 * derived/JPQL methods are generated from the typed contract, inserted before
 * the exact interface closing brace, and accompanied by only missing imports.
 * Custom annotations, default methods, native queries, comments, and manual
 * formatting outside the insertion points are untouched.
 */
@Service(Service.Level.PROJECT)
class DataRepositoryChangeService(private val project: Project) {

    fun validate(request: DataRepositoryChangeRequest): RepositorySemanticValidationResponse {
        val schema = SchemaWorkspaceService.getInstance(project).load(forceRefresh = false)
        val entity = schema.entities.singleOrNull { it.sourceLocator == request.entitySource }
            ?: return RepositorySemanticValidationResponse(
                accepted = false,
                diagnostics = listOf(
                    RepositorySemanticDiagnostic(
                        severity = RepositorySemanticSeverity.ERROR,
                        code = "JVW-REPOSITORY-ENTITY-STALE",
                        message = "The selected entity is missing, ambiguous, or changed. Refresh Entity Designer.",
                    ),
                ),
                propertyPaths = emptyList(),
                methods = emptyList(),
            )
        val analyzed = RepositorySemanticAnalyzer.analyze(entity, schema.entities, request.config)
        val lockedMethodCount = request.repositorySource
            ?.let { locator ->
                schema.repositories.singleOrNull { it.sourceLocator == locator }
                    ?.config
                    ?.methods
                    ?.size
            }
            ?: 0
        val diagnostics = analyzed.diagnostics.map { diagnostic ->
            val sourceOwned = request.repositorySource != null &&
                diagnostic.methodIndex != null &&
                diagnostic.methodIndex < lockedMethodCount
            diagnostic.copy(
                blocking = diagnostic.severity == RepositorySemanticSeverity.ERROR && !sourceOwned,
                sourceOwned = sourceOwned,
            )
        }
        return analyzed.copy(
            accepted = diagnostics.none(RepositorySemanticDiagnostic::blocking),
            diagnostics = diagnostics,
        )
    }

    fun preview(request: DataRepositoryChangeRequest): WorkspaceChangePreviewResponse {
        val proposal = propose(request)
        return proposal.changeSet
            ?.let { WorkspaceChangeService.getInstance(project).preview(it) }
            ?: rejectedPreview(proposal.issues)
    }

    fun prepareApply(request: DataRepositoryApplyRequest): PreparedWorkspaceChange {
        val proposal = propose(request.change)
        val changeSet = proposal.changeSet ?: return rejectedPrepared(proposal.issues)
        return WorkspaceChangeService.getInstance(project).prepareApply(
            WorkspaceChangeApplyRequest(changeSet, request.expectedPlanDigest),
        )
    }

    internal fun propose(request: DataRepositoryChangeRequest): DataRepositoryChangeProposal {
        val schema = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
        val entity = schema.entities.singleOrNull { it.sourceLocator == request.entitySource }
            ?: return rejected(
                "JVW-REPOSITORY-ENTITY-STALE",
                "The selected entity is missing, ambiguous, or changed. Refresh Entity Designer.",
                request.entitySource.relativePath,
            )
        if (entity.entityType != EntityType.ENTITY) {
            return rejected(
                "JVW-REPOSITORY-ENTITY-KIND",
                "A Jmix data repository can target only a persistent JPA entity.",
                entity.sourceLocator.relativePath,
            )
        }
        if (!request.config.enabled) {
            return rejected(
                "JVW-REPOSITORY-DISABLED",
                "Enable the repository before requesting a source change.",
            )
        }
        val language = if (entity.sourceLocator.relativePath.endsWith(".kt")) {
            EntitySourceLanguage.KOTLIN
        } else {
            EntitySourceLanguage.JAVA
        }
        val model = EntityModel(
            className = entity.className,
            packageName = entity.qualifiedName.substringBeforeLast('.', missingDelimiterValue = ""),
            sourceLanguage = language,
            entityName = entity.entityName,
            entityType = EntityType.ENTITY,
            id = IdConfig(type = entity.idType, columnName = entity.idColumnName),
            dataRepository = request.config,
        )
        val semantics = RepositorySemanticAnalyzer.analyze(
            entity,
            schema.entities,
            request.config,
        )
        val lockedMethodCount = request.repositorySource
            ?.let { locator ->
                schema.repositories.singleOrNull { it.sourceLocator == locator }
                    ?.config
                    ?.methods
                    ?.size
            }
            ?: 0
        val blockingDiagnostics = semantics.diagnostics.filter { diagnostic ->
            diagnostic.severity == RepositorySemanticSeverity.ERROR &&
                (
                    request.repositorySource == null ||
                        diagnostic.methodIndex == null ||
                        diagnostic.methodIndex >= lockedMethodCount
                    )
        }
        if (blockingDiagnostics.isNotEmpty()) {
            return DataRepositoryChangeProposal(
                changeSet = null,
                issues = blockingDiagnostics.map { diagnostic ->
                        WorkspaceChangeIssue(
                            diagnostic.code,
                            buildString {
                                diagnostic.methodIndex?.let { append("Method ${it + 1}: ") }
                                append(diagnostic.message)
                                if (diagnostic.suggestions.isNotEmpty()) {
                                    append(" Suggestions: ")
                                    append(diagnostic.suggestions.joinToString())
                                    append('.')
                                }
                            },
                            entity.sourceLocator.relativePath,
                        )
                    },
            )
        }
        return if (request.repositorySource == null) {
            createRepository(entity, model, request.config)
        } else {
            updateRepository(entity, model, request)
        }
    }

    private fun createRepository(
        entity: SchemaEntitySnapshot,
        model: EntityModel,
        config: DataRepositoryConfig,
    ): DataRepositoryChangeProposal {
        val repositoryName = config.interfaceName?.trim().orEmpty()
            .ifBlank { "${entity.className}Repository" }
        val content = generatedRepository(model)
            ?: return rejected(
                "JVW-REPOSITORY-GENERATION",
                "The repository contract is invalid or could not be generated.",
            )
        syntaxIssue("$repositoryName.${model.sourceLanguage.fileExtension}", model.sourceLanguage, content)
            ?.let { return DataRepositoryChangeProposal(null, listOf(it)) }
        val entityPath = entity.sourceLocator.relativePath
        val targetPath = entityPath.substringBeforeLast('/') +
            "/$repositoryName.${model.sourceLanguage.fileExtension}"
        val files = mutableListOf(
            WorkspaceFileChange(
                relativePath = targetPath,
                mode = WorkspaceFileChangeMode.CREATE,
                baseRevisionFingerprint = null,
                createContent = content,
            ),
        )
        repositoryActivation(entity, model)?.let(files::add)
        val identity = listOf(
            entity.sourceLocator.relativePath,
            entity.sourceLocator.revisionFingerprint,
            targetPath,
            content,
        ).joinToString("\u0000")
        return DataRepositoryChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "data-repository:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Create $repositoryName for ${entity.className}",
                files = files,
            ),
            issues = emptyList(),
        )
    }

    private fun updateRepository(
        entity: SchemaEntitySnapshot,
        model: EntityModel,
        request: DataRepositoryChangeRequest,
    ): DataRepositoryChangeProposal {
        val locator = requireNotNull(request.repositorySource)
        val snapshot = SchemaWorkspaceService.getInstance(project).load(forceRefresh = true)
            .repositories
            .singleOrNull { it.sourceLocator == locator }
            ?: return rejected(
                "JVW-REPOSITORY-SOURCE-STALE",
                "The repository is missing, ambiguous, or changed. Refresh Entity Designer.",
                locator.relativePath,
            )
        if (snapshot.entityQualifiedName != entity.qualifiedName) {
            return rejected(
                "JVW-REPOSITORY-ENTITY-MISMATCH",
                "The selected repository does not target ${entity.qualifiedName}.",
                locator.relativePath,
            )
        }
        val content = read(locator.relativePath)
            ?: return rejected(
                "JVW-REPOSITORY-SOURCE-MISSING",
                "The repository source cannot be read from registered project roots.",
                locator.relativePath,
            )
        val parsed = RepositorySourceParser.parse(
            content,
            kotlin = snapshot.sourceLanguage == EntitySourceLanguage.KOTLIN,
        ) ?: return rejected(
            "JVW-REPOSITORY-SOURCE-PARSE",
            "The repository interface no longer has a supported JmixDataRepository declaration.",
            locator.relativePath,
        )
        val requestedName = request.config.interfaceName?.trim().orEmpty()
            .ifBlank { parsed.interfaceName }
        if (requestedName != parsed.interfaceName) {
            return rejected(
                "JVW-REPOSITORY-RENAME-NATIVE",
                "Repository interface rename must use IntelliJ Rename so injections and usages participate.",
                locator.relativePath,
            )
        }
        if (request.config.applyConstraints != parsed.config.applyConstraints) {
            return rejected(
                "JVW-REPOSITORY-SECURITY-SOURCE-LOCKED",
                "Changing repository-wide security on handwritten source requires a dedicated security-impact preview.",
                locator.relativePath,
            )
        }
        val baseline = parsed.config.methods
        if (request.config.methods.size < baseline.size) {
            return rejected(
                "JVW-REPOSITORY-DELETE-NATIVE",
                "Existing repository methods cannot be deleted through an additive update. Use IntelliJ Safe Delete.",
                locator.relativePath,
            )
        }
        baseline.forEachIndexed { index, method ->
            if (request.config.methods.getOrNull(index) != method) {
                return rejected(
                    "JVW-REPOSITORY-EXISTING-METHOD-LOCKED",
                    "Existing method '${method.name}' changed or moved. Refresh and add a new method, or edit source directly.",
                    locator.relativePath,
                )
            }
        }
        val additions = request.config.methods.drop(baseline.size)
        if (additions.isEmpty()) {
            return rejected(
                "JVW-REPOSITORY-NOOP",
                "No new repository methods were added.",
                locator.relativePath,
            )
        }
        val baselineSignatures = baseline.map(RepositorySourceParser::signature).toSet()
        val additionSignatures = additions.map(RepositorySourceParser::signature)
        if (additionSignatures.distinct().size != additionSignatures.size ||
            additionSignatures.any { it in baselineSignatures }
        ) {
            return rejected(
                "JVW-REPOSITORY-METHOD-COLLISION",
                "New repository methods collide with an existing JVM signature.",
                locator.relativePath,
            )
        }
        val additionConfig = request.config.copy(methods = additions.toMutableList())
        val generated = generatedRepository(model.copy(dataRepository = additionConfig))
            ?: return rejected(
                "JVW-REPOSITORY-METHOD-GENERATION",
                "One or more new repository methods are invalid.",
            )
        val generatedBody = interfaceBody(generated)
            ?: return rejected(
                "JVW-REPOSITORY-METHOD-GENERATION",
                "Generated repository methods could not be isolated safely.",
            )
        val missingImports = imports(generated) - imports(content)
        val edits = mutableListOf<WorkspaceTextEdit>()
        importEdit(content, missingImports, snapshot.sourceLanguage)?.let(edits::add)
        edits += WorkspaceTextEdit(
            startOffset = parsed.bodyCloseOffset,
            endOffset = parsed.bodyCloseOffset,
            expectedText = "",
            replacement = generatedBody.trimEnd().prependIndent("    ")
                .let { body -> "\n$body\n" },
        )
        val resulting = applyEdits(content, edits)
        syntaxIssue(
            locator.relativePath.substringAfterLast('/'),
            snapshot.sourceLanguage,
            resulting,
        )?.let { return DataRepositoryChangeProposal(null, listOf(it)) }
        val identity = listOf(
            locator.relativePath,
            locator.revisionFingerprint,
            additions.joinToString("\u0000", transform = RepositorySourceParser::signature),
            resulting,
        ).joinToString("\u0000")
        return DataRepositoryChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "data-repository:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Add ${additions.size} method(s) to ${snapshot.interfaceName}",
                files = listOf(
                    WorkspaceFileChange(
                        relativePath = locator.relativePath,
                        mode = WorkspaceFileChangeMode.MODIFY,
                        baseRevisionFingerprint = locator.revisionFingerprint,
                        edits = edits.sortedBy(WorkspaceTextEdit::startOffset),
                    ),
                ),
            ),
            issues = emptyList(),
        )
    }

    private fun repositoryActivation(
        entity: SchemaEntitySnapshot,
        model: EntityModel,
    ): WorkspaceFileChange? {
        val graph = ApplicationGraphService.getInstance(project).graph()
        val alreadyActive = graph.artifacts
            .asSequence()
            .map { it.sourceLocator.relativePath }
            .distinct()
            .any { path -> read(path)?.contains("EnableJmixDataRepositories") == true }
        if (alreadyActive) return null
        val entityPackage = entity.qualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
        val basePackage = entityPackage.removeSuffix(".entity")
        val packagePath = entityPackage.replace('.', '/')
        val entityPath = entity.sourceLocator.relativePath
        val packageMarker = "/$packagePath/"
        val markerOffset = entityPath.indexOf(packageMarker)
        if (markerOffset < 0) return null
        val sourceRoot = entityPath.substring(0, markerOffset)
        val extension = model.sourceLanguage.fileExtension
        val path = "$sourceRoot/${basePackage.replace('.', '/')}/JmixDataRepositoryConfiguration.$extension"
        val content = if (model.sourceLanguage == EntitySourceLanguage.KOTLIN) {
            """
                package $basePackage

                import io.jmix.core.repository.EnableJmixDataRepositories
                import org.springframework.context.annotation.Configuration

                @Configuration
                @EnableJmixDataRepositories(basePackages = ["$entityPackage"])
                open class JmixDataRepositoryConfiguration
            """.trimIndent() + "\n"
        } else {
            """
                package $basePackage;

                import io.jmix.core.repository.EnableJmixDataRepositories;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @EnableJmixDataRepositories(basePackages = "$entityPackage")
                public class JmixDataRepositoryConfiguration {
                }
            """.trimIndent() + "\n"
        }
        return WorkspaceFileChange(
            relativePath = path,
            mode = WorkspaceFileChangeMode.CREATE,
            baseRevisionFingerprint = null,
            createContent = content,
        )
    }

    private fun generatedRepository(model: EntityModel): String? = runCatching {
        when (model.sourceLanguage) {
            EntitySourceLanguage.JAVA -> DataRepositoryGenerator.generate(model)
            EntitySourceLanguage.KOTLIN -> KotlinDataRepositoryGenerator.generate(model)
        }
    }.getOrNull()

    private fun interfaceBody(source: String): String? {
        val declaration = source.indexOf("interface ")
        if (declaration < 0) return null
        val open = source.indexOf('{', declaration)
        val close = source.lastIndexOf('}')
        if (open < 0 || close <= open) return null
        return source.substring(open + 1, close).trimIndent().trim()
    }

    private fun imports(source: String): Set<String> = IMPORT.findAll(source)
        .map { it.groupValues[1] }
        .toSet()

    private fun importEdit(
        source: String,
        missing: Set<String>,
        language: EntitySourceLanguage,
    ): WorkspaceTextEdit? {
        if (missing.isEmpty()) return null
        val matches = IMPORT.findAll(source).toList()
        val insertion = if (matches.isNotEmpty()) {
            source.indexOf('\n', matches.last().range.last + 1)
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: source.length
        } else {
            val packageMatch = PACKAGE.find(source)
            packageMatch?.let {
                source.indexOf('\n', it.range.last + 1)
                    .takeIf { offset -> offset >= 0 }
                    ?.plus(1)
            } ?: 0
        }
        val suffix = if (language == EntitySourceLanguage.JAVA) ";" else ""
        val replacement = missing.sorted().joinToString(
            separator = "\n",
            postfix = "\n",
        ) { "import $it$suffix" }
        return WorkspaceTextEdit(insertion, insertion, "", replacement)
    }

    private fun applyEdits(source: String, edits: List<WorkspaceTextEdit>): String {
        val result = StringBuilder(source)
        edits.sortedByDescending(WorkspaceTextEdit::startOffset).forEach { edit ->
            result.replace(edit.startOffset, edit.endOffset, edit.replacement)
        }
        return result.toString()
    }

    private fun syntaxIssue(
        fileName: String,
        language: EntitySourceLanguage,
        content: String,
    ): WorkspaceChangeIssue? {
        val fileType = when (language) {
            EntitySourceLanguage.JAVA -> JavaFileType.INSTANCE
            EntitySourceLanguage.KOTLIN -> FileTypeManager.getInstance().getFileTypeByExtension("kt")
                .takeIf { it.name.contains("kotlin", ignoreCase = true) }
                ?: return WorkspaceChangeIssue(
                    "JVW-REPOSITORY-KOTLIN-MISSING",
                    "Kotlin repository updates require the bundled IntelliJ Kotlin plugin.",
                )
        }
        val psi = PsiFileFactory.getInstance(project).createFileFromText(fileName, fileType, content)
        val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java) ?: return null
        return WorkspaceChangeIssue(
            "JVW-REPOSITORY-GENERATED-SYNTAX",
            "Proposed $fileName source is invalid: ${error.errorDescription}.",
        )
    }

    private fun read(relativePath: String): String? =
        ProjectFileResolver.getInstance(project).resolveFile(relativePath)?.file
            ?.let(ProjectSourceText::read)

    private fun rejected(
        code: String,
        message: String,
        path: String? = null,
    ) = DataRepositoryChangeProposal(
        changeSet = null,
        issues = listOf(WorkspaceChangeIssue(code, message, path)),
    )

    private fun rejectedPreview(issues: List<WorkspaceChangeIssue>) =
        WorkspaceChangePreviewResponse(
            accepted = false,
            changeSetId = "data-repository:rejected",
            label = "Data repository change rejected",
            planDigest = null,
            files = emptyList(),
            issues = issues,
        )

    private fun rejectedPrepared(issues: List<WorkspaceChangeIssue>) =
        PreparedWorkspaceChange(
            plan = WorkspaceChangePlan(
                accepted = false,
                changeSetId = "data-repository:rejected",
                label = "Data repository change rejected",
                planDigest = null,
                files = emptyList(),
                issues = issues,
            ),
            baseDir = null,
        )

    companion object {
        private val IMPORT = Regex(
            """(?m)^\s*import\s+(?:static\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;?""",
        )
        private val PACKAGE = Regex("""(?m)^\s*package\s+[A-Za-z_$][\w$.]*\s*;?""")

        fun getInstance(project: Project): DataRepositoryChangeService =
            project.getService(DataRepositoryChangeService::class.java)
    }
}

data class DataRepositoryChangeRequest(
    val entitySource: SourceLocator,
    val repositorySource: SourceLocator? = null,
    val config: DataRepositoryConfig,
)

data class DataRepositoryApplyRequest(
    val change: DataRepositoryChangeRequest,
    val expectedPlanDigest: String,
)

data class DataRepositoryChangeProposal(
    val changeSet: WorkspaceChangeSet?,
    val issues: List<WorkspaceChangeIssue>,
)
