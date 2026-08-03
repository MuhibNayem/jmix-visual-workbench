package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.util.ProcessingContext
import java.util.Locale

/**
 * Profile-safe references from Jmix application properties to REST descriptor
 * resources. Discovery is limited to a dedicated content index; completion on
 * every keystroke never enumerates arbitrary XML or the whole repository.
 */
class JmixConfigurationReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withParent(Property::class.java),
            JmixConfigurationReferenceProvider,
        )
    }
}

internal object JmixConfigurationReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        if (element.javaClass.simpleName != "PropertyValueImpl") {
            return PsiReference.EMPTY_ARRAY
        }
        val property = element.parent as? Property ?: return PsiReference.EMPTY_ARRAY
        val kind = property.key?.jmixRestDescriptorKind()
            ?: return PsiReference.EMPTY_ARRAY
        val valueRange = ElementManipulators.getValueTextRange(element)
        val rawValue = valueRange.substring(element.text)
        if (rawValue.isBlank()) {
            return arrayOf(
                JmixConfigurationResourceReference(
                    element,
                    valueRange,
                    "",
                    kind,
                ),
            )
        }
        return splitConfigurationResourceSegments(rawValue)
            .mapNotNull { segment ->
                val trimmed = segment.value.trim()
                if (trimmed.isBlank() ||
                    trimmed.equals("none", ignoreCase = true) ||
                    trimmed.startsWith("file:") ||
                    "\${" in trimmed
                ) {
                    return@mapNotNull null
                }
                val prefixLength = when {
                    trimmed.startsWith("classpath:") -> "classpath:".length
                    else -> 0
                }
                val leadingSlash = trimmed
                    .substring(prefixLength)
                    .takeWhile { it == '/' }
                    .length
                val pathStartInSegment = segment.value.indexOfFirst {
                    !it.isWhitespace()
                }.coerceAtLeast(0) + prefixLength + leadingSlash
                val path = trimmed.substring(prefixLength)
                    .trimStart('/')
                    .replace('\\', '/')
                val start = valueRange.startOffset + segment.startOffset +
                    pathStartInSegment
                JmixConfigurationResourceReference(
                    element,
                    TextRange(start, start + path.length),
                    path,
                    kind,
                )
            }
            .toTypedArray()
    }
}

internal class JmixConfigurationResourceReference(
    element: PsiElement,
    range: TextRange,
    private val configuredPath: String,
    internal val expectedKind: JmixRestDescriptorKind,
) : PsiPolyVariantReferenceBase<PsiElement>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateDeclarations()
            .filter { declaration ->
                declaration.kind == expectedKind &&
                    declaration.matches(configuredPath)
            }
            .map { PsiElementResolveResult(it.file) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateDeclarations()
            .asSequence()
            .filter { it.kind == expectedKind }
            .map { declaration ->
                LookupElementBuilder.create(
                    declaration.file,
                    declaration.preferredPath,
                ).withTypeText(
                    declaration.file.virtualFile.path,
                    true,
                )
            }
            .distinctBy { it.lookupString }
            .take(JMIX_CONFIGURATION_COMPLETION_LIMIT)
            .toList()
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val parentPath = configuredPath.substringBeforeLast('/', "")
        val replacement = if (parentPath.isBlank()) {
            newElementName
        } else {
            "$parentPath/$newElementName"
        }
        return ElementManipulators.handleContentChange(
            element,
            rangeInElement,
            replacement,
        )
    }

    internal fun candidateDeclarations(): List<JmixRestDescriptorDeclaration> =
        JmixRestConfigurationSymbolService.getInstance(element.project).descriptors()

    internal fun samePathDeclarations(): List<JmixRestDescriptorDeclaration> =
        candidateDeclarations().filter { it.matches(configuredPath) }

    internal fun configuredPath(): String = configuredPath
}

