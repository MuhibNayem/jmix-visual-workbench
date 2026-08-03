package org.jmixworkbench.ide

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import java.util.Locale

/**
 * Project-version-aware FlowUI metadata used by native editor assistance.
 *
 * Jmix and add-ons publish the XML element and injection class through
 * `@StudioComponent`, `@StudioDataComponent`, `@StudioFacet` and
 * `@StudioElement`. Querying those annotations through IntelliJ's stub index
 * keeps this resolver compatible with the exact Jmix/add-on versions in the
 * opened project instead of freezing a core-only tag table in the plugin.
 *
 * The result is cached against the small set of metadata declaration files and
 * the project-root tracker. Unrelated typing therefore does not repeat the
 * library query or invalidate the cache.
 */
internal object JmixFlowUiMetadata {
    private val CACHE_KEY =
        Key.create<JmixFlowUiMetadataCache>(
            "org.jmixworkbench.flowUiMetadata",
        )

    fun snapshot(project: Project): JmixFlowUiMetadataSnapshot {
        if (DumbService.isDumb(project)) {
            return JmixFlowUiMetadataSnapshot(
                elements = emptyList(),
                declarationFiles = emptyList(),
            )
        }
        val sourceScope = GlobalSearchScope.projectScope(project)
        ensureJmixCandidateIndexUpToDate(
            project,
            JmixStudioMetadataCandidateFileIndex.NAME,
            sourceScope,
        )
        val stamp = jmixCandidateIndexStamp(
            project,
            JmixStudioMetadataCandidateFileIndex.NAME,
        )
        project.getUserData(CACHE_KEY)
            ?.takeIf { cache -> cache.stamp == stamp }
            ?.let(JmixFlowUiMetadataCache::snapshot)
            ?.let { return it }

        synchronized(project) {
            project.getUserData(CACHE_KEY)
                ?.takeIf { cache -> cache.stamp == stamp }
                ?.let(JmixFlowUiMetadataCache::snapshot)
                ?.let { return it }
            val snapshot = computeSnapshot(project)
            project.putUserData(
                CACHE_KEY,
                JmixFlowUiMetadataCache(stamp, snapshot),
            )
            return snapshot
        }
    }

    fun targetTags(
        descriptors: List<XmlFile>,
        targetPath: String,
        targetScope: String,
    ): List<XmlTag> {
        if (targetPath.isBlank()) return emptyList()
        val acceptedTags = jmixControllerTargetTags(targetScope)
        val project = descriptors.firstOrNull()?.project
        return descriptors
            .asSequence()
            .flatMap { descriptor ->
                descriptor.rootTag
                    ?.depthFirstTags()
                    ?.asSequence()
                    ?: emptySequence()
            }
            .filter { tag ->
                acceptedTags == null || tag.localName in acceptedTags
            }
            .filter { tag ->
                matchesInjectionIdentifier(project, tag, targetPath)
            }
            .plus(
                descriptors.asSequence().flatMap { descriptor ->
                    resolveDottedTarget(
                        descriptor.rootTag,
                        targetPath,
                        acceptedTags,
                        project,
                    ).asSequence()
                },
            )
            .distinctBy { tag ->
                "${tag.containingFile.virtualFile?.path}:${tag.textOffset}"
            }
            .toList()
    }

