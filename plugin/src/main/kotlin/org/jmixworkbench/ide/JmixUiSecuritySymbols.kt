package org.jmixworkbench.ide

import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import java.util.Locale

/**
 * Complete, modification-aware symbol inventory for the connected Jmix UI and
 * security surface. Resolution is never truncated; only completion rendering
 * is allowed to cap the number of rows shown to a developer.
 */
@Service(Service.Level.PROJECT)
internal class JmixUiSecuritySymbolService(
    private val project: Project,
) {
    @Volatile
    private var viewCache: JmixUiSymbolCache<JmixViewIdDeclaration>? = null

    @Volatile
    private var menuCache: JmixUiSymbolCache<JmixMenuIdDeclaration>? = null

    @Volatile
    private var messageCache: JmixUiSymbolCache<JmixMessageDeclaration>? = null

    @Volatile
    private var specificPolicyCache: JmixUiSymbolCache<JmixSpecificPolicyDeclaration>? = null

    fun viewIds(): List<JmixViewIdDeclaration> =
        cached({ viewCache }, ::computeViewIds) { viewCache = it }

    fun menuIds(): List<JmixMenuIdDeclaration> =
        cached({ menuCache }, ::computeMenuIds) { menuCache = it }

    fun messages(): List<JmixMessageDeclaration> =
        cached({ messageCache }, ::computeMessages) { messageCache = it }

    fun specificPolicies(): List<JmixSpecificPolicyDeclaration> =
        cached(
            { specificPolicyCache },
            ::computeSpecificPolicies,
        ) { specificPolicyCache = it }

    private fun <T> cached(
        current: () -> JmixUiSymbolCache<T>?,
        compute: () -> List<T>,
        store: (JmixUiSymbolCache<T>) -> Unit,
    ): List<T> {
        if (DumbService.isDumb(project)) return emptyList()
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        current()?.takeIf { it.stamp == stamp }?.let { return it.values }
        return synchronized(this) {
            current()?.takeIf { it.stamp == stamp }?.values
                ?: compute().also { values ->
                    store(JmixUiSymbolCache(stamp, values))
                }
        }
    }

    private fun computeViewIds(): List<JmixViewIdDeclaration> {
        val allScope = GlobalSearchScope.allScope(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val declarations = mutableListOf<JmixViewIdDeclaration>()

        JMIX_VIEW_CONTROLLER_ANNOTATIONS.forEach { annotationName ->
            val annotationClass = facade.findClass(annotationName, allScope)
                ?: return@forEach
            declarations += AnnotatedElementsSearch.searchPsiClasses(annotationClass, allScope)
                .findAll()
                .mapNotNull(PsiClass::jmixViewIdDeclaration)
        }

        FilenameIndex.getAllFilesByExt(project, "java", projectScope)
            .asSequence()
            .mapNotNull { PsiManager.getInstance(project).findFile(it) }
            .flatMap { file ->
                PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java).asSequence()
            }
            .mapNotNull(PsiClass::jmixViewIdDeclaration)
            .forEach(declarations::add)

        FilenameIndex.getAllFilesByExt(project, "kt", projectScope)
            .asSequence()
            .mapNotNull { PsiManager.getInstance(project).findFile(it) }
            .flatMap { file ->
                PsiTreeUtil.findChildrenOfType(
                    file,
                    PsiLanguageInjectionHost::class.java,
                ).asSequence()
            }
            .filter { it.javaClass.simpleName == "KtStringTemplateExpression" }
            .mapNotNull { host ->
                val context = host.kotlinAnnotationContext() ?: return@mapNotNull null
                if (context.name != "ViewController" ||
                    context.attributeName !in setOf(null, "value", "id")
                ) {
                    return@mapNotNull null
                }
                val range = host.kotlinStringContentRange() ?: return@mapNotNull null
                val id = range.substring(host.text).takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                JmixViewIdDeclaration(id, host, null)
            }
            .forEach(declarations::add)

        return declarations
            .filter { it.id.isNotBlank() }
            .distinctBy { declaration ->
                val file = declaration.valueElement.containingFile?.virtualFile?.path.orEmpty()
                "$file:${declaration.valueElement.textOffset}:${declaration.id}"
            }
            .sortedWith(
                compareBy<JmixViewIdDeclaration> { it.id }
                    .thenBy { it.valueElement.containingFile?.virtualFile?.path.orEmpty() },
            )
    }

    private fun computeMenuIds(): List<JmixMenuIdDeclaration> {
        val scope = GlobalSearchScope.allScope(project)
        val manager = PsiManager.getInstance(project)
        return FilenameIndex.getAllFilesByExt(project, "xml", scope)
            .asSequence()
            .mapNotNull { manager.findFile(it) as? XmlFile }
            .filter(XmlFile::isJmixMenuDescriptor)
            .flatMap { file ->
                PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).asSequence()
            }
            .filter { it.localName == "menu" || it.localName == "item" }
            .mapNotNull { tag ->
                val explicit = tag.getAttribute("id")
                val effectiveId = explicit?.value
                    ?: tag.takeIf { it.localName == "item" }
                        ?.getAttributeValue("view")
                    ?: tag.takeIf { it.localName == "item" }
                        ?.getAttributeValue("screen")
                    ?: tag.takeIf { it.localName == "item" }
                        ?.getAttributeValue("bean")
                        ?.let { bean ->
                            tag.getAttributeValue("beanMethod")
                                ?.let { method -> "$bean#$method" }
                                ?: bean
                        }
                    ?: return@mapNotNull null
                val declaration = explicit
                    ?: tag.getAttribute("view")
                    ?: tag.getAttribute("screen")
                    ?: tag.getAttribute("bean")
                    ?: return@mapNotNull null
                JmixMenuIdDeclaration(
                    id = effectiveId,
                    declaration = declaration,
                    tag = tag,
                    explicit = explicit != null,
                )
            }
            .filter { it.id.isNotBlank() }
            .distinctBy {
                "${it.declaration.containingFile.virtualFile.path}:${it.declaration.textOffset}:${it.id}"
            }
            .sortedWith(
                compareBy<JmixMenuIdDeclaration> { it.id }
                    .thenBy { it.declaration.containingFile.virtualFile.path },
            )
            .toList()
    }

    private fun computeMessages(): List<JmixMessageDeclaration> {
        val scope = GlobalSearchScope.allScope(project)
        val manager = PsiManager.getInstance(project)
        return FilenameIndex.getAllFilesByExt(project, "properties", scope)
            .asSequence()
            .filter { JMIX_MESSAGE_FILE.matches(it.name) }
            .mapNotNull { manager.findFile(it) }
            .mapNotNull { it as? PropertiesFile }
            .flatMap { propertiesFile ->
                val psiFile = propertiesFile.containingFile
                val packageGroup = psiFile.jmixResourcePackage()
                propertiesFile.properties.asSequence()
                    .filterIsInstance<Property>()
                    .mapNotNull { property ->
                        val key = property.name?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val lookupKeys = buildSet {
                            add(key)
                            if ('/' !in key && packageGroup.isNotBlank()) {
                                add("$packageGroup/$key")
                            }
                        }
                        JmixMessageDeclaration(property, key, packageGroup, lookupKeys)
                    }
            }
            .distinctBy {
                "${it.property.containingFile.virtualFile.path}:${it.property.textOffset}:${it.key}"
            }
            .sortedWith(
                compareBy<JmixMessageDeclaration> { it.key }
                    .thenBy { it.property.containingFile.virtualFile.path },
            )
            .toList()
    }

    private fun computeSpecificPolicies(): List<JmixSpecificPolicyDeclaration> {
        val scope = GlobalSearchScope.projectScope(project)
        val manager = PsiManager.getInstance(project)
        val declarations = mutableListOf<JmixSpecificPolicyDeclaration>()

        FilenameIndex.getAllFilesByExt(project, "java", scope)
            .asSequence()
            .mapNotNull(manager::findFile)
            .flatMap { file ->
                PsiTreeUtil.findChildrenOfType(
                    file,
                    PsiLiteralExpression::class.java,
                ).asSequence()
            }
            .mapNotNull { literal ->
                val resource = literal.value as? String ?: return@mapNotNull null
                if (resource.isBlank() || resource == "*") return@mapNotNull null
                val annotation = PsiTreeUtil.getParentOfType(
                    literal,
                    PsiAnnotation::class.java,
                    false,
                ) ?: return@mapNotNull null
                val annotationName = annotation.qualifiedName?.substringAfterLast('.')
                    ?: annotation.nameReferenceElement?.referenceName
                if (annotationName != "SpecificPolicy") return@mapNotNull null
                val attribute = PsiTreeUtil.getParentOfType(
                    literal,
                    PsiNameValuePair::class.java,
                    false,
                )?.attributeName ?: "value"
                if (attribute != "resources" && attribute != "value") {
                    return@mapNotNull null
                }
                JmixSpecificPolicyDeclaration(resource, literal)
            }
            .forEach(declarations::add)

        FilenameIndex.getAllFilesByExt(project, "kt", scope)
            .asSequence()
            .mapNotNull(manager::findFile)
            .flatMap { file ->
                PsiTreeUtil.findChildrenOfType(
                    file,
                    PsiLanguageInjectionHost::class.java,
                ).asSequence()
            }
            .filter { it.javaClass.simpleName == "KtStringTemplateExpression" }
            .mapNotNull { host ->
                val context = host.kotlinAnnotationContext() ?: return@mapNotNull null
                if (context.name != "SpecificPolicy" ||
                    context.attributeName !in setOf(null, "value", "resources")
                ) {
                    return@mapNotNull null
                }
                val range = host.kotlinStringContentRange() ?: return@mapNotNull null
                val resource = range.substring(host.text)
                    .takeIf { it.isNotBlank() && it != "*" }
                    ?: return@mapNotNull null
                JmixSpecificPolicyDeclaration(resource, host)
            }
            .forEach(declarations::add)

        return declarations
            .distinctBy {
                "${it.valueElement.containingFile.virtualFile.path}:" +
                    "${it.valueElement.textOffset}:${it.resource}"
            }
            .sortedWith(
                compareBy<JmixSpecificPolicyDeclaration> { it.resource }
                    .thenBy { it.valueElement.containingFile.virtualFile.path },
            )
    }

    companion object {
        fun getInstance(project: Project): JmixUiSecuritySymbolService =
            project.getService(JmixUiSecuritySymbolService::class.java)
    }
}

