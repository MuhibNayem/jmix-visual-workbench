package org.jmixworkbench.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.ui.popup.JBPopupFactory
import org.jmixworkbench.services.JmixProjectService
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import javax.swing.JList

/**
 * Native IntelliJ Generate action for Jmix data-repository injection.
 *
 * Candidate discovery uses IntelliJ's inheritor indexes, so repositories built
 * through custom generic base interfaces participate without a project-wide
 * source scan. Java writes use PSI; Kotlin writes use exact Kotlin PSI
 * class-body boundaries and the live document under one undoable command.
 */
class InjectJmixRepositoryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.PSI_FILE)
        event.presentation.isEnabledAndVisible =
            project != null &&
                file != null &&
                file.virtualFile?.extension?.lowercase() in setOf("java", "kt") &&
                !DumbService.isDumb(project) &&
                JmixProjectService.getInstance(project).isJmixProject()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = event.getData(CommonDataKeys.EDITOR)
        editor?.document?.let { document ->
            val documentManager = PsiDocumentManager.getInstance(project)
            if (documentManager.getPsiFile(document) === file) {
                documentManager.commitDocument(document)
            }
        }
        val caretOffset = editor?.caretModel?.offset
        val service = NativeRepositoryInjectionService(project)
        ReadAction.nonBlocking<NativeRepositoryDiscovery> {
            val target = service.target(file, caretOffset)
            NativeRepositoryDiscovery(
                target = target,
                candidates = target?.let(service::candidates).orEmpty(),
            )
        }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { !file.isValid }
            .finishOnUiThread(ModalityState.any()) { discovery ->
                val target = discovery.target
                if (target == null) {
                    Messages.showInfoMessage(
                        project,
                        "Place the caret inside a concrete Java or Kotlin class or object.",
                        "Inject Jmix Data Repository",
                    )
                    return@finishOnUiThread
                }
                when (discovery.candidates.size) {
                    0 -> Messages.showInfoMessage(
                        project,
                        "No accessible Jmix data repository was resolved. Complete Gradle sync and check module dependencies.",
                        "Inject Jmix Data Repository",
                    )
                    1 -> injectSelected(project, service, target, discovery.candidates.single())
                    else -> showChooser(project, editor, service, target, discovery.candidates)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun showChooser(
        project: Project,
        editor: Editor?,
        service: NativeRepositoryInjectionService,
        target: NativeInjectionTarget,
        candidates: List<NativeRepositoryCandidate>,
    ) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(candidates)
            .setTitle("Inject Jmix Data Repository")
            .setRenderer(NativeRepositoryCandidateRenderer())
            .setItemChosenCallback { candidate ->
                injectSelected(project, service, target, candidate)
            }
            .setNamerForFiltering { candidate: NativeRepositoryCandidate ->
                listOf(
                    candidate.qualifiedName,
                    candidate.entityQualifiedName,
                    candidate.moduleName,
                ).joinToString(" ")
            }
            .createPopup()
        if (editor != null) {
            popup.showInBestPositionFor(editor)
        } else {
            popup.showCenteredInCurrentWindow(project)
        }
    }

    private fun injectSelected(
        project: Project,
        service: NativeRepositoryInjectionService,
        target: NativeInjectionTarget,
        candidate: NativeRepositoryCandidate,
    ) {
        if (candidate.constraintsApplied != true) {
            val detail = if (candidate.constraintsApplied == false) {
                "The repository hierarchy declares @ApplyConstraints(false)."
            } else {
                "The effective repository constraint policy could not be proven."
            }
            val decision = Messages.showYesNoDialog(
                project,
                "$detail\n\nInject ${candidate.qualifiedName} anyway? Review every use before accessing UI data.",
                "Repository Security Review",
                "Inject after review",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (decision != Messages.YES) return
        }
        val result = service.inject(target, candidate)
        if (!result.accepted) {
            Messages.showErrorDialog(
                project,
                result.message,
                "Inject Jmix Data Repository",
            )
            return
        }
        result.element?.let { element ->
            element.containingFile?.virtualFile?.let { file ->
                OpenFileDescriptor(project, file, element.textOffset).navigate(true)
            }
        }
    }
}

internal class NativeRepositoryInjectionService(
    private val project: Project,
) {
    fun target(
        file: PsiFile,
        caretOffset: Int?,
    ): NativeInjectionTarget? {
        val selected = caretOffset
            ?.coerceIn(0, file.textLength.coerceAtLeast(1) - 1)
            ?.let(file::findElementAt)
        val javaClass = selected?.let {
            PsiTreeUtil.getParentOfType(it, PsiClass::class.java, false)
        } ?: (file as? PsiJavaFile)?.classes?.firstOrNull()
        if (javaClass != null && javaTargetSupported(javaClass)) {
            return NativeInjectionTarget(
                pointer = SmartPointerManager.getInstance(project)
                    .createSmartPsiElementPointer(javaClass as PsiNamedElement),
                language = NativeInjectionLanguage.JAVA,
            )
        }
        val kotlinFile = file as? KtFile ?: return null
        val kotlinClass = selected?.let {
            PsiTreeUtil.getParentOfType(it, KtClassOrObject::class.java, false)
        } ?: kotlinFile.declarations.filterIsInstance<KtClassOrObject>().firstOrNull()
            ?: return null
        if (!kotlinTargetSupported(kotlinClass)) return null
        return NativeInjectionTarget(
            pointer = SmartPointerManager.getInstance(project)
                .createSmartPsiElementPointer(kotlinClass),
            language = NativeInjectionLanguage.KOTLIN,
        )
    }

    fun candidates(target: NativeInjectionTarget): List<NativeRepositoryCandidate> {
        val targetElement = target.pointer.element ?: return emptyList()
        val module = ModuleUtilCore.findModuleForPsiElement(targetElement)
        val scope = module?.let {
            GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(it)
        }
            ?: GlobalSearchScope.projectScope(project)
        val repositoryBase = JavaPsiFacade.getInstance(project).findClass(
            JMIX_REPOSITORY_BASE,
            scope,
        ) ?: return emptyList()
        val projectFiles = ProjectFileIndex.getInstance(project)
        return ClassInheritorsSearch.search(repositoryBase, scope, true)
            .findAll()
            .asSequence()
            .filter(PsiClass::isInterface)
            .filterNot(::isRepositoryBaseFragment)
            .mapNotNull { repository ->
                val virtualFile = repository.containingFile?.virtualFile ?: return@mapNotNull null
                if (!projectFiles.isInContent(virtualFile)) return@mapNotNull null
                val qualifiedName = repository.qualifiedName ?: return@mapNotNull null
                val entityClass = repositoryEntity(repository) ?: return@mapNotNull null
                NativeRepositoryCandidate(
                    pointer = SmartPointerManager.getInstance(project)
                        .createSmartPsiElementPointer(repository),
                    qualifiedName = qualifiedName,
                    entityQualifiedName = entityClass.qualifiedName ?: entityClass.name.orEmpty(),
                    moduleName = ModuleUtilCore.findModuleForPsiElement(repository)?.name.orEmpty(),
                    sourceFile = virtualFile,
                    constraintsApplied = repositoryConstraints(repository),
                )
            }
            .distinctBy(NativeRepositoryCandidate::qualifiedName)
            .sortedWith(
                compareBy(
                    NativeRepositoryCandidate::entityQualifiedName,
                    NativeRepositoryCandidate::qualifiedName,
                ),
            )
            .toList()
    }

    fun inject(
        target: NativeInjectionTarget,
        candidate: NativeRepositoryCandidate,
    ): NativeInjectionResult {
        val targetElement = target.pointer.element
            ?: return NativeInjectionResult(false, "The target class changed. Invoke the action again.")
        val repositoryClass = candidate.pointer.element
            ?: return NativeInjectionResult(false, "The selected repository changed. Invoke the action again.")
        var result = NativeInjectionResult(false, "Repository injection was not applied.")
        val targetFile = targetElement.containingFile
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(targetFile)
            ?: return NativeInjectionResult(false, "The target source document is unavailable.")
        val originalSource = document.text
        WriteCommandAction.writeCommandAction(project, targetFile)
            .withName("Inject Jmix data repository")
            .run<RuntimeException> {
                try {
                    result = when (target.language) {
                        NativeInjectionLanguage.JAVA ->
                            injectJava(targetElement as? PsiClass, repositoryClass)
                        NativeInjectionLanguage.KOTLIN ->
                            injectKotlin(targetElement as? KtClassOrObject, candidate.qualifiedName)
                    }
                } catch (canceled: ProcessCanceledException) {
                    restoreSource(documentManager, document, originalSource)
                    throw canceled
                } catch (failure: RuntimeException) {
                    restoreSource(documentManager, document, originalSource)
                    result = NativeInjectionResult(
                        false,
                        "Repository injection failed without changing the source: " +
                            (failure.message ?: failure.javaClass.simpleName),
                    )
                }
            }
        return result
    }

    private fun injectJava(
        targetClass: PsiClass?,
        repositoryClass: PsiClass,
    ): NativeInjectionResult {
        targetClass
            ?: return NativeInjectionResult(false, "The Java target class is no longer available.")
        targetClass.fields.firstOrNull { field ->
            field.type.resolvesTo(repositoryClass)
        }?.let { existing ->
            return NativeInjectionResult(
                true,
                "${repositoryClass.name} is already injected as '${existing.name}'.",
                existing,
            )
        }
        targetClass.constructors
            .asSequence()
            .flatMap { it.parameterList.parameters.asSequence() }
            .firstOrNull { parameter -> parameter.type.resolvesTo(repositoryClass) }
            ?.let { existing ->
                return NativeInjectionResult(
                    true,
                    "${repositoryClass.name} is already injected as '${existing.name}'.",
                    existing,
                )
            }
        if (!javaTargetSupported(targetClass)) {
            return NativeInjectionResult(
                false,
                "Repository fields can be injected only into a concrete Java class.",
            )
        }
        val factory = JavaPsiFacade.getElementFactory(project)
        val style = JavaCodeStyleManager.getInstance(project)
        val baseName = repositoryClass.name
            ?.replaceFirstChar(Char::lowercase)
            ?.takeIf(String::isNotBlank)
            ?: "repository"
        val fieldName = style.suggestUniqueVariableName(baseName, targetClass, true)
        val field = factory.createField(fieldName, factory.createType(repositoryClass))
        field.modifierList?.setModifierProperty(PsiModifier.PRIVATE, true)
        field.modifierList?.addAnnotation(AUTOWIRED)
        val added = targetClass.add(field) as PsiField
        val shortened = style.shortenClassReferences(added) as PsiField
        val formatted = CodeStyleManager.getInstance(project).reformat(shortened) as PsiField
        return NativeInjectionResult(
            true,
            "Injected ${repositoryClass.qualifiedName}.",
            formatted,
        )
    }

    private fun injectKotlin(
        targetClass: KtClassOrObject?,
        repositoryQualifiedName: String,
    ): NativeInjectionResult {
        targetClass
            ?: return NativeInjectionResult(false, "The Kotlin target class is no longer available.")
        if (!kotlinTargetSupported(targetClass)) {
            return NativeInjectionResult(
                false,
                "Repository properties can be injected only into a concrete Kotlin class or object.",
            )
        }
        val file = targetClass.containingFile
        val kotlinFile = file as? KtFile
            ?: return NativeInjectionResult(false, "The Kotlin source file is no longer available.")
        val repositorySimpleName = repositoryQualifiedName.substringAfterLast('.')
        val imports = kotlinFile.importDirectives.mapNotNull { directive ->
            val imported = directive.importedFqName?.asString() ?: return@mapNotNull null
            val visibleName = directive.aliasName ?: imported.substringAfterLast('.')
            visibleName to imported
        }.toMap()
        val packageName = kotlinFile.packageFqName.asString()
        val directDeclarations = buildList<PsiNamedElement> {
            addAll(targetClass.declarations.filterIsInstance<KtProperty>())
            (targetClass as? KtClass)?.primaryConstructorParameters
                ?.let(::addAll)
        }
        directDeclarations.firstOrNull { declaration ->
            kotlinDeclaredType(declaration)?.let { type ->
                type == repositoryQualifiedName ||
                    imports[type] == repositoryQualifiedName ||
                    type == repositorySimpleName &&
                    packageName == repositoryQualifiedName.substringBeforeLast('.', "")
            } == true
        }?.let { existing ->
            return NativeInjectionResult(
                true,
                "$repositorySimpleName is already injected as '${existing.name}'.",
                existing,
            )
        }
        val usedNames = directDeclarations.mapNotNull(PsiNamedElement::getName).toSet()
        val baseName = repositorySimpleName
            .replaceFirstChar(Char::lowercase)
            .takeIf(String::isNotBlank)
            ?: "repository"
        val fieldName = generateSequence(baseName) { current ->
            val suffix = current.removePrefix(baseName).toIntOrNull()?.plus(1) ?: 2
            "$baseName$suffix"
        }.first { it !in usedNames }
        val property = KtPsiFactory(project).createProperty(
            "@$AUTOWIRED\nprivate lateinit var $fieldName: $repositoryQualifiedName",
        )
        @Suppress("DEPRECATION")
        val added = targetClass.addDeclaration(property)
        val formatted = CodeStyleManager.getInstance(project).reformat(added)
        return NativeInjectionResult(
            true,
            "Injected $repositoryQualifiedName.",
            formatted,
        )
    }

    private fun repositoryEntity(repository: PsiClass): PsiClass? =
        repositoryEntity(repository, PsiSubstitutor.EMPTY, linkedSetOf())

    private fun repositoryEntity(
        owner: PsiClass,
        ownerSubstitutor: PsiSubstitutor,
        visiting: LinkedHashSet<PsiClass>,
    ): PsiClass? {
        if (!visiting.add(owner)) return null
        owner.extendsListTypes.forEach { declaredType ->
            val type = (ownerSubstitutor.substitute(declaredType) as? PsiClassType)
                ?: declaredType
            val resolved = type.resolveGenerics()
            val superClass = resolved.element ?: return@forEach
            if (superClass.qualifiedName == JMIX_REPOSITORY_BASE) {
                val entityType = superClass.typeParameters.firstOrNull()
                    ?.let(resolved.substitutor::substitute)
                    ?: type.parameters.firstOrNull()
                return (entityType as? PsiClassType)?.resolve()
            }
            repositoryEntity(
                superClass,
                resolved.substitutor,
                LinkedHashSet(visiting),
            )?.let { return it }
        }
        return null
    }

    private fun repositoryConstraints(repository: PsiClass): Boolean? {
        var level = listOf(repository)
        val visited = linkedSetOf<PsiClass>()
        while (level.isNotEmpty()) {
            val current = level.filter(visited::add)
            val policies = current.mapNotNull(::explicitConstraint)
            if (policies.any { it.value == null }) return null
            val values = policies.mapNotNull(ExplicitConstraint::value).distinct()
            if (values.size > 1) return null
            values.singleOrNull()?.let { return it }
            level = current.flatMap { it.supers.toList() }
        }
        return true
    }

    private fun explicitConstraint(owner: PsiModifierListOwner): ExplicitConstraint? {
        val annotation = owner.modifierList?.annotations?.firstOrNull {
            it.qualifiedName == APPLY_CONSTRAINTS
        } ?: return null
        val valueElement = annotation.findAttributeValue("value")
        val value = valueElement?.let {
            JavaPsiFacade.getInstance(project)
                .constantEvaluationHelper
                .computeConstantExpression(it) as? Boolean
        }
        return ExplicitConstraint(
            if (valueElement == null) true else value,
        )
    }

    private fun isRepositoryBaseFragment(repository: PsiClass): Boolean =
        repository.annotations.any { annotation ->
            annotation.qualifiedName == NO_REPOSITORY_BEAN
        }

    private fun restoreSource(
        documentManager: PsiDocumentManager,
        document: com.intellij.openapi.editor.Document,
        originalSource: String,
    ) {
        document.setText(originalSource)
        documentManager.commitDocument(document)
    }

    private fun javaTargetSupported(target: PsiClass): Boolean =
        target.name != null &&
        !target.isInterface &&
            !target.isAnnotationType &&
            !target.isEnum &&
            !target.isRecord &&
            !target.hasModifierProperty(PsiModifier.ABSTRACT)

    private fun kotlinTargetSupported(target: KtClassOrObject): Boolean =
        when (target) {
            is KtObjectDeclaration -> !target.isCompanion()
            is KtClass ->
                !target.isInterface() &&
                    !target.isEnum() &&
                    !target.isAnnotation() &&
                    !target.hasModifier(KtTokens.VALUE_KEYWORD) &&
                    !target.hasModifier(KtTokens.ABSTRACT_KEYWORD) &&
                    !target.hasModifier(KtTokens.SEALED_KEYWORD)
            else -> false
        }

    private fun com.intellij.psi.PsiType.resolvesTo(repositoryClass: PsiClass): Boolean {
        val typeClass = (this as? PsiClassType)?.resolve()
        return typeClass != null &&
            repositoryClass.manager.areElementsEquivalent(typeClass, repositoryClass)
    }

    companion object {
        private const val JMIX_REPOSITORY_BASE =
            "io.jmix.core.repository.JmixDataRepository"
        private const val AUTOWIRED =
            "org.springframework.beans.factory.annotation.Autowired"
        private const val APPLY_CONSTRAINTS =
            "io.jmix.core.repository.ApplyConstraints"
        private const val NO_REPOSITORY_BEAN =
            "org.springframework.data.repository.NoRepositoryBean"
    }
}

private data class ExplicitConstraint(
    val value: Boolean?,
)

internal data class NativeInjectionTarget(
    val pointer: SmartPsiElementPointer<PsiNamedElement>,
    val language: NativeInjectionLanguage,
)

internal enum class NativeInjectionLanguage {
    JAVA,
    KOTLIN,
}

internal data class NativeRepositoryCandidate(
    val pointer: SmartPsiElementPointer<PsiClass>,
    val qualifiedName: String,
    val entityQualifiedName: String,
    val moduleName: String,
    val sourceFile: VirtualFile,
    val constraintsApplied: Boolean?,
)

internal data class NativeInjectionResult(
    val accepted: Boolean,
    val message: String,
    val element: PsiElement? = null,
)

private data class NativeRepositoryDiscovery(
    val target: NativeInjectionTarget?,
    val candidates: List<NativeRepositoryCandidate>,
)

private class NativeRepositoryCandidateRenderer :
    ColoredListCellRenderer<NativeRepositoryCandidate>() {
    override fun customizeCellRenderer(
        list: JList<out NativeRepositoryCandidate>,
        value: NativeRepositoryCandidate?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        border = JBUI.Borders.empty(4, 6)
        value ?: return
        append(value.qualifiedName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append(
            "  ${value.entityQualifiedName}",
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
        )
        if (value.moduleName.isNotBlank()) {
            append("  [${value.moduleName}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        if (value.constraintsApplied != true) {
            append(
                if (value.constraintsApplied == false) {
                    "  constraints disabled"
                } else {
                    "  constraints unproven"
                },
                SimpleTextAttributes.ERROR_ATTRIBUTES,
            )
        }
    }
}

private fun kotlinDeclaredType(declaration: PsiNamedElement): String? =
    when (declaration) {
        is KtProperty -> declaration.typeReference?.text
        is KtParameter -> declaration.typeReference?.text
        else -> null
    }?.removeSuffix("?")