    fun expectedTypes(
        project: Project,
        tag: XmlTag,
    ): List<JmixFlowUiExpectedType> {
        val explicitClass = when (tag.localName) {
            "component", "fragment" -> tag.getAttributeValue("class")
            else -> null
        }
        if (!explicitClass.isNullOrBlank()) {
            return listOf(
                JmixFlowUiExpectedType(
                    classFqn = explicitClass,
                    source = tag.getAttribute("class") ?: tag,
                    kind = JmixFlowUiMetadataKind.COMPONENT,
                ),
            )
        }

        val namespace = tag.namespace.takeIf(String::isNotBlank)
        val metadata = snapshot(project).elements
            .filter { candidate ->
                candidate.xmlElement == tag.localName &&
                    candidate.isInjectable &&
                    (
                        candidate.namespace.isNullOrBlank() ||
                            namespace.isNullOrBlank() ||
                            candidate.namespace == namespace
                        )
            }
            .let { candidates ->
                val exactNamespace = candidates.filter {
                    !namespace.isNullOrBlank() && it.namespace == namespace
                }
                exactNamespace.ifEmpty { candidates }
            }
            .map { candidate ->
                JmixFlowUiExpectedType(
                    classFqn = candidate.classFqn,
                    source = candidate.declaration,
                    kind = candidate.kind,
                )
            }

        if (metadata.isNotEmpty()) {
            return metadata.distinctBy(JmixFlowUiExpectedType::classFqn)
        }

        return fallbackTypes(tag)
    }

    fun injectionIdentifierAttributes(tag: XmlTag): List<com.intellij.psi.xml.XmlAttribute> {
        val attributes = linkedSetOf<com.intellij.psi.xml.XmlAttribute>()
        tag.getAttribute("id")
            ?.takeIf { !it.value.isNullOrBlank() }
            ?.let(attributes::add)
        val namespace = tag.namespace.takeIf(String::isNotBlank)
        snapshot(tag.project).elements.asSequence()
            .filter { metadata ->
                metadata.xmlElement == tag.localName &&
                    metadata.isInjectable &&
                    (
                        metadata.namespace.isNullOrBlank() ||
                            namespace.isNullOrBlank() ||
                            metadata.namespace == namespace
                        )
            }
            .map(JmixFlowUiElementMetadata::injectionIdentifier)
            .filter { identifier ->
                identifier.isNotBlank() &&
                    identifier != "__empty__" &&
                    identifier != "id"
            }
            .mapNotNull(tag::getAttribute)
            .filter { !it.value.isNullOrBlank() }
            .forEach(attributes::add)
        return attributes.toList()
    }

    fun isCompatibleInjection(
        project: Project,
        injectedClass: PsiClass,
        expectedTypes: List<JmixFlowUiExpectedType>,
    ): Boolean? {
        val scope = ProjectScope.getAllScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val resolvedExpected = expectedTypes.mapNotNull { expected ->
            ProgressManager.checkCanceled()
            facade.findClass(expected.classFqn, scope)
        }
        if (resolvedExpected.isEmpty()) return null

        val recognizedRoots = JMIX_AUTOWIRE_ROOTS.mapNotNull { root ->
            ProgressManager.checkCanceled()
            facade.findClass(root, scope)
        }
        if (recognizedRoots.isNotEmpty() &&
            recognizedRoots.none { root ->
                InheritanceUtil.isInheritorOrSelf(injectedClass, root, true)
            }
        ) {
            return false
        }
        if (expectedTypes.all { it.kind == JmixFlowUiMetadataKind.FALLBACK }) {
            return true.takeIf { recognizedRoots.isNotEmpty() }
        }

        return resolvedExpected.any { expected ->
            InheritanceUtil.isInheritorOrSelf(expected, injectedClass, true)
        }
    }

