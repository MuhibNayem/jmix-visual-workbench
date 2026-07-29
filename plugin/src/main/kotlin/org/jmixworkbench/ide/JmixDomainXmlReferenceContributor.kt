package org.jmixworkbench.ide

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.PsiType
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UseScopeEnlarger
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * Native Jmix domain references used by FlowUI and shared fetch-plan XML.
 *
 * The implementation resolves to real Java/Kotlin PSI declarations so the
 * platform owns navigation, Find Usages and refactoring instead of a parallel
 * workbench-only symbol system.
 */
class JmixDomainXmlReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            JmixDomainXmlReferenceProvider,
        )
    }
}

/**
 * JPA/Jmix entity attributes are normally private, but their metadata names
 * are a public project-wide contract used from XML, JPQL, security and REST.
 * Enlarge the native use scope so IntelliJ rename and Find Usages do not stop
 * at the declaring Java file.
 */
class JmixEntityUseScopeEnlarger : UseScopeEnlarger() {
    override fun getAdditionalUseScope(element: PsiElement): SearchScope? {
        val entityClass = when (element) {
            is com.intellij.psi.PsiField -> element.containingClass
            is PsiMethod -> element.containingClass
            else -> null
        } ?: return null
        return GlobalSearchScope.projectScope(element.project)
            .takeIf { entityClass.isJmixEntity() }
    }
}

/**
 * Modification-aware project symbol cache. Completion may be requested for
 * every keystroke, so project-wide annotation and XML discovery must not be
 * repeated while PSI is unchanged. No correctness cutoff is applied: large
 * projects retain the complete symbol set and only the UI result list is
 * capped.
 */
@Service(Service.Level.PROJECT)
class JmixDomainSymbolService(
    private val project: Project,
) {
    @Volatile
    private var entityCache: JmixDomainSymbolCache<PsiClass>? = null

    @Volatile
    private var fetchPlanCache: JmixDomainSymbolCache<XmlAttribute>? = null

    fun entityClasses(): List<PsiClass> {
        if (DumbService.isDumb(project)) return emptyList()
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        entityCache?.takeIf { it.stamp == stamp }?.let { return it.values }
        return synchronized(this) {
            entityCache?.takeIf { it.stamp == stamp }?.values
                ?: computeEntityClasses().also { values ->
                    entityCache = JmixDomainSymbolCache(stamp, values)
                }
        }
    }

    fun fetchPlanDeclarations(): List<XmlAttribute> {
        if (DumbService.isDumb(project)) return emptyList()
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        fetchPlanCache?.takeIf { it.stamp == stamp }?.let { return it.values }
        return synchronized(this) {
            fetchPlanCache?.takeIf { it.stamp == stamp }?.values
                ?: computeFetchPlanDeclarations().also { values ->
                    fetchPlanCache = JmixDomainSymbolCache(stamp, values)
                }
        }
    }

    private fun computeEntityClasses(): List<PsiClass> {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        return JMIX_ENTITY_ANNOTATIONS.asSequence()
            .mapNotNull { annotationName ->
                runCatching { facade.findClass(annotationName, scope) }.getOrNull()
            }
            .flatMap { annotationClass ->
                runCatching {
                    AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope)
                        .findAll()
                        .asSequence()
                }.getOrDefault(emptySequence())
            }
            .filter(PsiClass::isJmixEntity)
            .distinctBy { it.qualifiedName ?: it.name }
            .sortedBy { it.qualifiedName ?: it.name }
            .toList()
    }

    private fun computeFetchPlanDeclarations(): List<XmlAttribute> {
        val scope = GlobalSearchScope.projectScope(project)
        val psiManager = PsiManager.getInstance(project)
        return FilenameIndex.getAllFilesByExt(project, "xml", scope)
            .asSequence()
            .mapNotNull { psiManager.findFile(it) as? XmlFile }
            .filter(XmlFile::isJmixFetchPlanDescriptor)
            .flatMap { file ->
                PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).asSequence()
            }
            .filter { it.localName == "fetchPlan" }
            .mapNotNull { it.getAttribute("name") }
            .filter { !it.value.isNullOrBlank() }
            .distinctBy { "${it.containingFile.virtualFile.path}:${it.textOffset}" }
            .sortedWith(
                compareBy<XmlAttribute> { it.value }
                    .thenBy { it.containingFile.virtualFile.path },
            )
            .toList()
    }

    companion object {
        fun getInstance(project: Project): JmixDomainSymbolService =
            project.getService(JmixDomainSymbolService::class.java)
    }
}