private data class JmixUiSymbolCache<T>(
    val stamp: Long,
    val values: List<T>,
)

internal data class JmixViewIdDeclaration(
    val id: String,
    val valueElement: PsiElement,
    val controller: PsiClass?,
)

internal data class JmixMenuIdDeclaration(
    val id: String,
    val declaration: XmlAttribute,
    val tag: XmlTag,
    val explicit: Boolean,
)

internal data class JmixMessageDeclaration(
    val property: Property,
    val key: String,
    val packageGroup: String,
    val lookupKeys: Set<String>,
)

internal data class JmixSpecificPolicyDeclaration(
    val resource: String,
    val valueElement: PsiElement,
)

internal class JmixViewIdElement(
    internal val declaration: JmixViewIdDeclaration,
) : FakePsiElement() {
    override fun getParent(): PsiElement =
        declaration.valueElement.parent ?: declaration.valueElement.containingFile

    override fun getManager(): PsiManager = declaration.valueElement.manager

    override fun getContainingFile() = declaration.valueElement.containingFile

    override fun getName(): String = declaration.id

    override fun setName(name: String): PsiElement {
        renameJmixStringValue(declaration.valueElement, name)
        return this
    }

    override fun getText(): String = name

    override fun getNavigationElement(): PsiElement = declaration.valueElement

    override fun getTextRange() = declaration.valueElement.textRange

    override fun getTextOffset(): Int = declaration.valueElement.textOffset

    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)

    override fun isValid(): Boolean = declaration.valueElement.isValid

    override fun isWritable(): Boolean = declaration.valueElement.isWritable

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another is JmixViewIdElement &&
            manager.areElementsEquivalent(
                declaration.valueElement,
                another.declaration.valueElement,
            )

    override fun getPresentableText(): String = name

    override fun toString(): String = "Jmix view '$name'"
}