    fun subscribeSubjects(
        targetClasses: List<PsiClass>,
        eventType: PsiType,
    ): List<JmixSubscribeSubject> {
        val inferred = targetClasses
            .asSequence()
            .flatMap { targetClass -> targetClass.allMethods.asSequence() }
            .onEach { ProgressManager.checkCanceled() }
            .mapNotNull { method ->
                method.toSubscribeSubject(eventType)
            }
            .toList()
        val project = targetClasses.firstOrNull()?.project
        val targetFqns = targetClasses.mapNotNull(PsiClass::getQualifiedName)
            .toSet()
        val custom = if (project == null || targetFqns.isEmpty()) {
            emptyList()
        } else {
            val eventClass = PsiUtil.resolveClassInClassTypeOnly(eventType)
            snapshot(project).elements
                .asSequence()
                .filter { metadata -> metadata.classFqn in targetFqns }
                .flatMap { metadata ->
                    metadata.customSubscriptions.asSequence()
                }
                .filter { customSubscription ->
                    val expectedEvent = JavaPsiFacade.getInstance(project)
                        .findClass(
                            customSubscription.eventClassFqn,
                            ProjectScope.getAllScope(project),
                        )
                    expectedEvent != null &&
                        eventClass != null &&
                        InheritanceUtil.isInheritorOrSelf(
                            eventClass,
                            expectedEvent,
                            true,
                        )
                }
                .map { customSubscription ->
                    JmixSubscribeSubject(
                        logicalName = customSubscription.logicalName,
                        method = customSubscription.declaration,
                    )
                }
                .toList()
        }

        return (inferred + custom)
            .distinctBy { subject ->
                "${subject.logicalName}:${subject.method.containingClass?.qualifiedName}:" +
                    "${subject.method.name}:${subject.method.textOffset}"
            }
            .sortedWith(
                compareBy(
                    JmixSubscribeSubject::logicalName,
                    { it.method.containingClass?.qualifiedName.orEmpty() },
                ),
            )
            .toList()
    }

    fun subscribeTargetClasses(
        controllerClass: PsiClass,
        annotation: PsiAnnotation,
    ): List<PsiClass> {
        val scopeName = annotation.findDeclaredAttributeValue("target")
            ?.text
            ?.substringAfterLast('.')
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: "COMPONENT"
        val targetPath = annotation.declaredConstantString("value")
            ?: annotation.declaredConstantString("id")
        if (targetPath.isNullOrBlank()) {
            return when (scopeName) {
                "COMPONENT", "CONTROLLER" -> listOf(controllerClass)
                "DATA_CONTEXT" -> listOfNotNull(
                    JavaPsiFacade.getInstance(controllerClass.project).findClass(
                        "io.jmix.flowui.model.DataContext",
                        ProjectScope.getAllScope(controllerClass.project),
                    ),
                )

                else -> emptyList()
            }
        }
        if (scopeName == "HOST_CONTROLLER") return emptyList()

        val descriptors = jmixDescriptorFilesForController(
            annotation,
            controllerClass,
        )
        val tags = targetTags(descriptors, targetPath, scopeName)
        if (tags.size != 1) return emptyList()
        return expectedClasses(controllerClass.project, tags.single())
    }

    fun expectedClasses(
        project: Project,
        tag: XmlTag,
    ): List<PsiClass> {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = ProjectScope.getAllScope(project)
        return expectedTypes(project, tag)
            .mapNotNull { expected ->
                ProgressManager.checkCanceled()
                facade.findClass(expected.classFqn, scope)
            }
            .distinctBy { it.qualifiedName ?: it.name }
    }

    fun kotlinTargetClasses(
        context: PsiElement,
        descriptors: List<XmlFile>,
        targetPath: String,
        targetScope: String,
    ): List<PsiClass> {
        if (targetPath.isBlank() || targetScope == "HOST_CONTROLLER") {
            return emptyList()
        }
        val tags = targetTags(descriptors, targetPath, targetScope)
        if (tags.size != 1) return emptyList()
        return expectedClasses(context.project, tags.single())
    }

    fun resolveKotlinClass(
        context: PsiElement,
        sourceType: String,
    ): PsiClass? {
        val rawType = sourceType
            .substringBefore('<')
            .removeSuffix("?")
            .trim()
        val simpleName = rawType.substringAfterLast('.').substringAfterLast('$')
        if (simpleName.isBlank()) return null
        val candidates = PsiShortNamesCache.getInstance(context.project)
            .getClassesByName(
                simpleName,
                ProjectScope.getAllScope(context.project),
            )
        if ('.' in rawType) {
            candidates.firstOrNull { it.qualifiedName == rawType }?.let {
                return it
            }
        }
        val importedFqn = context.containingFile.text
            .lineSequence()
            .map(String::trim)
            .firstOrNull { line ->
                line == "import $rawType" ||
                    line.endsWith(".$simpleName") &&
                    line.startsWith("import ")
            }
            ?.removePrefix("import ")
            ?.substringBefore(" as ")
        return candidates.firstOrNull { it.qualifiedName == importedFqn }
            ?: candidates.singleOrNull()
    }