@Service(Service.Level.PROJECT)
internal class JmixRestConfigurationSymbolService(
    private val project: Project,
) {
    @Volatile
    private var cache: JmixRestDescriptorCache? = null

    fun descriptors(): List<JmixRestDescriptorDeclaration> {
        if (DumbService.isDumb(project)) return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        ensureJmixCandidateIndexUpToDate(
            project,
            JmixRestDescriptorCandidateFileIndex.NAME,
            scope,
        )
        val stamp = jmixCandidateIndexStamp(
            project,
            JmixRestDescriptorCandidateFileIndex.NAME,
        )
        cache?.takeIf { it.stamp == stamp }?.let { return it.descriptors }

        val manager = PsiManager.getInstance(project)
        val descriptors = indexedJmixCandidateFiles(
            project,
            JmixRestDescriptorCandidateFileIndex.NAME,
            scope,
        ).asSequence()
            .onEach { ProgressManager.checkCanceled() }
            .mapNotNull(manager::findFile)
            .filterIsInstance<XmlFile>()
            .mapNotNull { file ->
                val kind = when {
                    file.isJmixRestServicesDescriptor() ->
                        JmixRestDescriptorKind.SERVICES

                    file.isJmixRestQueriesDescriptor() ->
                        JmixRestDescriptorKind.QUERIES

                    else -> null
                } ?: return@mapNotNull null
                val paths = file.jmixClasspathResourcePaths()
                paths.firstOrNull()?.let { preferred ->
                    JmixRestDescriptorDeclaration(
                        kind = kind,
                        file = file,
                        preferredPath = preferred,
                        logicalPaths = paths.toSet(),
                    )
                }
            }
            .sortedWith(
                compareBy<JmixRestDescriptorDeclaration> { it.kind.name }
                    .thenBy { it.preferredPath }
                    .thenBy { it.file.virtualFile.path },
            )
            .toList()
        cache = JmixRestDescriptorCache(
            jmixCandidateIndexStamp(
                project,
                JmixRestDescriptorCandidateFileIndex.NAME,
            ),
            descriptors,
        )
        return descriptors
    }

    companion object {
        fun getInstance(project: Project): JmixRestConfigurationSymbolService =
            project.getService(JmixRestConfigurationSymbolService::class.java)
    }
}

internal enum class JmixRestDescriptorKind {
    SERVICES,
    QUERIES,
}

internal data class JmixRestDescriptorDeclaration(
    val kind: JmixRestDescriptorKind,
    val file: XmlFile,
    val preferredPath: String,
    val logicalPaths: Set<String>,
) {
    fun matches(path: String): Boolean =
        path.replace('\\', '/').trimStart('/') in logicalPaths
}

private data class JmixRestDescriptorCache(
    val stamp: JmixCandidateIndexStamp,
    val descriptors: List<JmixRestDescriptorDeclaration>,
)

class JmixConfigurationReferenceInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element.javaClass.simpleName != "PropertyValueImpl") return
                val property = element.parent as? Property ?: return
                val key = property.key ?: return
                val expectedKind = key.jmixRestDescriptorKind() ?: return
                val references = element.references
                    .filterIsInstance<JmixConfigurationResourceReference>()
                references.forEach { reference ->
                    val path = reference.configuredPath()
                    if (path.isBlank()) {
                        holder.registerProblem(
                            element,
                            reference.rangeInElement,
                            "Jmix REST configuration resource must not be blank",
                        )
                        return@forEach
                    }
                    val samePath = reference.samePathDeclarations()
                    val expected = samePath.filter { it.kind == expectedKind }
                    when {
                        expected.size > 1 ->
                            holder.registerProblem(
                                element,
                                reference.rangeInElement,
                                "Ambiguous Jmix REST configuration resource '$path': " +
                                    "${expected.size} classpath files match",
                            )

                        expected.isEmpty() && samePath.isNotEmpty() -> {
                            val actual = samePath.map { it.kind.displayName }
                                .distinct()
                                .joinToString()
                            holder.registerProblem(
                                element,
                                reference.rangeInElement,
                                "Jmix property '$key' requires a ${expectedKind.displayName} " +
                                    "descriptor, but '$path' is $actual",
                            )
                        }

                        expected.isEmpty() ->
                            holder.registerConfigurationReferenceProblem(
                                element,
                                reference,
                                "Unresolved Jmix REST configuration resource '$path'",
                            )
                    }
                }
                val duplicatePaths = references.groupBy {
                    it.configuredPath().replace('\\', '/').trimStart('/')
                }.filter { (path, matches) -> path.isNotBlank() && matches.size > 1 }
                duplicatePaths.forEach { (path, matches) ->
                    matches.forEach { reference ->
                        holder.registerProblem(
                            element,
                            reference.rangeInElement,
                            "Duplicate Jmix REST configuration resource '$path'",
                        )
                    }
                }
            }
        }
}

