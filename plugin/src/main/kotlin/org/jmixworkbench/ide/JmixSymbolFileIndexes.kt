package org.jmixworkbench.ide

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput
import java.util.Locale

/**
 * Persistent, content-sensitive candidate-file indexes for native Jmix
 * assistance.
 *
 * The indexers deliberately avoid PSI. They perform a conservative text-level
 * classification and let the reference services validate candidates with PSI.
 * False positives cost one candidate parse; false negatives would hide a real
 * symbol, so markers intentionally err on the side of inclusion.
 *
 * Each artifact family owns an independent index. Consequently, editing a
 * message bundle cannot invalidate entity or menu symbol inventories.
 */
abstract class JmixCandidateFileIndex(
    private val id: ID<String, Long>,
    private val acceptedExtensions: Set<String>,
) : FileBasedIndexExtension<String, Long>() {

    final override fun getName(): ID<String, Long> = id

    final override fun getIndexer(): DataIndexer<String, Long, FileContent> =
        DataIndexer { input ->
            ProgressManager.checkCanceled()
            val text = input.contentAsText
            if (isCandidate(input.file, text)) {
                mapOf(PRESENCE_KEY to contentFingerprint(text))
            } else {
                emptyMap()
            }
        }

    final override fun getKeyDescriptor(): KeyDescriptor<String> =
        EnumeratorStringDescriptor.INSTANCE

    final override fun getValueExternalizer(): DataExternalizer<Long> =
        LongExternalizer

    final override fun getVersion(): Int = INDEX_VERSION

    final override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file ->
            file.extension?.lowercase(Locale.ROOT) in acceptedExtensions
        }

    final override fun dependsOnFileContent(): Boolean = true

    protected abstract fun isCandidate(
        file: VirtualFile,
        text: CharSequence,
    ): Boolean

    private object LongExternalizer : DataExternalizer<Long> {
        override fun save(out: DataOutput, value: Long) {
            out.writeLong(value)
        }

        override fun read(input: DataInput): Long = input.readLong()
    }

    companion object {
        internal const val PRESENCE_KEY = "present"
        private const val INDEX_VERSION = 1

        /**
         * FNV-1a over UTF-16 code units. The value is not a security digest; it
         * makes the forward-index value change when a candidate file changes,
         * which advances the per-index modification stamp used by symbol
         * caches.
         */
        private fun contentFingerprint(text: CharSequence): Long {
            var hash = -3750763034362895579L
            text.forEach { character ->
                hash = hash xor character.code.toLong()
                hash *= 1099511628211L
            }
            return hash
        }
    }
}

class JmixEntityCandidateFileIndex : JmixCandidateFileIndex(
    NAME,
    JVM_EXTENSIONS,
) {
    override fun isCandidate(file: VirtualFile, text: CharSequence): Boolean =
        text.containsAnnotation("JmixEntity") ||
            text.containsAnnotation("Entity") ||
            text.contains("io.jmix.core.metamodel.annotation.JmixEntity") ||
            text.contains("jakarta.persistence.Entity") ||
            text.contains("javax.persistence.Entity")

    companion object {
        @JvmField
        val NAME: ID<String, Long> =
            ID.create("org.jmixworkbench.entityCandidateFile")
    }
}

class JmixViewControllerCandidateFileIndex : JmixCandidateFileIndex(
    NAME,
    JVM_EXTENSIONS,
) {
    override fun isCandidate(file: VirtualFile, text: CharSequence): Boolean =
        text.contains("ViewController") || text.contains("UiController")

    companion object {
        @JvmField
        val NAME: ID<String, Long> =
            ID.create("org.jmixworkbench.viewControllerCandidateFile")
    }
}

class JmixSpecificPolicyCandidateFileIndex : JmixCandidateFileIndex(
    NAME,
    JVM_EXTENSIONS,
) {
    override fun isCandidate(file: VirtualFile, text: CharSequence): Boolean =
        text.contains("SpecificPolicy")

    companion object {
        @JvmField
        val NAME: ID<String, Long> =
            ID.create("org.jmixworkbench.specificPolicyCandidateFile")
    }
}