    private fun computeSnapshot(project: Project): JmixFlowUiMetadataSnapshot {
        val scope = ProjectScope.getAllScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val elements = mutableListOf<JmixFlowUiElementMetadata>()
        val files = linkedSetOf<PsiFile>()

        JMIX_STUDIO_ELEMENT_ANNOTATIONS.forEach { (annotationFqn, kind) ->
            ProgressManager.checkCanceled()
            val annotationClass = facade.findClass(annotationFqn, scope)
                ?: return@forEach
            val methods = runCatching {
                AnnotatedElementsSearch.searchPsiMethods(annotationClass, scope)
                    .findAll()
            }.getOrDefault(emptyList())
            methods.forEach { method ->
                ProgressManager.checkCanceled()
                val annotation = method.modifierList.findAnnotation(annotationFqn)
                    ?: return@forEach
                val xmlElement = annotation.constantString("xmlElement")
                    ?: return@forEach
                val classFqn = annotation.constantString("classFqn")
                    ?: return@forEach
                if (xmlElement.isBlank() || classFqn.isBlank()) return@forEach
                val isInjectable = annotation.constantBoolean("isInjectable")
                    ?: true
                elements += JmixFlowUiElementMetadata(
                    xmlElement = xmlElement,
                    namespace = annotation.constantString("xmlns")
                        ?.takeIf(String::isNotBlank),
                    classFqn = classFqn,
                    injectionIdentifier =
                        annotation.constantString("injectionIdentifier")
                            ?.takeIf(String::isNotBlank)
                            ?: "id",
                    isInjectable = isInjectable,
                    kind = kind,
                    declaration = method,
                    customSubscriptions =
                        annotation.customSubscriptions(method),
                )
                method.containingFile?.let(files::add)
            }
        }

        return JmixFlowUiMetadataSnapshot(
            elements = elements
                .distinctBy { metadata ->
                    "${metadata.namespace}:${metadata.xmlElement}:" +
                        "${metadata.classFqn}:${metadata.injectionIdentifier}:" +
                        metadata.customSubscriptions.joinToString {
                            "${it.logicalName}=${it.eventClassFqn}"
                        }
                }
                .sortedWith(
                    compareBy(
                        JmixFlowUiElementMetadata::xmlElement,
                        { it.namespace.orEmpty() },
                        JmixFlowUiElementMetadata::classFqn,
                    ),
                ),
            declarationFiles = files.toList(),
        )
    }

    private fun fallbackTypes(tag: XmlTag): List<JmixFlowUiExpectedType> {
        val classFqn = when (tag.localName) {
            "action" -> "io.jmix.flowui.kit.action.Action"
            "dataContext" -> "io.jmix.flowui.model.DataContext"
            "loader" -> when (tag.parentTag?.localName) {
                "collection" -> "io.jmix.flowui.model.CollectionLoader"
                "instance" -> "io.jmix.flowui.model.InstanceLoader"
                "keyValueCollection" ->
                    "io.jmix.flowui.model.KeyValueCollectionLoader"

                "keyValueInstance" ->
                    "io.jmix.flowui.model.KeyValueInstanceLoader"

                else -> "io.jmix.flowui.model.DataLoader"
            }

            else -> return emptyList()
        }
        return listOf(
            JmixFlowUiExpectedType(
                classFqn = classFqn,
                source = tag,
                kind = JmixFlowUiMetadataKind.FALLBACK,
            ),
        )
    }

