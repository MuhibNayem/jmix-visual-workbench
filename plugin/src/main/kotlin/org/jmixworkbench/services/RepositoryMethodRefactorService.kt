package org.jmixworkbench.services

import com.google.gson.annotations.SerializedName
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator

/**
 * Resolves an indexed Jmix repository method for an IntelliJ-native refactoring.
 *
 * The JCEF request is untrusted. A declaration is returned only when its
 * project-contained source, revision, repository identity, method index,
 * indexed signature, PSI owner, name, arity and exact source range all agree.
 * This service never mutates source; IntelliJ's language-aware refactoring
 * action owns preview, usage analysis and the eventual write.
 */
@Service(Service.Level.PROJECT)
class RepositoryMethodRefactorService(
    private val project: Project,
) {
    fun prepare(request: RepositoryMethodRefactorRequest): PreparedRepositoryMethodRefactor {
        val source = request.repositorySource
            ?: return invalidRequest("Repository source evidence is required.")
        val relativePath = runCatching { source.relativePath }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_RELATIVE_PATH_LENGTH }
            ?: return invalidRequest("Repository source path is missing or too long.")
        val revisionFingerprint = runCatching { source.revisionFingerprint }.getOrNull()
            ?.takeIf { SHA_256.matches(it) }
            ?: return invalidRequest("Repository source revision must be a SHA-256 fingerprint.")
        val repositoryQualifiedName = request.repositoryQualifiedName
            ?.takeIf { it.isNotBlank() && it.length <= MAX_QUALIFIED_NAME_LENGTH }
            ?: return invalidRequest("Repository qualified name is missing or too long.")
        val methodIndex = request.methodIndex
            ?.takeIf { it >= 0 }
            ?: return invalidRequest("Repository method index must be zero or greater.")
        val sourceSignature = request.sourceSignature
            ?.takeIf { it.isNotBlank() && it.length <= MAX_SOURCE_SIGNATURE_LENGTH }
            ?: return invalidRequest("Repository method signature is missing or too long.")
        val operation = request.operation
            ?: return invalidRequest("Repository refactoring operation is required.")
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(relativePath)
            ?: return failure(
                "JVW-REPOSITORY-REFACTOR-SOURCE-MISSING",
                "The indexed repository source no longer exists.",
            )
        val file = resolved.file
        if (
            file.isDirectory ||
            file.extension !in setOf("java", "kt") ||
            !VfsUtilCore.isAncestor(resolved.root, file, false)
        ) {
            return failure(
                "JVW-REPOSITORY-REFACTOR-SOURCE-INVALID",
                "Native repository refactoring requires a Java or Kotlin source inside project content.",
            )
        }
        val currentSource = runCatching { ProjectSourceText.read(file) }.getOrElse {
            return failure(
                "JVW-REPOSITORY-REFACTOR-SOURCE-UNREADABLE",
                "The current repository document cannot be read.",
            )
        }
        if (
            revisionFingerprint !=
            CanonicalDiscoveryJson.sha256(currentSource)
        ) {
            return failure(
                "JVW-REPOSITORY-REFACTOR-SOURCE-STALE",
                "The repository changed after indexing. Refresh Entity Designer before refactoring.",
            )
        }
        val snapshot = SchemaWorkspaceService.getInstance(project)
            .load(forceRefresh = false)
            .repositories
            .singleOrNull { repository ->
                repository.sourceLocator == source &&
                    repository.qualifiedName == repositoryQualifiedName
            }
            ?: return failure(
                "JVW-REPOSITORY-REFACTOR-SNAPSHOT-MISSING",
                "The exact indexed repository is missing or ambiguous.",
            )
        val method = snapshot.config.methods.getOrNull(methodIndex)
            ?: return failure(
                "JVW-REPOSITORY-REFACTOR-METHOD-MISSING",
                "The selected repository method is no longer indexed.",
            )
        val evidence = snapshot.methodEvidence.singleOrNull {
            it.methodIndex == methodIndex &&
                it.sourceSignature == sourceSignature
        } ?: return failure(
            "JVW-REPOSITORY-REFACTOR-EVIDENCE-MISMATCH",
            "The submitted method identity does not match the indexed repository evidence.",
        )
        val startOffset = evidence.sourceStartOffset
        val endOffset = evidence.sourceEndOffset
        if (
            startOffset == null ||
            endOffset == null ||
            startOffset !in currentSource.indices ||
            endOffset <= startOffset ||
            endOffset > currentSource.length
        ) {
            return failure(
                "JVW-REPOSITORY-REFACTOR-RANGE-MISSING",
                "The exact source range for this method is unavailable.",
            )
        }
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return failure(
                "JVW-REPOSITORY-REFACTOR-PSI-MISSING",
                "IntelliJ could not create live PSI for the repository source.",
            )
        val anchor = psiFile.findElementAt(startOffset)
            ?: return failure(
                "JVW-REPOSITORY-REFACTOR-ANCHOR-MISSING",
                "The indexed repository method anchor is no longer available.",
            )
        val declaration = when (psiFile) {
            is PsiJavaFile -> resolveJavaMethod(
                anchor = anchor,
                repositoryQualifiedName = snapshot.qualifiedName,
                methodName = method.name,
                parameterCount = method.parameters.size,
                startOffset = startOffset,
                endOffset = endOffset,
            )
            is KtFile -> resolveKotlinMethod(
                anchor = anchor,
                repositoryQualifiedName = snapshot.qualifiedName,
                methodName = method.name,
                parameterCount = method.parameters.size,
                startOffset = startOffset,
                endOffset = endOffset,
            )
            else -> null
        } ?: return failure(
            "JVW-REPOSITORY-REFACTOR-DECLARATION-MISSING",
            "The live repository method cannot be resolved unambiguously.",
        )
        if (
            operation != RepositoryMethodRefactorOperation.OPEN_SOURCE &&
            !declaration.isWritable
        ) {
            return failure(
                "JVW-REPOSITORY-REFACTOR-READONLY",
                "The repository method declaration is read-only.",
            )
        }
        return PreparedRepositoryMethodRefactor(
            accepted = true,
            code = null,
            message = "${operation.label} is ready for ${snapshot.interfaceName}.${method.name}.",
            pointer = SmartPointerManager.getInstance(project)
                .createSmartPsiElementPointer(declaration),
            operation = operation,
            repositoryName = snapshot.interfaceName,
            methodName = method.name,
        )
    }

    private fun resolveJavaMethod(
        anchor: com.intellij.psi.PsiElement,
        repositoryQualifiedName: String,
        methodName: String,
        parameterCount: Int,
        startOffset: Int,
        endOffset: Int,
    ): PsiMethod? {
        val method = PsiTreeUtil.getParentOfType(anchor, PsiMethod::class.java, false)
            ?: return null
        return method.takeIf {
            it.name == methodName &&
                it.parameterList.parametersCount == parameterCount &&
                it.containingClass?.qualifiedName == repositoryQualifiedName &&
                it.textRange.startOffset <= startOffset &&
                it.textRange.endOffset >= endOffset
        }
    }

    private fun resolveKotlinMethod(
        anchor: com.intellij.psi.PsiElement,
        repositoryQualifiedName: String,
        methodName: String,
        parameterCount: Int,
        startOffset: Int,
        endOffset: Int,
    ): KtNamedFunction? {
        val method = PsiTreeUtil.getParentOfType(
            anchor,
            KtNamedFunction::class.java,
            false,
        ) ?: return null
        val owner = PsiTreeUtil.getParentOfType(
            method,
            KtClassOrObject::class.java,
            true,
        ) ?: return null
        val file = method.containingKtFile
        val qualifiedName = listOf(
            file.packageFqName.asString(),
            owner.name.orEmpty(),
        ).filter(String::isNotBlank).joinToString(".")
        return method.takeIf {
            it.name == methodName &&
                it.valueParameters.size == parameterCount &&
                qualifiedName == repositoryQualifiedName &&
                it.textRange.startOffset <= startOffset &&
                it.textRange.endOffset >= endOffset
        }
    }

    private fun failure(
        code: String,
        message: String,
    ): PreparedRepositoryMethodRefactor =
        PreparedRepositoryMethodRefactor(
            accepted = false,
            code = code,
            message = message,
            pointer = null,
            operation = null,
            repositoryName = null,
            methodName = null,
        )

    private fun invalidRequest(message: String): PreparedRepositoryMethodRefactor =
        failure("JVW-REPOSITORY-REFACTOR-REQUEST-INVALID", message)

    companion object {
        private const val MAX_RELATIVE_PATH_LENGTH = 4_096
        private const val MAX_QUALIFIED_NAME_LENGTH = 512
        private const val MAX_SOURCE_SIGNATURE_LENGTH = 4_096
        private val SHA_256 = Regex("[a-fA-F0-9]{64}")

        fun getInstance(project: Project): RepositoryMethodRefactorService =
            project.getService(RepositoryMethodRefactorService::class.java)
    }
}