private data class JmixDomainSymbolCache<T>(
    val stamp: Long,
    val values: List<T>,
)

internal object JmixDomainXmlReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attribute = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val file = value.containingFile as? XmlFile ?: return PsiReference.EMPTY_ARRAY
        if (!file.isJmixFlowUiDescriptor() && !file.isJmixFetchPlanDescriptor()) {
            return PsiReference.EMPTY_ARRAY
        }
        val raw = value.value
        if (raw.isBlank()) return PsiReference.EMPTY_ARRAY
        val tag = attribute.parent

        if (attribute.localName == "class" &&
            (tag.localName in JMIX_DATA_CONTAINER_TAGS || tag.localName == "fetchPlan")
        ) {
            return arrayOf(
                JmixEntityClassReference(
                    value,
                    xmlValueRange(raw),
                    raw,
                    JmixEntityReferenceStyle.QUALIFIED_CLASS,
                ),
            )
        }
        if (file.isJmixFetchPlanDescriptor() &&
            attribute.localName == "entity" &&
            tag.localName == "fetchPlan"
        ) {
            return arrayOf(
                JmixEntityClassReference(
                    value,
                    xmlValueRange(raw),
                    raw,
                    JmixEntityReferenceStyle.METADATA_NAME,
                ),
            )
        }
        if (file.isJmixFetchPlanDescriptor() &&
            attribute.localName == "name" &&
            tag.localName == "fetchPlan"
        ) {
            return arrayOf(
                JmixNamedFetchPlanDeclarationReference(
                    value,
                    xmlValueRange(raw),
                    attribute,
                ),
            )
        }

        val propertyOwner = attribute.jmixPropertyOwnerClass()
        if (propertyOwner != null &&
            (attribute.localName == "property" ||
                attribute.localName == "hierarchyProperty" ||
                (attribute.localName == "name" && tag.localName == "property"))
        ) {
            return propertyPathReferences(value, raw, propertyOwner)
        }

        if (attribute.localName == "fetchPlan" ||
            (attribute.localName == "extends" && tag.localName == "fetchPlan")
        ) {
            val entityClass = attribute.jmixFetchPlanTargetClass()
            return arrayOf(
                JmixNamedFetchPlanReference(
                    value,
                    xmlValueRange(raw),
                    raw,
                    entityClass,
                ),
            )
        }
        return PsiReference.EMPTY_ARRAY
    }
}

internal enum class JmixEntityReferenceStyle {
    QUALIFIED_CLASS,
    METADATA_NAME,
}

internal class JmixEntityClassReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val identifier: String,
    private val style: JmixEntityReferenceStyle,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        resolveJmixEntityClasses(element, identifier)
            .map(::PsiElementResolveResult)
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        jmixEntityClasses(element)
            .take(JMIX_DOMAIN_COMPLETION_LIMIT)
            .mapNotNull { entityClass ->
                val qualifiedName = entityClass.qualifiedName ?: return@mapNotNull null
                val insertion = when (style) {
                    JmixEntityReferenceStyle.QUALIFIED_CLASS -> qualifiedName
                    JmixEntityReferenceStyle.METADATA_NAME -> entityClass.preferredMetadataName()
                }
                LookupElementBuilder.create(entityClass, insertion)
                    .withPresentableText(entityClass.name ?: insertion)
                    .withTypeText(
                        when (style) {
                            JmixEntityReferenceStyle.QUALIFIED_CLASS ->
                                qualifiedName.substringBeforeLast('.', "")

                            JmixEntityReferenceStyle.METADATA_NAME -> qualifiedName
                        },
                        true,
                    )
                    .withIcon(entityClass.getIcon(0))
            }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val entityClass = resolveJmixEntityClass(element, identifier)
        val oldClassName = entityClass?.name
        val replacement = when {
            style == JmixEntityReferenceStyle.QUALIFIED_CLASS && '.' in identifier ->
                "${identifier.substringBeforeLast('.')}.$newElementName"

            oldClassName == identifier -> newElementName
            else -> identifier
        }
        return replaceXmlRange(element, rangeInElement, replacement)
    }

    internal fun candidateNames(): Sequence<String> =
        jmixEntityClasses(element).asSequence().mapNotNull { entityClass ->
            when (style) {
                JmixEntityReferenceStyle.QUALIFIED_CLASS -> entityClass.qualifiedName
                JmixEntityReferenceStyle.METADATA_NAME -> entityClass.preferredMetadataName()
            }
        }
}