class JmixSpringBeanCandidateFileIndex : JmixCandidateFileIndex(
    NAME,
    JVM_EXTENSIONS,
) {
    override fun isCandidate(file: VirtualFile, text: CharSequence): Boolean =
        SPRING_BEAN_ANNOTATIONS.any(text::containsAnnotation) ||
            SPRING_BEAN_QUALIFIED_MARKERS.any(text::contains)

    companion object {
        @JvmField
        val NAME: ID<String, Long> =
            ID.create("org.jmixworkbench.springBeanCandidateFile")
    }
}

class JmixMenuCandidateFileIndex : JmixCandidateFileIndex(
    NAME,
    XML_EXTENSIONS,
) {
    override fun isCandidate(file: VirtualFile, text: CharSequence): Boolean =
        firstXmlElementLocalName(text) in setOf("menu-config", "menu")

    companion object {
        @JvmField
        val NAME: ID<String, Long> =
            ID.create("org.jmixworkbench.menuCandidateFile")
    }
}

class JmixFetchPlanCandidateFileIndex : JmixCandidateFileIndex(
    NAME,
    XML_EXTENSIONS,
) {
    override fun isCandidate(file: VirtualFile, text: CharSequence): Boolean =
        firstXmlElementLocalName(text) in setOf("fetchPlans", "fetch-plans")

    companion object {
        @JvmField
        val NAME: ID<String, Long> =
            ID.create("org.jmixworkbench.fetchPlanCandidateFile")
    }
}

class JmixFlowUiDescriptorCandidateFileIndex : JmixCandidateFileIndex(
    NAME,
    XML_EXTENSIONS,
) {
    override fun isCandidate(file: VirtualFile, text: CharSequence): Boolean =
        firstXmlElementLocalName(text) in setOf("view", "fragment")

    companion object {
        @JvmField
        val NAME: ID<String, Long> =
            ID.create("org.jmixworkbench.flowUiDescriptorCandidateFile")
    }
}

class JmixMessageBundleCandidateFileIndex : JmixCandidateFileIndex(
    NAME,
    PROPERTIES_EXTENSIONS,
) {
    override fun isCandidate(file: VirtualFile, text: CharSequence): Boolean =
        MESSAGE_BUNDLE_NAME.matches(file.name)

    companion object {
        @JvmField
        val NAME: ID<String, Long> =
            ID.create("org.jmixworkbench.messageBundleCandidateFile")
    }
}

private val JVM_EXTENSIONS = setOf("java", "kt")
private val XML_EXTENSIONS = setOf("xml")
private val PROPERTIES_EXTENSIONS = setOf("properties")
private val SPRING_BEAN_ANNOTATIONS = setOf(
    "Component",
    "Service",
    "Repository",
    "Controller",
    "RestController",
    "Configuration",
    "Bean",
    "Named",
)
private val SPRING_BEAN_QUALIFIED_MARKERS = setOf(
    "org.springframework.stereotype.Component",
    "org.springframework.stereotype.Service",
    "org.springframework.stereotype.Repository",
    "org.springframework.stereotype.Controller",
    "org.springframework.web.bind.annotation.RestController",
    "org.springframework.context.annotation.Configuration",
    "org.springframework.context.annotation.Bean",
    "jakarta.inject.Named",
    "javax.inject.Named",
)
private val MESSAGE_BUNDLE_NAME =
    Regex("""messages(?:_[A-Za-z0-9_-]+)?\.properties""")

/**
 * Returns the first real element name without constructing DOM/PSI. XML
 * declarations, comments and declarations are skipped. Namespace prefixes are
 * removed because Jmix descriptors are identified by local root name and
 * validated by the PSI-level predicate after index lookup.
 */