internal class JmixSpecificPolicyElement(
    internal val declaration: JmixSpecificPolicyDeclaration,
) : FakePsiElement() {
    override fun getParent(): PsiElement =
        declaration.valueElement.parent ?: declaration.valueElement.containingFile

    override fun getManager(): PsiManager = declaration.valueElement.manager

    override fun getContainingFile() = declaration.valueElement.containingFile

    override fun getName(): String = declaration.resource

    override fun setName(name: String): PsiElement {
        renameJmixStringValue(declaration.valueElement, name)
        return this
    }

    override fun getText(): String = name

    override fun getNavigationElement(): PsiElement = declaration.valueElement

    override fun getTextRange() = declaration.valueElement.textRange

    override fun getTextOffset(): Int = declaration.valueElement.textOffset

    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)

    override fun isValid(): Boolean = declaration.valueElement.isValid

    override fun isWritable(): Boolean = declaration.valueElement.isWritable

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another is JmixSpecificPolicyElement &&
            manager.areElementsEquivalent(
                declaration.valueElement,
                another.declaration.valueElement,
            )

    override fun getPresentableText(): String = name

    override fun toString(): String = "Jmix specific permission '$name'"
}

internal fun XmlFile.isJmixMenuDescriptor(): Boolean {
    val root = rootTag ?: return false
    if (root.localName != "menu-config" && root.localName != "menu") return false
    val namespace = root.namespace.orEmpty().lowercase(Locale.ROOT)
    return namespace.isBlank() || "jmix" in namespace || "menu" in namespace
}