internal class JmixEntityPropertyReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val rootEntity: PsiClass,
    private val pathPrefix: List<String>,
    private val propertyName: String,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidateProperties()
            .filter { it.name == propertyName }
            .map { PsiElementResolveResult(it.element) }
            .toTypedArray()

    override fun getVariants(): Array<Any> =
        candidateProperties()
            .map { property ->
                LookupElementBuilder.create(property.element, property.name)
                    .withTypeText(property.type.presentableText, true)
                    .withIcon(property.element.getIcon(0))
            }
            .toTypedArray()

    override fun handleElementRename(newElementName: String): PsiElement =
        replaceXmlRange(element, rangeInElement, newElementName)

    internal fun candidateProperties(): List<JmixEntityProperty> =
        entityClassAtPath(rootEntity, pathPrefix)
            ?.let(::jmixEntityProperties)
            .orEmpty()
}

internal class JmixNamedFetchPlanReference(
    element: XmlAttributeValue,
    range: TextRange,
    internal val planName: String,
    private val entityClass: PsiClass?,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    internal val isBuiltIn: Boolean
        get() = planName in JMIX_BUILT_IN_FETCH_PLANS

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        if (isBuiltIn) return ResolveResult.EMPTY_ARRAY
        return candidateDeclarations()
            .filter { it.value == planName }
            .map { PsiElementResolveResult(JmixXmlAttributeNamedElement(it, "Jmix fetch plan")) }
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> {
        val builtIns = JMIX_BUILT_IN_FETCH_PLANS.map { name ->
            LookupElementBuilder.create(name).withTypeText("built-in fetch plan", true)
        }
        val named = candidateDeclarations()
            .asSequence()
            .take(JMIX_DOMAIN_COMPLETION_LIMIT)
            .mapNotNull { declaration ->
                val name = declaration.value?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                LookupElementBuilder.create(name)
                    .withTypeText(
                        declaration.containingFile.virtualFile.parent?.path.orEmpty(),
                        true,
                    )
            }
        return (builtIns.asSequence() + named)
            .distinctBy { it.lookupString }
            .toList()
            .toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement =
        replaceXmlRange(element, rangeInElement, newElementName)

    internal fun candidateDeclarations(): List<XmlAttribute> =
        findJmixFetchPlanDeclarations(element, entityClass)

    internal fun candidateNames(): Sequence<String> =
        sequence {
            yieldAll(JMIX_BUILT_IN_FETCH_PLANS)
            yieldAll(candidateDeclarations().asSequence().mapNotNull(XmlAttribute::getValue))
        }.filter(String::isNotBlank).distinct()
}

internal class JmixNamedFetchPlanDeclarationReference(
    element: XmlAttributeValue,
    range: TextRange,
    private val declaration: XmlAttribute,
) : PsiPolyVariantReferenceBase<XmlAttributeValue>(element, range, false) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        arrayOf(
            PsiElementResolveResult(
                JmixXmlAttributeNamedElement(declaration, "Jmix fetch plan"),
            ),
        )

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        declaration.setValue(newElementName)
        return declaration.valueElement ?: element
    }
}

internal class JmixXmlAttributeNamedElement(
    internal val declaration: XmlAttribute,
    private val kind: String,
) : FakePsiElement() {
    override fun getParent(): PsiElement = declaration

    override fun getManager(): PsiManager = declaration.manager

    override fun getContainingFile(): PsiFile = declaration.containingFile

    override fun getName(): String = declaration.value.orEmpty()

    override fun setName(name: String): PsiElement {
        declaration.setValue(name)
        return this
    }

    override fun getText(): String = name

    override fun getNavigationElement(): PsiElement = declaration.valueElement ?: declaration

    override fun getTextRange(): TextRange = navigationElement.textRange

    override fun getTextOffset(): Int = navigationElement.textOffset

    override fun getUseScope(): SearchScope = GlobalSearchScope.projectScope(project)

    override fun isValid(): Boolean = declaration.isValid

    override fun isWritable(): Boolean = declaration.isWritable

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another is JmixXmlAttributeNamedElement &&
            manager.areElementsEquivalent(declaration, another.declaration)

    override fun getPresentableText(): String = name

    override fun toString(): String = "$kind '$name'"
}