    private fun resolveDottedTarget(
        root: XmlTag?,
        targetPath: String,
        acceptedTags: Set<String>?,
        project: Project?,
    ): List<XmlTag> {
        root ?: return emptyList()
        val segments = targetPath.split('.').filter(String::isNotBlank)
        if (segments.size < 2) return emptyList()

        var owners = root.depthFirstTags()
            .filter { tag ->
                matchesInjectionIdentifier(project, tag, segments.first())
            }
        segments.drop(1).forEachIndexed { index, segment ->
            val terminal = index == segments.lastIndex - 1
            owners = owners.flatMap { owner ->
                owner.depthFirstTags(includeSelf = false)
                    .filter { candidate ->
                        matchesInjectionIdentifier(
                            project,
                            candidate,
                            segment,
                        ) &&
                            (!terminal ||
                                acceptedTags == null ||
                                candidate.localName in acceptedTags)
                    }
            }
        }
        return owners
    }

    private fun matchesInjectionIdentifier(
        project: Project?,
        tag: XmlTag,
        expected: String,
    ): Boolean {
        if (project == null) return tag.getAttributeValue("id") == expected
        return injectionIdentifierAttributes(tag).any { attribute ->
            attribute.value == expected
        }
    }
}

internal data class JmixFlowUiMetadataSnapshot(
    val elements: List<JmixFlowUiElementMetadata>,
    val declarationFiles: List<PsiFile>,
)

private data class JmixFlowUiMetadataCache(
    val stamp: JmixCandidateIndexStamp,
    val snapshot: JmixFlowUiMetadataSnapshot,
)

internal data class JmixFlowUiElementMetadata(
    val xmlElement: String,
    val namespace: String?,
    val classFqn: String,
    val injectionIdentifier: String,
    val isInjectable: Boolean,
    val kind: JmixFlowUiMetadataKind,
    val declaration: PsiModifierListOwner,
    val customSubscriptions: List<JmixFlowUiCustomSubscription>,
)

internal data class JmixFlowUiCustomSubscription(
    val logicalName: String,
    val eventClassFqn: String,
    val declaration: PsiMethod,
)

internal data class JmixFlowUiExpectedType(
    val classFqn: String,
    val source: PsiElement,
    val kind: JmixFlowUiMetadataKind,
)

internal enum class JmixFlowUiMetadataKind {
    COMPONENT,
    DATA_COMPONENT,
    FACET,
    ELEMENT,
    FALLBACK,
}

internal data class JmixSubscribeSubject(
    val logicalName: String,
    val method: PsiMethod,
)

private fun PsiMethod.toSubscribeSubject(
    eventType: PsiType,
): JmixSubscribeSubject? {
    if (parameterList.parametersCount != 1) return null
    val isSetter = name.startsWith("set") && returnType == PsiTypes.voidType()
    val returnFqn = returnType?.canonicalText?.substringBefore('<')
    val isAdder = name.startsWith("add") &&
        returnFqn in JMIX_SUBSCRIPTION_RETURN_TYPES
    if (!isSetter && !isAdder) return null

    val parameterType = parameterList.parameters.single().type
    val listenerEventTypes = JMIX_LISTENER_BASE_TYPES.mapNotNull { listenerFqn ->
        PsiUtil.substituteTypeParameter(
            parameterType,
            listenerFqn,
            0,
            false,
        )
    }
    if (listenerEventTypes.none { listenerEvent ->
            listenerEvent.isAssignableFromEvent(eventType)
        }
    ) {
        return null
    }

    val prefixLength = if (isSetter) 3 else 3
    val logicalName = name.substring(prefixLength)
        .replaceFirstChar { character ->
            character.toString().lowercase(Locale.ROOT)
        }
        .takeIf(String::isNotBlank)
        ?: return null
    return JmixSubscribeSubject(logicalName, this)
}

