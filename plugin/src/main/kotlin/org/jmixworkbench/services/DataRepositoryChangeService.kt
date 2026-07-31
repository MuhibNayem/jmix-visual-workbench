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
import org.jmixworkbench.model.RepositoryMethod

/**
 * Revision-bound repository creation and lossless handwritten-source updates.
 *
 * Existing callable declarations are never regenerated. Supported repository
 * metadata is changed at exact annotation ranges, while new abstract methods
 * are inserted before the exact interface closing brace. Custom annotations,
 * default methods, native queries, comments, modifiers, and manual formatting
 * outside those annotation ranges remain byte-preserved.
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
        val baselineMethods = request.repositorySource
            ?.let { locator ->
                schema.repositories.singleOrNull { it.sourceLocator == locator }
                    ?.config
                    ?.methods
            }
            .orEmpty()
        val diagnostics = analyzed.diagnostics.map { diagnostic ->
            val methodIndex = diagnostic.methodIndex
            val sourceOwned = request.repositorySource != null &&
                methodIndex != null &&
                methodIndex in baselineMethods.indices &&
                request.config.methods.getOrNull(methodIndex) == baselineMethods[methodIndex]
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
        val baselineMethods = request.repositorySource
            ?.let { locator ->
                schema.repositories.singleOrNull { it.sourceLocator == locator }
                    ?.config
                    ?.methods
            }
            .orEmpty()
        val blockingDiagnostics = semantics.diagnostics.filter { diagnostic ->
            val methodIndex = diagnostic.methodIndex
            val unchangedExistingMethod = methodIndex != null &&
                methodIndex in baselineMethods.indices &&
                request.config.methods.getOrNull(methodIndex) == baselineMethods[methodIndex]
            diagnostic.severity == RepositorySemanticSeverity.ERROR &&
                (
                    request.repositorySource == null ||
                        !unchangedExistingMethod
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
        val repositoryModel = model.copy(sourceLanguage = snapshot.sourceLanguage)
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
        val parsedSupportedMethods = parsed.methods.filter { it.method != null }
        if (parsedSupportedMethods.size != baseline.size) {
            return rejected(
                "JVW-REPOSITORY-METHOD-EVIDENCE",
                "Repository method ordering could not be paired with exact source evidence.",
                locator.relativePath,
            )
        }
        if (request.config.methods.size < baseline.size) {
            return rejected(
                "JVW-REPOSITORY-DELETE-NATIVE",
                "Existing repository methods cannot be deleted through an additive update. Use IntelliJ Safe Delete.",
                locator.relativePath,
            )
        }
        val modifications = baseline.indices.filter { index ->
            request.config.methods.getOrNull(index) != baseline[index]
        }
        modifications.forEach { index ->
            val baselineMethod = baseline[index]
            val requestedMethod = request.config.methods[index]
            val evidence = parsedSupportedMethods[index]
            if (!evidence.editable ||
                evidence.sourceStartOffset == null ||
                evidence.sourceEndOffset == null
            ) {
                return rejected(
                    "JVW-REPOSITORY-EXISTING-METHOD-LOCKED",
                    "Existing method '${baselineMethod.name}' is source-owned and cannot be rewritten safely. " +
                        (evidence.issue ?: "Use IntelliJ source tools for this declaration."),
                    locator.relativePath,
                )
            }
            if (!sameCallableContract(baselineMethod, requestedMethod)) {
                return rejected(
                    "JVW-REPOSITORY-METHOD-REFACTOR-NATIVE",
                    "Changing the name, return type, parameter names/types/nullability, or parameter roles of " +
                        "'${baselineMethod.name}' requires IntelliJ refactoring so callers participate.",
                    locator.relativePath,
                )
            }
        }
        val additions = request.config.methods.drop(baseline.size)
        if (additions.isEmpty() && modifications.isEmpty()) {
            return rejected(
                "JVW-REPOSITORY-NOOP",
                "No repository method changes were requested.",
                locator.relativePath,
            )
        }
        val requestedSignatures = request.config.methods.map(RepositorySourceParser::signature)
        if (requestedSignatures.distinct().size != requestedSignatures.size) {
            return rejected(
                "JVW-REPOSITORY-METHOD-COLLISION",
                "Repository methods contain a duplicate JVM signature.",
                locator.relativePath,
            )
        }
        val changedMethods = modifications.map(request.config.methods::get) + additions
        val changedConfig = request.config.copy(methods = changedMethods.toMutableList())
        val generated = generatedRepository(repositoryModel.copy(dataRepository = changedConfig))
            ?: return rejected(
                "JVW-REPOSITORY-METHOD-GENERATION",
                "One or more changed repository methods are invalid.",
            )
        val missingImports = imports(generated) - imports(content)
        val edits = mutableListOf<WorkspaceTextEdit>()
        importEdit(content, missingImports, snapshot.sourceLanguage)?.let(edits::add)
        modifications.forEach { index ->
            val generatedMethod = generatedMethodBody(
                model = repositoryModel,
                config = request.config,
                method = request.config.methods[index],
            ) ?: return rejected(
                "JVW-REPOSITORY-METHOD-GENERATION",
                "Changed method '${request.config.methods[index].name}' could not be isolated safely.",
                locator.relativePath,
            )
            sourceMethodEdit(
                content = content,
                evidence = parsedSupportedMethods[index],
                baselineMethod = baseline[index],
                requestedMethod = request.config.methods[index],
                generatedMethod = generatedMethod,
                language = snapshot.sourceLanguage,
            )?.let(edits::add) ?: return rejected(
                "JVW-REPOSITORY-METHOD-EVIDENCE",
                "Changed method '${request.config.methods[index].name}' contains metadata that cannot be " +
                    "edited without rewriting handwritten source. Use native IntelliJ editing for this declaration.",
                locator.relativePath,
            )
        }
        if (additions.isNotEmpty()) {
            val additionConfig = request.config.copy(methods = additions.toMutableList())
            val additionSource = generatedRepository(repositoryModel.copy(dataRepository = additionConfig))
                ?: return rejected(
                    "JVW-REPOSITORY-METHOD-GENERATION",
                    "One or more new repository methods are invalid.",
                )
            val generatedBody = interfaceBody(additionSource)
                ?: return rejected(
                    "JVW-REPOSITORY-METHOD-GENERATION",
                    "Generated repository methods could not be isolated safely.",
                )
            edits += WorkspaceTextEdit(
                startOffset = parsed.bodyCloseOffset,
                endOffset = parsed.bodyCloseOffset,
                expectedText = "",
                replacement = generatedBody.trimEnd().prependIndent("    ")
                    .let { body -> "\n$body\n" },
            )
        }
        val resulting = applyEdits(content, edits)
        syntaxIssue(
            locator.relativePath.substringAfterLast('/'),
            snapshot.sourceLanguage,
            resulting,
        )?.let { return DataRepositoryChangeProposal(null, listOf(it)) }
        val identity = listOf(
            locator.relativePath,
            locator.revisionFingerprint,
            modifications.joinToString("\u0000") { index ->
                RepositorySourceParser.signature(request.config.methods[index])
            },
            additions.joinToString("\u0000", transform = RepositorySourceParser::signature),
            resulting,
        ).joinToString("\u0000")
        val changeCount = modifications.size + additions.size
        return DataRepositoryChangeProposal(
            changeSet = WorkspaceChangeSet(
                id = "data-repository:${CanonicalDiscoveryJson.sha256(identity).take(24)}",
                label = "Update $changeCount method(s) in ${snapshot.interfaceName}",
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

    private fun sameCallableContract(
        baseline: RepositoryMethod,
        requested: RepositoryMethod,
    ): Boolean =
        baseline.name == requested.name &&
            baseline.returnType == requested.returnType &&
            baseline.parameters.size == requested.parameters.size &&
            baseline.parameters.zip(requested.parameters).all { (left, right) ->
                left.name == right.name &&
                    left.type == right.type &&
                    left.nullable == right.nullable &&
                    left.role == right.role
            }

    private fun generatedMethodBody(
        model: EntityModel,
        config: DataRepositoryConfig,
        method: RepositoryMethod,
    ): String? {
        val singleMethodConfig = config.copy(methods = mutableListOf(method))
        val source = generatedRepository(model.copy(dataRepository = singleMethodConfig))
            ?: return null
        return interfaceBody(source)
    }

    private fun sourceMethodEdit(
        content: String,
        evidence: ParsedRepositoryMethod,
        baselineMethod: RepositoryMethod,
        requestedMethod: RepositoryMethod,
        generatedMethod: String,
        language: EntitySourceLanguage,
    ): WorkspaceTextEdit? {
        val methodStart = evidence.sourceStartOffset ?: return null
        val methodEnd = evidence.sourceEndOffset ?: return null
        if (methodStart !in 0..content.length ||
            methodEnd !in methodStart..content.length
        ) {
            return null
        }
        val lineStart = content.lastIndexOf('\n', (methodStart - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val indentation = content.substring(lineStart, methodStart)
        if (indentation.any { !it.isWhitespace() }) return null
        val expected = content.substring(lineStart, methodEnd)
        val replacementBody = preservingMethodMetadataEdit(
            source = content.substring(methodStart, methodEnd),
            baseline = baselineMethod,
            requested = requestedMethod,
            generatedMethod = generatedMethod,
            language = language,
            baseIndent = indentation,
        ) ?: return null
        val replacement = indentation + replacementBody
        return WorkspaceTextEdit(
            startOffset = lineStart,
            endOffset = methodEnd,
            expectedText = expected,
            replacement = replacement,
        )
    }

    private fun preservingMethodMetadataEdit(
        source: String,
        baseline: RepositoryMethod,
        requested: RepositoryMethod,
        generatedMethod: String,
        language: EntitySourceLanguage,
        baseIndent: String,
    ): String? {
        if (baseline.description != requested.description) return null
        if (baseline.parameters.zip(requested.parameters).any { (left, right) ->
                left.bindingName != right.bindingName
            }
        ) {
            return null
        }
        val changes = listOf(
            AnnotationMetadataChange(
                annotationName = "Query",
                changed = baseline.queryType != requested.queryType ||
                    baseline.query != requested.query ||
                    baseline.queryProperties != requested.queryProperties,
            ),
            AnnotationMetadataChange(
                annotationName = "FetchPlan",
                changed = baseline.fetchPlan != requested.fetchPlan,
            ),
            AnnotationMetadataChange(
                annotationName = "ApplyConstraints",
                changed = baseline.applyConstraints != requested.applyConstraints,
            ),
            AnnotationMetadataChange(
                annotationName = "QueryHints",
                changed = baseline.queryHints != requested.queryHints,
            ),
        ).filter(AnnotationMetadataChange::changed)
        if (changes.isEmpty()) return null

        val replacements = mutableListOf<LocalSourceReplacement>()
        val additions = mutableListOf<String>()
        changes.forEach { change ->
            val existing = annotationSpan(source, change.annotationName)
            val generated = annotationSpan(generatedMethod, change.annotationName)
            if (existing != null && existing.text.hasSourceComment()) return null
            when {
                existing != null && generated != null -> {
                    replacements += LocalSourceReplacement(
                        existing.startOffset,
                        existing.endOffset,
                        preserveAnnotationQualifier(existing.text, generated.text),
                    )
                }
                existing != null -> {
                    replacements += LocalSourceReplacement(
                        existing.startOffset,
                        existing.endOffset,
                        "",
                    )
                }
                generated != null -> additions += generated.text.trim()
            }
        }
        if (additions.isNotEmpty()) {
            val declarationOffset = methodDeclarationOffset(source, requested.name, language)
                ?: return null
            val declarationLineStart = source.lastIndexOf('\n', declarationOffset - 1)
                .let { if (it < 0) 0 else it + 1 }
            val declarationIndent = source.substring(declarationLineStart, declarationOffset)
            if (declarationIndent.any { !it.isWhitespace() }) return null
            val continuationIndent = declarationIndent.ifEmpty { baseIndent }
            replacements += LocalSourceReplacement(
                declarationOffset,
                declarationOffset,
                additions.joinToString("\n$continuationIndent") + "\n$continuationIndent",
            )
        }
        var result = source
        replacements.sortedByDescending(LocalSourceReplacement::startOffset).forEach { replacement ->
            result = result.replaceRange(
                replacement.startOffset,
                replacement.endOffset,
                replacement.text,
            )
        }
        return result.takeIf { it != source }
    }

    private fun annotationSpan(source: String, simpleName: String): SourceAnnotationSpan? {
        val searchable = maskCommentsAndStrings(source)
        val match = ANNOTATION_REFERENCE.findAll(searchable)
            .firstOrNull { it.groupValues[1].substringAfterLast('.') == simpleName }
            ?: return null
        var end = match.range.last + 1
        var cursor = end
        while (cursor < searchable.length && searchable[cursor].isWhitespace()) cursor++
        if (searchable.getOrNull(cursor) == '(') {
            var depth = 0
            var index = cursor
            while (index < searchable.length) {
                when (searchable[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            end = index + 1
                            break
                        }
                    }
                }
                index++
            }
            if (depth != 0) return null
        }
        return SourceAnnotationSpan(
            startOffset = match.range.first,
            endOffset = end,
            text = source.substring(match.range.first, end),
        )
    }

    private fun maskCommentsAndStrings(source: String): String {
        val masked = source.toCharArray()
        var index = 0
        var state = SourceMaskState.CODE
        var escaped = false
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                SourceMaskState.CODE -> when {
                    char == '/' && next == '/' -> {
                        masked[index] = ' '
                        masked[index + 1] = ' '
                        index++
                        state = SourceMaskState.LINE_COMMENT
                    }
                    char == '/' && next == '*' -> {
                        masked[index] = ' '
                        masked[index + 1] = ' '
                        index++
                        state = SourceMaskState.BLOCK_COMMENT
                    }
                    char == '"' -> {
                        masked[index] = ' '
                        escaped = false
                        state = SourceMaskState.STRING
                    }
                    char == '\'' -> {
                        masked[index] = ' '
                        escaped = false
                        state = SourceMaskState.CHAR
                    }
                }
                SourceMaskState.LINE_COMMENT -> {
                    if (char == '\n' || char == '\r') {
                        state = SourceMaskState.CODE
                    } else {
                        masked[index] = ' '
                    }
                }
                SourceMaskState.BLOCK_COMMENT -> {
                    masked[index] = ' '
                    if (char == '*' && next == '/') {
                        masked[index + 1] = ' '
                        index++
                        state = SourceMaskState.CODE
                    }
                }
                SourceMaskState.STRING,
                SourceMaskState.CHAR -> {
                    masked[index] = ' '
                    if (escaped) {
                        escaped = false
                    } else if (char == '\\') {
                        escaped = true
                    } else if (
                        (state == SourceMaskState.STRING && char == '"') ||
                        (state == SourceMaskState.CHAR && char == '\'')
                    ) {
                        state = SourceMaskState.CODE
                    }
                }
            }
            index++
        }
        return String(masked)
    }

    private fun methodDeclarationOffset(
        source: String,
        methodName: String,
        language: EntitySourceLanguage,
    ): Int? {
        val escapedName = Regex.escape(methodName)
        val pattern = if (language == EntitySourceLanguage.KOTLIN) {
            Regex("""\bfun\s+$escapedName\s*\(""")
        } else {
            Regex("""\b$escapedName\s*\(""")
        }
        val match = pattern.findAll(maskCommentsAndStrings(source)).lastOrNull() ?: return null
        val lineStart = source.lastIndexOf('\n', match.range.first - 1)
            .let { if (it < 0) 0 else it + 1 }
        var declarationOffset = lineStart
        while (source.getOrNull(declarationOffset)?.let(Char::isWhitespace) == true &&
            source[declarationOffset] !in setOf('\n', '\r')
        ) {
            declarationOffset++
        }
        return declarationOffset
    }

    private fun preserveAnnotationQualifier(existing: String, generated: String): String {
        val generatedArguments = generated.indexOf('(')
            .takeIf { it >= 0 }
            ?.let(generated::substring)
            .orEmpty()
        val existingHead = existing.substringBefore('(').trimEnd()
        return existingHead + generatedArguments
    }

    private fun String.hasSourceComment(): Boolean =
        contains("//") || contains("/*")

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
            """(?m)^[ \t]*import[ \t]+(?:static[ \t]+)?""" +
                """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)[ \t]*;?[ \t]*$""",
        )
        private val PACKAGE = Regex(
            """(?m)^[ \t]*package[ \t]+[A-Za-z_$][\w$.]*[ \t]*;?[ \t]*$""",
        )
        private val ANNOTATION_REFERENCE = Regex(
            """@([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""",
        )

        fun getInstance(project: Project): DataRepositoryChangeService =
            project.getService(DataRepositoryChangeService::class.java)
    }
}

private data class AnnotationMetadataChange(
    val annotationName: String,
    val changed: Boolean,
)

private data class LocalSourceReplacement(
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

private data class SourceAnnotationSpan(
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

private enum class SourceMaskState {
    CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING,
    CHAR,
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