internal data class JmixEntityProperty(
    val name: String,
    val element: PsiNamedElement,
    val type: PsiType,
)

internal fun XmlFile.isJmixFetchPlanDescriptor(): Boolean {
    val root = rootTag ?: return false
    if (root.localName != "fetchPlans" && root.localName != "fetch-plans") return false
    val namespace = root.namespace.orEmpty().lowercase()
    return namespace.isBlank() || "jmix" in namespace || "fetch-plan" in namespace
}

internal fun resolveJmixEntityClass(context: PsiElement, identifier: String): PsiClass? {
    return resolveJmixEntityClasses(context, identifier).singleOrNull()
}

internal fun resolveJmixEntityClasses(
    context: PsiElement,
    identifier: String,
): List<PsiClass> {
    val project = context.project
    val scope = GlobalSearchScope.projectScope(project)
    if ('.' in identifier) {
        runCatching {
            JavaPsiFacade.getInstance(project).findClass(identifier, scope)
        }.getOrNull()?.takeIf(PsiClass::isJmixEntity)?.let { return listOf(it) }
    }
    return jmixEntityClasses(context).filter { identifier in it.jmixEntityAliases() }
}

internal fun jmixEntityClasses(context: PsiElement): List<PsiClass> {
    return JmixDomainSymbolService.getInstance(context.project).entityClasses()
}

internal fun PsiClass.isJmixEntity(): Boolean =
    !isAnnotationType &&
        annotations.any { annotation ->
            annotation.qualifiedName in JMIX_ENTITY_ANNOTATIONS ||
                annotation.qualifiedName?.substringAfterLast('.') in JMIX_ENTITY_ANNOTATION_SHORT_NAMES
        }

private fun PsiClass.jmixEntityAliases(): Set<String> = buildSet {
    qualifiedName?.let(::add)
    name?.let(::add)
    annotations.forEach { annotation ->
        if (annotation.qualifiedName in JMIX_ENTITY_ANNOTATIONS ||
            annotation.qualifiedName?.substringAfterLast('.') in JMIX_ENTITY_ANNOTATION_SHORT_NAMES
        ) {
            annotation.stringAttribute("name")?.let(::add)
            annotation.stringAttribute("value")?.let(::add)
        }
    }
}

private fun PsiClass.preferredMetadataName(): String =
    annotations.asSequence()
        .filter { annotation ->
            annotation.qualifiedName in JMIX_ENTITY_ANNOTATIONS ||
                annotation.qualifiedName?.substringAfterLast('.') in JMIX_ENTITY_ANNOTATION_SHORT_NAMES
        }
        .mapNotNull { it.stringAttribute("name") ?: it.stringAttribute("value") }
        .firstOrNull(String::isNotBlank)
        ?: name.orEmpty()

private fun PsiAnnotation.stringAttribute(name: String): String? {
    val value = findDeclaredAttributeValue(name) ?: return null
    return JavaPsiFacade.getInstance(project)
        .constantEvaluationHelper
        .computeConstantExpression(value) as? String
}

private fun propertyPathReferences(
    value: XmlAttributeValue,
    raw: String,
    rootEntity: PsiClass,
): Array<PsiReference> {
    val result = mutableListOf<PsiReference>()
    val prefix = mutableListOf<String>()
    var start = 0
    raw.split('.').forEach { segment ->
        val end = start + segment.length
        if (segment.isNotBlank()) {
            result += JmixEntityPropertyReference(
                value,
                TextRange(start + 1, end + 1),
                rootEntity,
                prefix.toList(),
                segment,
            )
            prefix += segment
        }
        start = end + 1
    }
    return result.toTypedArray()
}

private fun XmlAttribute.jmixPropertyOwnerClass(): PsiClass? {
    val tag = parent
    val file = containingFile as? XmlFile ?: return null
    if (localName == "name" && tag.localName == "property") {
        return fetchPlanPropertyOwnerClass(tag)
    }
    if (localName !in JMIX_PROPERTY_REFERENCE_ATTRIBUTES) return null
    if (localName == "property" && tag.localName in JMIX_DATA_CONTAINER_TAGS) {
        val master = generateSequence(tag.parentTag) { it.parentTag }
            .firstOrNull { it.localName in JMIX_DATA_CONTAINER_TAGS }
            ?: return null
        return entityClassForDataContainer(master)
    }
    val containerId = generateSequence(tag) { it.parentTag }
        .mapNotNull { candidate ->
            candidate.getAttributeValue("dataContainer")
                ?: candidate.getAttributeValue("itemsContainer")
        }
        .firstOrNull()
        ?: return null
    val container = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
        .firstOrNull { candidate ->
            candidate.localName in JMIX_DATA_CONTAINER_TAGS &&
                candidate.getAttributeValue("id") == containerId
        }
        ?: return null
    return entityClassForDataContainer(container)
}