private fun PsiType.isAssignableFromEvent(eventType: PsiType): Boolean {
    if (canonicalText == eventType.canonicalText) return true
    val expectedClass = PsiUtil.resolveClassInClassTypeOnly(this) ?: return false
    val eventClass = PsiUtil.resolveClassInClassTypeOnly(eventType) ?: return false
    return InheritanceUtil.isInheritorOrSelf(eventClass, expectedClass, true)
}

private fun PsiAnnotation.constantString(name: String): String? =
    findAttributeValue(name)?.let { value ->
        JavaPsiFacade.getInstance(project)
            .constantEvaluationHelper
            .computeConstantExpression(value) as? String
    }

private fun PsiAnnotation.declaredConstantString(name: String): String? =
    findDeclaredAttributeValue(name)?.let { value ->
        JavaPsiFacade.getInstance(project)
            .constantEvaluationHelper
            .computeConstantExpression(value) as? String
    }?.takeIf(String::isNotBlank)

private fun PsiAnnotation.constantBoolean(name: String): Boolean? =
    findAttributeValue(name)?.let { value ->
        JavaPsiFacade.getInstance(project)
            .constantEvaluationHelper
            .computeConstantExpression(value) as? Boolean
    }

private fun PsiAnnotation.customSubscriptions(
    declaration: PsiMethod,
): List<JmixFlowUiCustomSubscription> =
    PsiTreeUtil.findChildrenOfType(
        this,
        PsiAnnotation::class.java,
    ).asSequence()
        .filter { nested ->
            nested.qualifiedName?.substringAfterLast('.') ==
                "StudioCustomSubscription" ||
                nested.nameReferenceElement?.referenceName ==
                "StudioCustomSubscription"
        }
        .mapNotNull { nested ->
            val logicalName = nested.constantString("methodName")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val eventClassFqn = nested.constantString("eventClassFqn")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            JmixFlowUiCustomSubscription(
                logicalName = logicalName,
                eventClassFqn = eventClassFqn,
                declaration = declaration,
            )
        }
        .distinctBy { "${it.logicalName}:${it.eventClassFqn}" }
        .toList()

private fun XmlTag.depthFirstTags(
    includeSelf: Boolean = true,
): List<XmlTag> = buildList {
    if (includeSelf) add(this@depthFirstTags)
    subTags.forEach { child ->
        addAll(child.depthFirstTags())
    }
}

private val JMIX_STUDIO_ELEMENT_ANNOTATIONS = linkedMapOf(
    "io.jmix.flowui.kit.meta.StudioComponent" to
        JmixFlowUiMetadataKind.COMPONENT,
    "io.jmix.flowui.kit.meta.StudioDataComponent" to
        JmixFlowUiMetadataKind.DATA_COMPONENT,
    "io.jmix.flowui.kit.meta.StudioFacet" to
        JmixFlowUiMetadataKind.FACET,
    "io.jmix.flowui.kit.meta.StudioElement" to
        JmixFlowUiMetadataKind.ELEMENT,
)

private val JMIX_AUTOWIRE_ROOTS = listOf(
    "com.vaadin.flow.component.Component",
    "io.jmix.flowui.model.InstanceContainer",
    "io.jmix.flowui.model.DataLoader",
    "io.jmix.flowui.model.DataContext",
    "io.jmix.flowui.kit.action.Action",
    "io.jmix.flowui.facet.Facet",
    "io.jmix.flowui.view.MessageBundle",
)

private val JMIX_LISTENER_BASE_TYPES = listOf(
    "java.util.function.Consumer",
    "com.vaadin.flow.component.ComponentEventListener",
    "com.vaadin.flow.component.HasValue.ValueChangeListener",
)

private val JMIX_SUBSCRIPTION_RETURN_TYPES = setOf(
    "com.vaadin.flow.shared.Registration",
    "io.jmix.core.common.event.Subscription",
)
