package org.jmixworkbench.project

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

internal data class JmixTemplateBundleAuthoringInput(
    val sourceRoot: Path,
    val output: Path,
    val baseRequest: JmixProjectTemplateRequest,
    val catalogId: String,
    val catalogVersion: String,
    val catalogDisplayName: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val templateId: String,
    val templateVersion: String,
    val templateName: String,
    val templateDescription: String,
    val templateOrder: Int,
    val signingChoice: JmixTemplateSigningChoice,
) {
    fun draft(plan: JmixTemplateOverlayPlan): JmixTemplateCatalogDraft =
        JmixTemplateCatalogDraft(
            catalogId = catalogId,
            catalogVersion = catalogVersion,
            displayName = catalogDisplayName,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            templates = listOf(
                plan.toTemplateDraft(
                    id = templateId,
                    version = templateVersion,
                    name = templateName,
                    description = templateDescription,
                    order = templateOrder,
                    request = baseRequest,
                ),
            ),
        )
}

internal sealed interface JmixTemplateSigningChoice {
    data object TransientFiles : JmixTemplateSigningChoice {
        override fun toString(): String = "Transient Ed25519 key files"
    }

    data class Enterprise(
        val provider: JmixTemplateCatalogSigningProvider,
    ) : JmixTemplateSigningChoice {
        override fun toString(): String = provider.displayName
    }
}