private fun XmlAttribute.jmixFetchPlanTargetClass(): PsiClass? {
    val tag = parent
    if (localName == "extends" && tag.localName == "fetchPlan") {
        return entityClassForFetchPlanTag(tag)
    }
    if (localName != "fetchPlan") return null
    if (tag.localName == "property") {
        val owner = fetchPlanPropertyOwnerClass(tag) ?: return null
        val propertyName = tag.getAttributeValue("name") ?: return null
        return entityClassAtPath(owner, propertyName.split('.'))
    }
    if (tag.localName in JMIX_DATA_CONTAINER_TAGS) {
        return entityClassForDataContainer(tag)
    }
    return jmixPropertyOwnerClass()
}

internal fun entityClassForDataContainer(tag: XmlTag): PsiClass? {
    tag.getAttributeValue("class")?.let { className ->
        resolveJmixEntityClass(tag, className)?.let { return it }
    }
    val propertyPath = tag.getAttributeValue("property") ?: return null
    val master = generateSequence(tag.parentTag) { it.parentTag }
        .firstOrNull { it.localName in JMIX_DATA_CONTAINER_TAGS }
        ?: return null
    val masterClass = entityClassForDataContainer(master) ?: return null
    return entityClassAtPath(masterClass, propertyPath.split('.'))
}

private fun fetchPlanPropertyOwnerClass(propertyTag: XmlTag): PsiClass? {
    val fetchPlan = generateSequence(propertyTag.parentTag) { it.parentTag }
        .firstOrNull { it.localName == "fetchPlan" }
        ?: return null
    var current = entityClassForFetchPlanTag(fetchPlan) ?: return null
    val outerProperties = generateSequence(propertyTag.parentTag) { it.parentTag }
        .takeWhile { it != fetchPlan }
        .filter { it.localName == "property" }
        .toList()
        .asReversed()
    outerProperties.forEach { outer ->
        val name = outer.getAttributeValue("name") ?: return null
        current = entityClassAtPath(current, name.split('.')) ?: return null
    }
    return current
}

internal fun entityClassForFetchPlanTag(fetchPlan: XmlTag): PsiClass? {
    fetchPlan.getAttributeValue("class")?.let { className ->
        resolveJmixEntityClass(fetchPlan, className)?.let { return it }
    }
    fetchPlan.getAttributeValue("entity")?.let { entityName ->
        resolveJmixEntityClass(fetchPlan, entityName)?.let { return it }
    }
    val parentProperty = fetchPlan.parentTag?.takeIf { it.localName == "property" }
    if (parentProperty != null) {
        val owner = fetchPlanPropertyOwnerClass(parentProperty) ?: return null
        val propertyName = parentProperty.getAttributeValue("name") ?: return null
        return entityClassAtPath(owner, propertyName.split('.'))
    }
    val container = generateSequence(fetchPlan.parentTag) { it.parentTag }
        .firstOrNull { it.localName in JMIX_DATA_CONTAINER_TAGS }
        ?: return null
    return entityClassForDataContainer(container)
}

private fun entityClassAtPath(root: PsiClass, path: List<String>): PsiClass? {
    var current = root
    path.filter(String::isNotBlank).forEach { segment ->
        val property = jmixEntityProperties(current).firstOrNull { it.name == segment }
            ?: return null
        current = entityClassForType(property.type) ?: return null
    }
    return current
}

internal fun entityClassForType(type: PsiType): PsiClass? =
    when (type) {
        is PsiArrayType -> entityClassForType(type.componentType)
        is PsiClassType -> {
            val resolved = type.resolve()
            val parameters = type.parameters
            val rawTypeName = type.rawType().canonicalText
            val isEntityContainer = resolved != null &&
                (InheritanceUtil.isInheritor(resolved, "java.lang.Iterable") ||
                    resolved.qualifiedName == "java.util.Map") ||
                rawTypeName in JMIX_ENTITY_CONTAINER_TYPE_NAMES ||
                rawTypeName.substringAfterLast('.') in JMIX_ENTITY_CONTAINER_SIMPLE_NAMES
            when {
                isEntityContainer && parameters.isNotEmpty() ->
                    entityClassForType(parameters.last())

                else -> resolved
            }
        }
        else -> null
    }