internal fun firstXmlElementLocalName(text: CharSequence): String? {
    var cursor = 0
    while (cursor < text.length) {
        ProgressManager.checkCanceled()
        val open = text.indexOf('<', cursor)
        if (open < 0 || open + 1 >= text.length) return null
        when {
            text.regionMatches(open, "<!--") -> {
                val close = text.indexOf("-->", open + 4)
                if (close < 0) return null
                cursor = close + 3
            }

            text.regionMatches(open, "<?") -> {
                val close = text.indexOf("?>", open + 2)
                if (close < 0) return null
                cursor = close + 2
            }

            text.regionMatches(open, "<!") -> {
                val close = findXmlDeclarationEnd(text, open + 2)
                if (close < 0) return null
                cursor = close + 1
            }

            text[open + 1] == '/' -> cursor = open + 2

            else -> {
                var end = open + 1
                while (end < text.length &&
                    !text[end].isWhitespace() &&
                    text[end] != '>' &&
                    text[end] != '/'
                ) {
                    end++
                }
                return text.subSequence(open + 1, end)
                    .toString()
                    .substringAfterLast(':')
                    .takeIf(String::isNotBlank)
            }
        }
    }
    return null
}

private fun findXmlDeclarationEnd(text: CharSequence, start: Int): Int {
    var cursor = start
    var subsetDepth = 0
    var quote: Char? = null
    while (cursor < text.length) {
        val character = text[cursor]
        when {
            quote != null && character == quote -> quote = null
            quote != null -> Unit
            character == '"' || character == '\'' -> quote = character
            character == '[' -> subsetDepth++
            character == ']' && subsetDepth > 0 -> subsetDepth--
            character == '>' && subsetDepth == 0 -> return cursor
        }
        cursor++
    }
    return -1
}

private fun CharSequence.indexOf(
    value: String,
    startIndex: Int,
): Int {
    if (value.isEmpty()) return startIndex.coerceAtMost(length)
    val lastStart = length - value.length
    var cursor = startIndex.coerceAtLeast(0)
    while (cursor <= lastStart) {
        var matched = true
        for (offset in value.indices) {
            if (this[cursor + offset] != value[offset]) {
                matched = false
                break
            }
        }
        if (matched) return cursor
        cursor++
    }
    return -1
}

private fun CharSequence.regionMatches(
    startIndex: Int,
    value: String,
): Boolean {
    if (startIndex < 0 || startIndex + value.length > length) return false
    return value.indices.all { offset -> this[startIndex + offset] == value[offset] }
}

private fun CharSequence.containsAnnotation(shortName: String): Boolean {
    val marker = "@$shortName"
    var cursor = indexOf(marker, 0)
    while (cursor >= 0) {
        val after = cursor + marker.length
        if (after >= length ||
            !this[after].isLetterOrDigit() && this[after] != '_'
        ) {
            return true
        }
        cursor = indexOf(marker, after)
    }
    return false
}

internal data class JmixCandidateIndexStamp(
    val content: Long,
    val projectRoots: Long,
)

internal fun ensureJmixCandidateIndexUpToDate(
    project: Project,
    indexId: ID<String, Long>,
    scope: GlobalSearchScope,
) {
    ProgressManager.checkCanceled()
    FileBasedIndex.getInstance().processValues(
        indexId,
        JmixCandidateFileIndex.PRESENCE_KEY,
        null,
        { _, _ -> false },
        scope,
    )
}

/**
 * Returns only files classified for the requested artifact family. Calling
 * this method also asks IntelliJ to bring that one index up to date for the
 * requested scope; it never enumerates files by extension.
 */
internal fun indexedJmixCandidateFiles(
    project: Project,
    indexId: ID<String, Long>,
    scope: GlobalSearchScope,
): List<VirtualFile> {
    ProgressManager.checkCanceled()
    ensureJmixCandidateIndexUpToDate(project, indexId, scope)
    return FileBasedIndex.getInstance()
        .getContainingFiles(
            indexId,
            JmixCandidateFileIndex.PRESENCE_KEY,
            scope,
        )
        .asSequence()
        .onEach { ProgressManager.checkCanceled() }
        .sortedBy(VirtualFile::getPath)
        .toList()
}

internal fun jmixCandidateIndexStamp(
    project: Project,
    indexId: ID<String, Long>,
): JmixCandidateIndexStamp =
    JmixCandidateIndexStamp(
        content = FileBasedIndex.getInstance()
            .getIndexModificationStamp(indexId, project),
        projectRoots = ProjectRootModificationTracker.getInstance(project)
            .modificationCount,
    )
