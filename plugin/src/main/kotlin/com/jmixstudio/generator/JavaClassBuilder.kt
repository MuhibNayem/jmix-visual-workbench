package com.jmixstudio.generator

/**
 * Generic, fluent Java source file builder.
 * Handles package, imports (auto-deduped & sorted), class-level annotations,
 * fields, methods, inner classes, and arbitrary code blocks.
 */
class JavaClassBuilder(private val className: String) {

    private var packageName: String = ""
    private val imports = sortedSetOf<String>()
    private val staticImports = sortedSetOf<String>()
    private val classAnnotations = mutableListOf<AnnotationBuilder>()
    private var extendsClass: String? = null
    private val implementsList = mutableListOf<String>()
    private val fields = mutableListOf<FieldBuilder>()
    private val methods = mutableListOf<MethodBuilder>()
    private val innerClasses = mutableListOf<JavaClassBuilder>()
    private val rawBlocks = mutableListOf<String>()
    private var classComment: String? = null
    private var isEnum = false
    private var enumConstants = mutableListOf<EnumConstantBuilder>()
    private var isInterface = false
    private var isAbstract = false
    private var isRecord = false

    // ─── Class-level configuration ───────────────────────────────────────────

    fun package_(pkg: String) = apply { packageName = pkg }
    fun import_(vararg fqns: String) = apply { imports.addAll(fqns) }
    fun staticImport_(vararg fqns: String) = apply { staticImports.addAll(fqns) }
    fun extends_(fqcn: String) = apply { extendsClass = fqcn; imports.add(fqcn) }
    fun implements_(vararg fqcns: String) = apply { implementsList.addAll(fqcns); imports.addAll(fqcns) }
    fun comment_(text: String) = apply { classComment = text }
    fun asEnum() = apply { isEnum = true }
    fun asInterface() = apply { isInterface = true }
    fun asAbstract() = apply { isAbstract = true }
    fun asRecord() = apply { isRecord = true }

    fun annotation(block: AnnotationBuilder.() -> Unit) = apply {
        classAnnotations.add(AnnotationBuilder().apply(block))
    }

    // ─── Enum constants ──────────────────────────────────────────────────────

    fun enumConstant(block: EnumConstantBuilder.() -> Unit) = apply {
        enumConstants.add(EnumConstantBuilder().apply(block))
    }

    // ─── Fields ──────────────────────────────────────────────────────────────

    fun field(block: FieldBuilder.() -> Unit) = apply {
        fields.add(FieldBuilder().apply(block))
    }

    // ─── Methods ─────────────────────────────────────────────────────────────

    fun method(block: MethodBuilder.() -> Unit) = apply {
        methods.add(MethodBuilder().apply(block))
    }

    // ─── Inner classes ───────────────────────────────────────────────────────

    fun innerClass(name: String, block: JavaClassBuilder.() -> Unit) = apply {
        innerClasses.add(JavaClassBuilder(name).apply(block))
    }

    // ─── Raw code blocks ─────────────────────────────────────────────────────

    fun raw(code: String) = apply { rawBlocks.add(code) }

    // ─── Build ───────────────────────────────────────────────────────────────

    fun build(): String {
        collectImportsFromAnnotations(classAnnotations)
        fields.forEach { collectImportsFromAnnotations(it.annotations) }
        methods.forEach { collectImportsFromAnnotations(it.annotations) }

        val sb = StringBuilder()

        if (packageName.isNotEmpty()) {
            sb.appendLine("package $packageName;")
            sb.appendLine()
        }

        if (imports.isNotEmpty() || staticImports.isNotEmpty()) {
            imports.forEach { sb.appendLine("import $it;") }
            if (imports.isNotEmpty() && staticImports.isNotEmpty()) sb.appendLine()
            staticImports.forEach { sb.appendLine("import static $it;") }
            sb.appendLine()
        }

        classComment?.let {
            sb.appendLine("/**")
            it.lines().forEach { line -> sb.appendLine(" * $line") }
            sb.appendLine(" */")
        }

        classAnnotations.forEach { sb.appendLine(it.build()) }

        val kind = when {
            isEnum -> "enum"
            isInterface -> "interface"
            isRecord -> "record"
            else -> "class"
        }
        val modifiers = buildString {
            append("public ")
            if (isAbstract && !isInterface) append("abstract ")
        }

        sb.append("$modifiers$kind $className")

        extendsClass?.let { sb.append(" extends ${simpleName(it)}") }
        if (implementsList.isNotEmpty()) {
            sb.append(if (isInterface) " extends " else " implements ")
            sb.append(implementsList.joinToString(", ") { simpleName(it) })
        }

        sb.appendLine(" {")

        if (isEnum && enumConstants.isNotEmpty()) {
            sb.appendLine()
            enumConstants.forEachIndexed { i, ec ->
                val sep = if (i < enumConstants.size - 1) "," else ";"
                sb.appendLine("    ${ec.build()}$sep")
            }
        }

        fields.forEach { f ->
            sb.appendLine()
            sb.append(f.build(1))
        }

        methods.forEach { m ->
            sb.appendLine()
            sb.append(m.build(1))
        }

        rawBlocks.forEach { block ->
            sb.appendLine()
            block.lines().forEach { sb.appendLine("    $it") }
        }

        innerClasses.forEach { ic ->
            sb.appendLine()
            ic.build().lines().forEach { sb.appendLine("    $it") }
        }

        sb.appendLine("}")
        return sb.toString()
    }