private fun jmixEntityProperties(entityClass: PsiClass): List<JmixEntityProperty> {
    val properties = linkedMapOf<String, JmixEntityProperty>()
    entityClass.allFields.asSequence()
        .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
        .filter { it.name.isNotBlank() }
        .forEach { field ->
            val navigation = field.navigationElement as? PsiNamedElement
            val element = navigation?.takeIf { it.name == field.name } ?: field
            properties.putIfAbsent(
                field.name,
                JmixEntityProperty(field.name, element, field.type),
            )
        }
    entityClass.allMethods.asSequence()
        .filter { it.parameterList.parametersCount == 0 && it.returnType != null }
        .mapNotNull { method ->
            val propertyName = method.jmixGetterPropertyName() ?: return@mapNotNull null
            if (propertyName == "class") return@mapNotNull null
            val navigation = method.navigationElement as? PsiNamedElement
            val element = navigation?.takeIf { it.name == propertyName } ?: method
            JmixEntityProperty(propertyName, element, requireNotNull(method.returnType))
        }
        .forEach { property -> properties.putIfAbsent(property.name, property) }
    return properties.values.sortedBy(JmixEntityProperty::name)
}

private fun PsiMethod.jmixGetterPropertyName(): String? {
    val stem = when {
        name.startsWith("get") && name.length > 3 -> name.substring(3)
        name.startsWith("is") && name.length > 2 &&
            returnType?.canonicalText in setOf("boolean", "java.lang.Boolean") ->
            name.substring(2)
        else -> return null
    }
    return stem.replaceFirstChar(Char::lowercase)
}

internal fun findJmixFetchPlanDeclarations(
    context: PsiElement,
    entityClass: PsiClass?,
): List<XmlAttribute> {
    val project = context.project
    if (DumbService.isDumb(project)) return emptyList()
    return JmixDomainSymbolService.getInstance(project)
        .fetchPlanDeclarations()
        .asSequence()
        .map { it.parent }
        .filter { tag ->
            if (entityClass == null) return@filter true
            val declaredEntity = entityClassForFetchPlanTag(tag)
            declaredEntity != null &&
                declaredEntity.manager.areElementsEquivalent(declaredEntity, entityClass)
        }
        .mapNotNull { it.getAttribute("name") }
        .filter { !it.value.isNullOrBlank() }
        .toList()
}

private fun replaceXmlRange(
    value: XmlAttributeValue,
    range: TextRange,
    replacement: String,
): PsiElement {
    val raw = value.value
    val start = range.startOffset - 1
    val end = range.endOffset - 1
    if (start < 0 || start > end || end > raw.length) return value
    val attribute = value.parent as? XmlAttribute ?: return value
    attribute.setValue(raw.replaceRange(start, end, replacement))
    return attribute.valueElement ?: value
}

private fun xmlValueRange(value: String): TextRange = TextRange(1, value.length + 1)

private val JMIX_ENTITY_ANNOTATIONS = setOf(
    "io.jmix.core.metamodel.annotation.JmixEntity",
    "jakarta.persistence.Entity",
    "javax.persistence.Entity",
)
private val JMIX_ENTITY_ANNOTATION_SHORT_NAMES = setOf("JmixEntity", "Entity")
private val JMIX_DATA_CONTAINER_TAGS = setOf("instance", "collection")
private val JMIX_PROPERTY_REFERENCE_ATTRIBUTES = setOf("property", "hierarchyProperty")
private val JMIX_BUILT_IN_FETCH_PLANS = listOf("_base", "_instance_name", "_local")
private val JMIX_ENTITY_CONTAINER_TYPE_NAMES = setOf(
    "java.lang.Iterable",
    "java.util.Collection",
    "java.util.List",
    "java.util.Map",
    "java.util.Set",
)
private val JMIX_ENTITY_CONTAINER_SIMPLE_NAMES = setOf(
    "Iterable",
    "Collection",
    "List",
    "Map",
    "Set",
)
private const val JMIX_DOMAIN_COMPLETION_LIMIT = 2_000