data class RepositoryMethodRefactorRequest(
    val repositorySource: SourceLocator?,
    val repositoryQualifiedName: String?,
    val methodIndex: Int?,
    val sourceSignature: String?,
    val operation: RepositoryMethodRefactorOperation?,
)

enum class RepositoryMethodRefactorOperation(
    val actionId: String?,
    val label: String,
) {
    @SerializedName("OPEN_SOURCE")
    OPEN_SOURCE(null, "Source navigation"),

    @SerializedName("RENAME")
    RENAME("RenameElement", "IntelliJ Rename"),

    @SerializedName("CHANGE_SIGNATURE")
    CHANGE_SIGNATURE("ChangeSignature", "IntelliJ Change Signature"),

    @SerializedName("SAFE_DELETE")
    SAFE_DELETE("SafeDelete", "IntelliJ Safe Delete"),
}

data class PreparedRepositoryMethodRefactor(
    val accepted: Boolean,
    val code: String?,
    val message: String,
    val pointer: SmartPsiElementPointer<PsiNamedElement>?,
    val operation: RepositoryMethodRefactorOperation?,
    val repositoryName: String?,
    val methodName: String?,
)

data class RepositoryMethodRefactorLaunchResponse(
    val success: Boolean,
    val code: String? = null,
    val message: String,
)