internal class JmixTemplateCatalogAuthoringDialog(
    private val clock: Clock = Clock.systemUTC(),
) : DialogWrapper(false) {
    private val sourceRoot = JBTextField()
    private val projectName = JBTextField()
    private val groupId = JBTextField("com.company")
    private val artifactId = JBTextField("jmix-application")
    private val basePackage = JBTextField("com.company.application")
    private val projectId = JBTextField("app")
    private val templateKind = JComboBox(JmixProjectTemplateKind.entries.toTypedArray())
    private val language = JComboBox(JmixProjectLanguage.entries.toTypedArray())
    private val uiKind = JComboBox(JmixProjectUiKind.entries.toTypedArray()).apply {
        selectedItem = JmixProjectUiKind.FLOW_UI
    }
    private val jmixVersion = JComboBox(
        JmixProjectTemplateGenerator.certifiedVersions.map { it.jmixVersion }.toTypedArray(),
    )
    private val javaVersion = JComboBox(arrayOf(17, 21, 25))
    private val locales = JBTextField("en")
    private val useMavenLocal = JBCheckBox("Base project used Maven Local")
    private val repositories = JBTextArea(2, 60).apply {
        lineWrap = false
    }

    private val catalogId = JBTextField("company.templates")
    private val catalogVersion = JBTextField("1.0.0")
    private val catalogDisplayName = JBTextField("Company Jmix templates")
    private val lifetimeDays = JBTextField("365")
    private val authoredTemplateId = JBTextField("company-flowui")
    private val authoredTemplateVersion = JBTextField("1.0.0")
    private val authoredTemplateName = JBTextField("Company governed application")
    private val authoredTemplateDescription = JBTextArea(2, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val authoredTemplateOrder = JBTextField("10")

    private val signingChoices = listOf<JmixTemplateSigningChoice>(
        JmixTemplateSigningChoice.TransientFiles,
    ) + JmixTemplateCatalogSigningProvider.available().map(JmixTemplateSigningChoice::Enterprise)
    private val signingChoice = JComboBox(
        DefaultComboBoxModel(signingChoices.toTypedArray()),
    )
    private val signingKeyId = JBTextField("company-release")
    private val privateKeyPath = JBTextField()
    private val publicKeyPath = JBTextField()
    private val outputDirectory = JBTextField()
    private val outputFileName = JBTextField("company-templates-1.0.0.jmix-template-catalog")

    init {
        title = "Create Signed Jmix Project Template"
        setOKButtonText("Review Changes")
        isResizable = true
        signingChoice.addActionListener { refreshSigningFields() }
        templateKind.addActionListener {
            if (templateKind.selectedItem == JmixProjectTemplateKind.ADDON) {
                uiKind.selectedItem = JmixProjectUiKind.HEADLESS
                uiKind.isEnabled = false
            } else {
                uiKind.isEnabled = true
            }
        }
        jmixVersion.addActionListener {
            val selected = jmixVersion.selectedItem as? String ?: return@addActionListener
            val certified = JmixProjectTemplateGenerator.certifiedVersions
                .single { it.jmixVersion == selected }
            if (javaVersion.selectedItem !in certified.supportedJavaVersions) {
                javaVersion.selectedItem = certified.supportedJavaVersions.min()
            }
        }
        catalogVersion.document.addDocumentListener(
            SimpleDocumentListener {
                val suggestedPrefix = "company-templates-"
                if (outputFileName.text.startsWith(suggestedPrefix)) {
                    outputFileName.text =
                        "$suggestedPrefix${catalogVersion.text.trim()}.jmix-template-catalog"
                }
            },
        )
        init()
        refreshSigningFields()
    }

    public override fun createCenterPanel(): JComponent {
        val content = panel {
            group("Customized project and exact certified base") {
                row("Customized project:") {
                    cell(sourceRoot).align(Align.FILL).resizableColumn()
                    button("Choose…") { chooseDirectory(sourceRoot, "Choose Customized Jmix Project") }
                }
                row("Project name:") {
                    cell(projectName).align(Align.FILL)
                }
                row("Group / artifact:") {
                    cell(groupId).align(Align.FILL)
                    cell(artifactId).align(Align.FILL)
                }
                row("Base package / project ID:") {
                    cell(basePackage).align(Align.FILL)
                    cell(projectId).align(Align.FILL)
                }
                row("Project type / language / UI:") {
                    cell(templateKind)
                    cell(language)
                    cell(uiKind)
                }
                row("Jmix / Java / locales:") {
                    cell(jmixVersion)
                    cell(javaVersion)
                    cell(locales).align(Align.FILL)
                }
                row {
                    cell(useMavenLocal)
                }
                row("Additional HTTPS repositories:") {
                    scrollCell(repositories).align(Align.FILL)
                }
                row {
                    comment(
                        "These values must describe the base originally used to create the customized project. " +
                            "Build output and IDE metadata are ignored; bundled Gradle wrapper files are protected.",
                    )
                }
            }
            group("Catalog and template identity") {
                row("Catalog ID / version:") {
                    cell(catalogId).align(Align.FILL)
                    cell(catalogVersion).align(Align.FILL)
                }
                row("Catalog name / lifetime days:") {
                    cell(catalogDisplayName).align(Align.FILL)
                    cell(lifetimeDays)
                }
                row("Template ID / version:") {
                    cell(authoredTemplateId).align(Align.FILL)
                    cell(authoredTemplateVersion).align(Align.FILL)
                }
                row("Template name / order:") {
                    cell(authoredTemplateName).align(Align.FILL)
                    cell(authoredTemplateOrder)
                }
                row("Description:") {
                    scrollCell(authoredTemplateDescription).align(Align.FILL)
                }
            }
            group("Signing and create-only export") {
                row("Signing method:") {
                    cell(signingChoice).align(Align.FILL)
                    comment(
                        "Installed signing providers can keep keys in PKCS#11, HSM, secure-enclave, " +
                            "or approved remote signing systems.",
                    )
                }
                row("Signing key ID:") {
                    cell(signingKeyId).align(Align.FILL)
                }
                row("PKCS#8 private key:") {
                    cell(privateKeyPath).align(Align.FILL).resizableColumn()
                    button("Choose…") { chooseFile(privateKeyPath, "Choose Ed25519 Private Key") }
                }
                row("X.509 public key:") {
                    cell(publicKeyPath).align(Align.FILL).resizableColumn()
                    button("Choose…") { chooseFile(publicKeyPath, "Choose Ed25519 Public Key") }
                }
                row("Output directory:") {
                    cell(outputDirectory).align(Align.FILL).resizableColumn()
                    button("Choose…") { chooseDirectory(outputDirectory, "Choose Catalog Output Directory") }
                }
                row("New bundle filename:") {
                    cell(outputFileName).align(Align.FILL)
                    comment("Existing output is never replaced.")
                }
            }
        }
        return JBScrollPane(content).apply {
            preferredSize = Dimension(940, 700)
            border = null
            verticalScrollBar.unitIncrement = 16
            accessibleContext.accessibleName = "Signed Jmix project template authoring form"
        }
    }

    override fun doValidate(): ValidationInfo? {
        fun problem(message: String, component: JComponent): ValidationInfo =
            ValidationInfo(message, component)
        val source = path(sourceRoot.text)
        if (
            source == null ||
            !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(source)
        ) {
            return problem("Choose a real customized project directory.", sourceRoot)
        }
        val request = runCatching { baseRequest() }.getOrElse { failure ->
            return problem(failure.message ?: "Base project values are invalid.", projectName)
        }
        runCatching { JmixProjectTemplateGenerator.validate(request) }.exceptionOrNull()?.let {
            return problem(it.message ?: "Base project values are invalid.", projectName)
        }
        val identifier = Regex("[a-z][a-z0-9.-]{0,95}")
        val version = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?")
        if (!identifier.matches(catalogId.text.trim())) {
            return problem("Use a lowercase DNS-style catalog ID.", catalogId)
        }
        if (!version.matches(catalogVersion.text.trim())) {
            return problem("Use a numeric dotted catalog version.", catalogVersion)
        }
        if (catalogDisplayName.text.trim().isEmpty()) {
            return problem("Catalog name is required.", catalogDisplayName)
        }
        val days = lifetimeDays.text.trim().toLongOrNull()
        if (days == null || days !in 0..3650) {
            return problem("Lifetime must be 0-3650 days; 0 means no expiry.", lifetimeDays)
        }
        if (!identifier.matches(authoredTemplateId.text.trim())) {
            return problem("Use a lowercase DNS-style template ID.", authoredTemplateId)
        }
        if (!version.matches(authoredTemplateVersion.text.trim())) {
            return problem("Use a numeric dotted template version.", authoredTemplateVersion)
        }
        if (authoredTemplateName.text.trim().isEmpty()) {
            return problem("Template name is required.", authoredTemplateName)
        }
        if (authoredTemplateDescription.text.length > 1_000) {
            return problem("Description cannot exceed 1,000 characters.", authoredTemplateDescription)
        }
        if (authoredTemplateOrder.text.trim().toIntOrNull() !in -100_000..100_000) {
            return problem("Order must be between -100000 and 100000.", authoredTemplateOrder)
        }
        when (val selected = signingChoice.selectedItem) {
            JmixTemplateSigningChoice.TransientFiles -> {
                if (!identifier.matches(signingKeyId.text.trim())) {
                    return problem("Use a lowercase DNS-style signing key ID.", signingKeyId)
                }
                if (!realFile(privateKeyPath.text)) {
                    return problem("Choose a real PKCS#8 private-key file.", privateKeyPath)
                }
                if (!realFile(publicKeyPath.text)) {
                    return problem("Choose a real X.509 public-key file.", publicKeyPath)
                }
            }

            is JmixTemplateSigningChoice.Enterprise -> {
                if (
                    !identifier.matches(selected.provider.keyId) ||
                    selected.provider.publicKeyX509Base64.isBlank()
                ) {
                    return problem(
                        "The selected enterprise signing provider exposes invalid identity metadata.",
                        signingChoice,
                    )
                }
            }
        }
        val output = outputPath()
        val parent = output?.parent
        if (
            output == null || parent == null ||
            !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(parent)
        ) {
            return problem("Choose a real output directory and filename.", outputDirectory)
        }
        if (!output.fileName.toString().matches(Regex("[A-Za-z0-9._-]+\\.jmix-template-catalog"))) {
            return problem("Use a portable .jmix-template-catalog filename.", outputFileName)
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            return problem("The output already exists; choose a new filename.", outputFileName)
        }
        return null
    }

    fun showAndGetInput(): JmixTemplateBundleAuthoringInput? {
        if (!showAndGet()) return null
        val issued = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val days = lifetimeDays.text.trim().toLong()
        return JmixTemplateBundleAuthoringInput(
            sourceRoot = requireNotNull(path(sourceRoot.text)),
            output = requireNotNull(outputPath()),
            baseRequest = baseRequest(),
            catalogId = catalogId.text.trim(),
            catalogVersion = catalogVersion.text.trim(),
            catalogDisplayName = catalogDisplayName.text.trim(),
            issuedAt = issued,
            expiresAt = if (days == 0L) null else issued.plus(Duration.ofDays(days)),
            templateId = authoredTemplateId.text.trim(),
            templateVersion = authoredTemplateVersion.text.trim(),
            templateName = authoredTemplateName.text.trim(),
            templateDescription = authoredTemplateDescription.text.trim(),
            templateOrder = authoredTemplateOrder.text.trim().toInt(),
            signingChoice = requireNotNull(signingChoice.selectedItem as? JmixTemplateSigningChoice),
        )
    }

    fun resolveSigner(input: JmixTemplateBundleAuthoringInput): JmixTemplateCatalogSigningIdentity =
        when (val selected = input.signingChoice) {
            JmixTemplateSigningChoice.TransientFiles ->
                JmixTemplateCatalogSigner.fromFiles(
                    keyId = signingKeyId.text.trim(),
                    privateKeyPkcs8 = requireNotNull(path(privateKeyPath.text)),
                    publicKeyX509 = requireNotNull(path(publicKeyPath.text)),
                )

            is JmixTemplateSigningChoice.Enterprise -> selected.provider
        }

    private fun baseRequest(): JmixProjectTemplateRequest =
        JmixProjectTemplateRequest(
            projectName = projectName.text.trim(),
            groupId = groupId.text.trim(),
            artifactId = artifactId.text.trim(),
            basePackage = basePackage.text.trim(),
            projectId = projectId.text.trim(),
            jmixVersion = requireNotNull(jmixVersion.selectedItem as? String),
            javaVersion = requireNotNull(javaVersion.selectedItem as? Int),
            templateKind = requireNotNull(templateKind.selectedItem as? JmixProjectTemplateKind),
            language = requireNotNull(language.selectedItem as? JmixProjectLanguage),
            uiKind = if (templateKind.selectedItem == JmixProjectTemplateKind.ADDON) {
                JmixProjectUiKind.HEADLESS
            } else {
                requireNotNull(uiKind.selectedItem as? JmixProjectUiKind)
            },
            locales = locales.text.split(','),
            useMavenLocal = useMavenLocal.isSelected,
            additionalRepositories = repositories.text.lineSequence().toList(),
        )

    private fun outputPath(): Path? {
        val directory = path(outputDirectory.text) ?: return null
        val name = outputFileName.text.trim()
        if (name.isEmpty()) return null
        return directory.resolve(name).toAbsolutePath().normalize()
    }

    private fun refreshSigningFields() {
        val local = signingChoice.selectedItem == JmixTemplateSigningChoice.TransientFiles
        signingKeyId.isEnabled = local
        privateKeyPath.isEnabled = local
        publicKeyPath.isEnabled = local
        if (!local) {
            val provider = (signingChoice.selectedItem as? JmixTemplateSigningChoice.Enterprise)?.provider
            signingKeyId.text = provider?.keyId.orEmpty()
        } else if (signingKeyId.text.isBlank()) {
            signingKeyId.text = "company-release"
        }
    }

    private fun chooseDirectory(
        target: JBTextField,
        chooserTitle: String,
    ) {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle(chooserTitle)
        FileChooser.chooseFile(descriptor, null, null)?.takeIf { it.isInLocalFileSystem }?.let {
            target.text = it.toNioPath().toString()
        }
    }

    private fun chooseFile(
        target: JBTextField,
        chooserTitle: String,
    ) {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle(chooserTitle)
        FileChooser.chooseFile(descriptor, null, null)?.takeIf { it.isInLocalFileSystem }?.let {
            target.text = it.toNioPath().toString()
        }
    }

    private fun path(value: String): Path? =
        value.trim().takeIf(String::isNotEmpty)?.let { text ->
            runCatching { Path.of(text).toAbsolutePath().normalize() }.getOrNull()
        }

    private fun realFile(value: String): Boolean {
        val candidate = path(value) ?: return false
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(candidate)
    }
}

internal class JmixTemplateOverlayPreviewDialog(
    private val plan: JmixTemplateOverlayPlan,
) : DialogWrapper(false) {
    private val table = JBTable(OverlayPreviewTableModel(plan.previews))
    private val beforeText = previewArea("Base content")
    private val afterText = previewArea("Customized content")

    init {
        title = "Review Signed Template Changes"
        setOKButtonText("Sign and Export")
        isResizable = true
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoCreateRowSorter = true
        table.accessibleContext.accessibleName = "Project template file changes"
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) updatePreview()
        }
        init()
        if (plan.previews.isNotEmpty()) {
            table.setRowSelectionInterval(0, 0)
            updatePreview()
        }
    }

    public override fun createCenterPanel(): JComponent {
        val diff = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            labeledPreview("Certified base", beforeText),
            labeledPreview("Customized result", afterText),
        ).apply {
            resizeWeight = 0.5
            setDividerLocation(0.5)
            accessibleContext.accessibleName = "Side-by-side template content comparison"
        }
        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JBScrollPane(table),
            diff,
        ).apply {
            resizeWeight = 0.42
            dividerLocation = 260
        }
        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(1_020, 700)
            add(
                JBLabel(
                    "<html><b>${plan.changes.size} reviewed changes:</b> " +
                        "${plan.addedCount} add, ${plan.replacedCount} replace, " +
                        "${plan.deletedCount} delete, ${plan.binaryCount} binary.<br>" +
                        "Source SHA-256: ${plan.sourceSha256}" +
                        if (plan.ignoredPaths.isEmpty()) {
                            ""
                        } else {
                            "<br>Ignored build/IDE roots: ${plan.ignoredPaths.joinToString()}"
                        } +
                        "</html>",
                ),
                BorderLayout.NORTH,
            )
            add(split, BorderLayout.CENTER)
        }
    }

    private fun updatePreview() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        val row = table.convertRowIndexToModel(viewRow)
        val preview = plan.previews[row]
        beforeText.text = render(preview.before, preview.beforeExecutable)
        afterText.text = render(preview.after, preview.afterExecutable)
        beforeText.caretPosition = 0
        afterText.caretPosition = 0
    }

    private fun render(
        bytes: ByteArray?,
        executable: Boolean,
    ): String {
        if (bytes == null) return "(file does not exist)\n"
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (decoded != null && bytes.none { it == 0.toByte() }) {
            return buildString {
                append(if (executable) "[executable]\n" else "[not executable]\n")
                append(decoded)
            }
        }
        val digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes),
        )
        return "Binary file\nBytes: ${bytes.size}\nSHA-256: $digest\n" +
            "Executable: ${if (executable) "yes" else "no"}\n"
    }

    private fun previewArea(accessibleName: String): JBTextArea =
        JBTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            lineWrap = false
            accessibleContext.accessibleName = accessibleName
        }

    private fun labeledPreview(
        label: String,
        area: JBTextArea,
    ): JComponent = JPanel(BorderLayout(0, 4)).apply {
        add(JBLabel(label), BorderLayout.NORTH)
        add(JBScrollPane(area), BorderLayout.CENTER)
    }

    private class OverlayPreviewTableModel(
        private val rows: List<JmixTemplateOverlayPreview>,
    ) : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 5
        override fun getColumnName(column: Int): String = when (column) {
            0 -> "Action"
            1 -> "Kind"
            2 -> "Path"
            3 -> "Bytes"
            else -> "Executable"
        }

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.action.name
                1 -> row.payloadKind?.name ?: "—"
                2 -> row.relativePath
                3 -> if (row.afterSize >= 0) row.afterSize else row.beforeSize.coerceAtLeast(0)
                else -> row.afterExecutable
            }
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            3 -> Int::class.javaObjectType
            4 -> Boolean::class.javaObjectType
            else -> String::class.java
        }
    }
}

private fun interface SimpleDocumentListener : javax.swing.event.DocumentListener {
    fun changed()

    override fun insertUpdate(event: javax.swing.event.DocumentEvent) = changed()
    override fun removeUpdate(event: javax.swing.event.DocumentEvent) = changed()
    override fun changedUpdate(event: javax.swing.event.DocumentEvent) = changed()
}
