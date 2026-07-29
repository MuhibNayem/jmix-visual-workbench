package org.jmixworkbench.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import org.jmixworkbench.discovery.model.CanonicalDiscoveryJson
import org.jmixworkbench.discovery.model.SourceLocator

/**
 * Resolves an entity property rename against the exact live IntelliJ PSI.
 *
 * The actual mutation is delegated to IntelliJ's RenameProcessor so every
 * plugin-contributed reference (FlowUI, fetch plans, security, JPQL, etc.)
 * participates in its standard usage preview and conflict analysis.
 */
@Service(Service.Level.PROJECT)
class EntityAttributeRefactorService(
    private val project: Project,
) {
    fun prepareRename(request: EntityAttributeRenameRequest): PreparedEntityAttributeRename {
        if (!IDENTIFIER.matches(request.newName)) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-NAME-INVALID",
                "'${request.newName}' is not a valid Java/Kotlin property name.",
            )
        }
        if (request.attributeName == request.newName) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-NOOP",
                "The new property name is unchanged.",
            )
        }
        val resolved = ProjectFileResolver.getInstance(project)
            .resolveFile(request.sourceLocator.relativePath)
            ?: return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-SOURCE-MISSING",
                "The indexed entity source no longer exists.",
            )
        val file = resolved.file
        if (
            file.isDirectory ||
            file.extension !in setOf("java", "kt") ||
            !VfsUtilCore.isAncestor(resolved.root, file, false)
        ) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-SOURCE-INVALID",
                "Native entity rename requires a Java or Kotlin source inside project content.",
            )
        }
        val current = runCatching { ProjectSourceText.read(file) }.getOrElse {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-SOURCE-UNREADABLE",
                "The current entity document cannot be read.",
            )
        }
        val fingerprint = CanonicalDiscoveryJson.sha256(current)
        if (request.sourceLocator.revisionFingerprint != fingerprint) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-SOURCE-STALE",
                "The entity changed after indexing. Refresh Entity Designer before refactoring.",
            )
        }
        val snapshot = SchemaWorkspaceService.getInstance(project).load().entities.firstOrNull {
            it.className == request.entityClassName &&
                it.sourceLocator.relativePath == request.sourceLocator.relativePath
        } ?: return PreparedEntityAttributeRename.failure(
            "JVW-ENTITY-RENAME-SNAPSHOT-MISSING",
            "The exact entity metadata snapshot is unavailable.",
        )
        val attribute = snapshot.attributes.firstOrNull { it.name == request.attributeName }
            ?: return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-ATTRIBUTE-MISSING",
                "Property ${request.attributeName} is no longer declared by ${request.entityClassName}.",
            )
        if (snapshot.attributes.any { it.name == request.newName }) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-COLLISION",
                "${request.entityClassName} already declares ${request.newName}.",
            )
        }
        if (attribute.association) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-RELATIONSHIP-IMPACT",
                "Relationship rename needs mappedBy/join-column inverse-side choreography and is not safe in the scalar workflow.",
            )
        }
        val psiFile = PsiManager.getInstance(project).findFile(file)
            ?: return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-PSI-MISSING",
                "IntelliJ could not create live PSI for the entity source.",
            )
        val declaration = when (psiFile) {
            is PsiJavaFile -> {
                val entityClass = psiFile.classes.singleOrNull { it.name == request.entityClassName }
                    ?: return PreparedEntityAttributeRename.failure(
                        "JVW-ENTITY-RENAME-CLASS-MISSING",
                        "Class ${request.entityClassName} is no longer present in the Java source.",
                    )
                entityClass.fields.singleOrNull {
                    it.name == request.attributeName &&
                        !it.hasModifierProperty(PsiModifier.STATIC)
                }
            }

            else -> {
                val entityClass = PsiTreeUtil.findChildrenOfType(
                    psiFile,
                    PsiNamedElement::class.java,
                ).singleOrNull {
                    it.javaClass.simpleName == "KtClass" &&
                        it.name == request.entityClassName
                } ?: return PreparedEntityAttributeRename.failure(
                    "JVW-ENTITY-RENAME-CLASS-MISSING",
                    "Class ${request.entityClassName} is no longer present in the Kotlin source.",
                )
                PsiTreeUtil.findChildrenOfType(entityClass, PsiNamedElement::class.java)
                    .singleOrNull {
                        it.javaClass.simpleName == "KtProperty" &&
                            it.name == request.attributeName &&
                            it.nearestKotlinEntityClass() === entityClass
                    }
            }
        } ?: return PreparedEntityAttributeRename.failure(
            "JVW-ENTITY-RENAME-DECLARATION-MISSING",
            "The live property declaration cannot be resolved unambiguously.",
        )
        if (!declaration.isWritable) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-READONLY",
                "The property declaration is not writable.",
            )
        }
        if (
            attribute.persistent &&
            !EXPLICIT_COLUMN_NAME.containsMatchIn(declaration.text)
        ) {
            return PreparedEntityAttributeRename.failure(
                "JVW-ENTITY-RENAME-INFERRED-COLUMN",
                "Renaming this property would also change its inferred database column. Add an explicit @Column(name = \"${attribute.columnName}\") or use the schema-aware column rename workflow.",
            )
        }
        return PreparedEntityAttributeRename(
            accepted = true,
            code = null,
            message = "IntelliJ usage preview is ready.",
            element = declaration,
            newName = request.newName,
        )
    }

    companion object {
        private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
        private val EXPLICIT_COLUMN_NAME = Regex(
            """(?s)@\s*(?:field:)?(?:[\w.]+\.)?Column\s*\([^)]*\bname\s*=""",
        )

        fun getInstance(project: Project): EntityAttributeRefactorService =
            project.getService(EntityAttributeRefactorService::class.java)
    }
}

data class EntityAttributeRenameRequest(
    val sourceLocator: SourceLocator,
    val entityClassName: String,
    val attributeName: String,
    val newName: String,
)

data class PreparedEntityAttributeRename(
    val accepted: Boolean,
    val code: String?,
    val message: String,
    val element: PsiNamedElement?,
    val newName: String?,
) {
    companion object {
        fun failure(code: String, message: String): PreparedEntityAttributeRename =
            PreparedEntityAttributeRename(false, code, message, null, null)
    }
}

data class EntityAttributeRenameLaunchResponse(
    val success: Boolean,
    val code: String? = null,
    val message: String,
)

private fun com.intellij.psi.PsiElement.nearestKotlinEntityClass(): com.intellij.psi.PsiElement? =
    generateSequence(parent) { it.parent }
        .firstOrNull {
            it.javaClass.simpleName == "KtClass" ||
                it.javaClass.simpleName == "KtObjectDeclaration"
        }