internal fun jmixViewIdDeclarations(context: PsiElement): List<JmixViewIdDeclaration> =
    JmixUiSecuritySymbolService.getInstance(context.project).viewIds()

internal fun jmixMenuIdDeclarations(context: PsiElement): List<JmixMenuIdDeclaration> =
    JmixUiSecuritySymbolService.getInstance(context.project).menuIds()

internal fun jmixMessageDeclarations(context: PsiElement): List<JmixMessageDeclaration> =
    JmixUiSecuritySymbolService.getInstance(context.project).messages()

internal fun jmixSpecificPolicyDeclarations(
    context: PsiElement,
): List<JmixSpecificPolicyDeclaration> =
    JmixUiSecuritySymbolService.getInstance(context.project).specificPolicies()

internal fun resolveJmixViewIds(context: PsiElement, id: String): List<JmixViewIdDeclaration> =
    jmixViewIdDeclarations(context).filter { it.id == id }

internal fun resolveJmixMenuIds(context: PsiElement, id: String): List<JmixMenuIdDeclaration> =
    jmixMenuIdDeclarations(context).filter { it.id == id }

internal fun resolveJmixMessages(
    context: PsiElement,
    logicalKey: String,
): List<JmixMessageDeclaration> =
    jmixMessageDeclarations(context).filter { logicalKey in it.lookupKeys }

internal fun PsiElement.jmixResourcePackage(): String {
    val file = containingFile?.virtualFile ?: return ""
    val sourceRoot = ProjectFileIndex.getInstance(project).getSourceRootForFile(file)
    if (sourceRoot != null) {
        val relative = file.parent.path.removePrefix(sourceRoot.path).trim('/')
        return relative.replace('/', '.')
    }
    val normalized = file.path.replace('\\', '/')
    val relative = normalized.substringAfter("/resources/", "")
        .substringBeforeLast('/', "")
    return relative.replace('/', '.')
}

private fun PsiClass.jmixViewIdDeclaration(): JmixViewIdDeclaration? {
    val annotation = annotations.firstOrNull(PsiAnnotation::isJmixViewController)
        ?: return null
    val value = annotation.findDeclaredAttributeValue("value")
        ?: annotation.findDeclaredAttributeValue("id")
        ?: return null
    val id = JavaPsiFacade.getInstance(project)
        .constantEvaluationHelper
        .computeConstantExpression(value) as? String
        ?: return null
    if (id.isBlank()) return null
    return JmixViewIdDeclaration(
        id,
        value.jmixNavigationStringElement(),
        this,
    )
}

private fun PsiAnnotation.isJmixViewController(): Boolean {
    val shortName = qualifiedName?.substringAfterLast('.')
        ?: nameReferenceElement?.referenceName
    return shortName == "ViewController"
}

private fun PsiAnnotationMemberValue.jmixNavigationStringElement(): PsiElement {
    val navigation = navigationElement
    if (navigation is PsiLiteralExpression ||
        navigation is PsiLanguageInjectionHost &&
        navigation.javaClass.simpleName == "KtStringTemplateExpression"
    ) {
        return navigation
    }
    return this
}

internal fun renameJmixStringValue(element: PsiElement, replacement: String): PsiElement =
    when (element) {
        is PsiLiteralExpression -> {
            val escaped = replacement.replace("\\", "\\\\").replace("\"", "\\\"")
            val expression = JavaPsiFacade.getElementFactory(element.project)
                .createExpressionFromText("\"$escaped\"", element)
            element.replace(expression)
        }

        is PsiLanguageInjectionHost -> {
            val range = element.kotlinStringContentRange() ?: return element
            ElementManipulators.handleContentChange(element, range, replacement)
        }

        else -> element
    }

private val JMIX_VIEW_CONTROLLER_ANNOTATIONS = setOf(
    "io.jmix.flowui.view.ViewController",
    "io.jmix.ui.screen.UiController",
)
private val JMIX_MESSAGE_FILE = Regex("""messages(?:_[A-Za-z0-9_-]+)?\.properties""")
internal const val JMIX_UI_COMPLETION_LIMIT = 2_000