    private fun collectImportsFromAnnotations(annotations: List<AnnotationBuilder>) {
        annotations.forEach { ann ->
            ann.importPath?.let { imports.add(it) }
            ann.nestedAnnotations.forEach { nested ->
                nested.importPath?.let { imports.add(it) }
            }
        }
    }

    private fun simpleName(fqcn: String): String = fqcn.substringAfterLast('.')

    // ─── Nested builders ─────────────────────────────────────────────────────

    class AnnotationBuilder {
        var name: String = ""
        var importPath: String? = null
        val params = linkedMapOf<String, String>()
        val nestedAnnotations = mutableListOf<AnnotationBuilder>()

        fun param(key: String, value: String) { params[key] = value }
        fun value(v: String) { params[""] = v }
        fun nested(block: AnnotationBuilder.() -> Unit) {
            nestedAnnotations.add(AnnotationBuilder().apply(block))
        }

        fun build(): String {
            val sb = StringBuilder("@$name")
            if (params.isNotEmpty()) {
                sb.append("(")
                val parts = params.map { (k, v) ->
                    if (k.isEmpty()) v else "$k = $v"
                }
                sb.append(parts.joinToString(", "))
                sb.append(")")
            }
            return sb.toString()
        }
    }

    class EnumConstantBuilder {
        var name: String = ""
        val args = mutableListOf<String>()
        val annotations = mutableListOf<AnnotationBuilder>()
        var comment: String? = null

        fun arg(value: String) { args.add(value) }
        fun annotation(block: AnnotationBuilder.() -> Unit) {
            annotations.add(AnnotationBuilder().apply(block))
        }

        fun build(): String {
            val sb = StringBuilder()
            annotations.forEach { sb.append(it.build()).append(" ") }
            sb.append(name)
            if (args.isNotEmpty()) {
                sb.append("(").append(args.joinToString(", ")).append(")")
            }
            return sb.toString()
        }
    }

    class FieldBuilder {
        var name: String = ""
        var type: String = ""
        var typeImport: String? = null
        var initializer: String? = null
        var isStatic = false
        var isFinal = false
        var isTransient = false
        var visibility: Visibility = Visibility.PRIVATE
        val annotations = mutableListOf<AnnotationBuilder>()
        var comment: String? = null

        fun annotation(block: AnnotationBuilder.() -> Unit) {
            annotations.add(AnnotationBuilder().apply(block))
        }

        fun build(indent: Int): String {
            val pad = "    ".repeat(indent)
            val sb = StringBuilder()
            comment?.let { sb.appendLine("$pad// $it") }
            annotations.forEach { sb.appendLine("$pad${it.build()}") }
            val mods = buildString {
                append(visibility.keyword).append(" ")
                if (isStatic) append("static ")
                if (isFinal) append("final ")
                if (isTransient) append("transient ")
            }
            sb.append("$pad$mods$type $name")
            initializer?.let { sb.append(" = $it") }
            sb.appendLine(";")
            return sb.toString()
        }
    }

    class MethodBuilder {
        var name: String = ""
        var returnType: String = "void"
        var returnTypeImport: String? = null
        val parameters = mutableListOf<Pair<String, String>>()
        val parameterAnnotations = mutableMapOf<Int, MutableList<AnnotationBuilder>>()
        val body = mutableListOf<String>()
        val annotations = mutableListOf<AnnotationBuilder>()
        var visibility: Visibility = Visibility.PUBLIC
        var isStatic = false
        var isAbstract = false
        var isOverride = false
        var throwsList = mutableListOf<String>()
        var comment: String? = null
        var javadoc: String? = null

        fun param(type: String, name: String) { parameters.add(type to name) }
        fun paramAnnotation(paramIndex: Int, block: AnnotationBuilder.() -> Unit) {
            parameterAnnotations.getOrPut(paramIndex) { mutableListOf() }
                .add(AnnotationBuilder().apply(block))
        }
        fun annotation(block: AnnotationBuilder.() -> Unit) {
            annotations.add(AnnotationBuilder().apply(block))
        }
        fun line(code: String) { body.add(code) }
        fun lines(vararg code: String) { body.addAll(code) }
        fun throws_(vararg types: String) { throwsList.addAll(types) }

        fun build(indent: Int): String {
            val pad = "    ".repeat(indent)
            val sb = StringBuilder()

            javadoc?.let {
                sb.appendLine("$pad/**")
                it.lines().forEach { l -> sb.appendLine("$pad * $l") }
                sb.appendLine("$pad */")
            }
            comment?.let { sb.appendLine("$pad// $it") }

            if (isOverride) sb.appendLine("$pad@Override")
            annotations.forEach { sb.appendLine("$pad${it.build()}") }

            val mods = buildString {
                append(visibility.keyword).append(" ")
                if (isStatic) append("static ")
                if (isAbstract) append("abstract ")
            }

            val params = parameters.mapIndexed { i, (type, pName) ->
                val anns = parameterAnnotations[i]
                val annStr = anns?.joinToString(" ") { it.build() }?.let { "$it " } ?: ""
                "$annStr$type $pName"
            }.joinToString(", ")

            sb.append("$pad$mods$returnType $name($params)")
            if (throwsList.isNotEmpty()) {
                sb.append(" throws ${throwsList.joinToString(", ")}")
            }

            if (isAbstract) {
                sb.appendLine(";")
            } else {
                sb.appendLine(" {")
                body.forEach { sb.appendLine("$pad    $it") }
                sb.appendLine("$pad}")
            }
            return sb.toString()
        }
    }

    enum class Visibility(val keyword: String) {
        PUBLIC("public"), PROTECTED("protected"), PRIVATE("private"), PACKAGE("")
    }
}