private fun ProblemsHolder.registerConfigurationReferenceProblem(
    valueElement: PsiElement,
    reference: JmixConfigurationResourceReference,
    message: String,
) {
    val path = reference.configuredPath()
    val closest = reference.candidateDeclarations()
        .asSequence()
        .filter { it.kind == reference.expectedKind }
        .map { declaration ->
            declaration.preferredPath to
                jmixEditDistance(path, declaration.preferredPath)
        }
        .filter { (_, distance) -> distance <= JMIX_CONFIGURATION_FIX_DISTANCE }
        .minWithOrNull(
            compareBy<Pair<String, Int>> { it.second }.thenBy { it.first },
        )
        ?.first
    registerProblem(
        valueElement,
        reference.rangeInElement,
        message,
        *closest?.let {
            arrayOf(ReplaceJmixConfigurationResourceQuickFix(it))
        }.orEmpty(),
    )
}

private class ReplaceJmixConfigurationResourceQuickFix(
    private val replacement: String,
) : LocalQuickFix {
    override fun getFamilyName(): String =
        "Replace with existing Jmix configuration resource"

    override fun getName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val valueElement = descriptor.psiElement
            .takeIf { it.javaClass.simpleName == "PropertyValueImpl" }
            ?: return
        val reference = valueElement.references
            .filterIsInstance<JmixConfigurationResourceReference>()
            .firstOrNull {
                it.rangeInElement == descriptor.textRangeInElement
            }
            ?: return
        reference.handleElementRename(replacement)
    }
}

internal fun XmlFile.isJmixRestQueriesDescriptor(): Boolean {
    val root = rootTag ?: return false
    if (root.localName != "queries") return false
    val namespace = root.namespace.orEmpty().lowercase(Locale.ROOT)
    return namespace.contains("jmix.io/schema/rest/queries") ||
        namespace.isBlank() && name.lowercase(Locale.ROOT).contains("rest-queries")
}

private fun XmlFile.jmixClasspathResourcePaths(): List<String> {
    val file = virtualFile
    val candidates = linkedSetOf<String>()
    val normalizedPath = file.path.replace('\\', '/')
    val resourcesMarker = "/resources/"
    val markerIndex = normalizedPath.lastIndexOf(resourcesMarker)
    if (markerIndex >= 0) {
        normalizedPath.substring(markerIndex + resourcesMarker.length)
            .trimStart('/')
            .takeIf(String::isNotBlank)
            ?.let(candidates::add)
    }
    ProjectFileIndex.getInstance(project)
        .getSourceRootForFile(file)
        ?.let { sourceRoot ->
            VfsUtilCore.getRelativePath(file, sourceRoot, '/')
                ?.trimStart('/')
                ?.takeIf(String::isNotBlank)
                ?.let(candidates::add)
        }
    return candidates.toList()
}

private data class ConfigurationResourceSegment(
    val startOffset: Int,
    val value: String,
)

private fun splitConfigurationResourceSegments(
    value: String,
): List<ConfigurationResourceSegment> {
    val result = mutableListOf<ConfigurationResourceSegment>()
    var start = 0
    var escaped = false
    value.forEachIndexed { index, character ->
        when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == ',' -> {
                result += ConfigurationResourceSegment(
                    startOffset = start,
                    value = value.substring(start, index),
                )
                start = index + 1
            }
        }
    }
    result += ConfigurationResourceSegment(
        startOffset = start,
        value = value.substring(start),
    )
    return result
}

private fun String.jmixRestDescriptorKind(): JmixRestDescriptorKind? =
    when (this) {
        "jmix.rest.services-config",
        "jmix.rest.servicesConfig"
        -> JmixRestDescriptorKind.SERVICES

        "jmix.rest.queries-config",
        "jmix.rest.queriesConfig"
        -> JmixRestDescriptorKind.QUERIES

        else -> null
    }

private val JmixRestDescriptorKind.displayName: String
    get() = when (this) {
        JmixRestDescriptorKind.SERVICES -> "REST services"
        JmixRestDescriptorKind.QUERIES -> "REST queries"
    }

private const val JMIX_CONFIGURATION_COMPLETION_LIMIT = 1_000
private const val JMIX_CONFIGURATION_FIX_DISTANCE = 8
