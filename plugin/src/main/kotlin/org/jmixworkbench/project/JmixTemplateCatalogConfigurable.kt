package org.jmixworkbench.project

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class JmixTemplateCatalogConfigurable : Configurable {
    private var component: JPanel? = null
    private val offlineMode = JBCheckBox("Work offline; never refresh catalogs from the network")
    private val model = CatalogTableModel()
    private val table = JBTable(model)

    override fun getDisplayName(): String = "Jmix Organization Templates"

    override fun createComponent(): JComponent {
        if (component == null) {
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            table.emptyText.text = "No organization template catalogs configured"
            table.accessibleContext.accessibleName = "Organization template catalogs"
            table.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (event.clickCount == 2) editSelected()
                    }
                },
            )
            val buttons = JPanel(FlowLayout(FlowLayout.LEADING, 8, 0)).apply {
                add(JButton("Add…").also { it.addActionListener { addCatalog() } })
                add(JButton("Edit…").also { it.addActionListener { editSelected() } })
                add(JButton("Remove").also { it.addActionListener { removeSelected() } })
                add(JButton("Import Signed Bundle…").also {
                    it.toolTipText = "Verify and cache a bundle for deterministic offline use"
                    it.addActionListener { importSelected() }
                })
                add(JButton("Refresh Selected").also {
                    it.toolTipText = "Download the exact SHA-256-pinned HTTPS bundle and verify its signature"
                    it.addActionListener { refreshSelected() }
                })
                add(JButton("Create Signed Bundle…").also {
                    it.toolTipText =
                        "Compare a customized project to a certified base, review every change, then sign and export"
                    it.addActionListener { createSignedBundle() }
                })
            }
            component = JPanel(BorderLayout(0, 10)).apply {
                add(
                    JPanel(BorderLayout()).apply {
                        add(
                            JBLabel(
                                "<html>Only Ed25519-signed, SHA-256-pinned declarative bundles are accepted. " +
                                    "Public keys are trust anchors, not secrets.</html>",
                            ),
                            BorderLayout.NORTH,
                        )
                        add(offlineMode, BorderLayout.SOUTH)
                    },
                    BorderLayout.NORTH,
                )
                add(JBScrollPane(table), BorderLayout.CENTER)
                add(buttons, BorderLayout.SOUTH)
            }
        }
        return requireNotNull(component)
    }

    override fun isModified(): Boolean {
        val saved = JmixTemplateCatalogSettings.getInstance().state
        return offlineMode.isSelected != saved.offlineMode ||
            model.rows != saved.catalogs.map(CatalogDraft::from)
    }

    override fun apply() {
        val duplicates = model.rows
            .groupBy { it.catalogId.trim() to it.catalogVersion.trim() }
            .filterValues { it.size > 1 }
            .keys
        if (duplicates.isNotEmpty()) {
            throw ConfigurationException(
                "Catalog ID and exact version must be unique: " +
                    duplicates.joinToString { "${it.first}:${it.second}" },
            )
        }
        JmixTemplateCatalogSettings.getInstance().replace(
            offlineMode = offlineMode.isSelected,
            catalogs = model.rows.map(CatalogDraft::toState),
        )
    }

    override fun reset() {
        val saved = JmixTemplateCatalogSettings.getInstance().state
        offlineMode.isSelected = saved.offlineMode
        model.replace(saved.catalogs.map(CatalogDraft::from))
    }

    override fun disposeUIResources() {
        component = null
        model.replace(emptyList())
    }

    private fun addCatalog() {
        CatalogEditorDialog(null).showAndGetDraft()?.let(model::add)
    }

    private fun editSelected() {
        val row = selectedRow() ?: return
        CatalogEditorDialog(model.rows[row]).showAndGetDraft()?.let { model.replace(row, it) }
    }

    private fun removeSelected() {
        val row = selectedRow() ?: return
        val draft = model.rows[row]
        if (
            Messages.showYesNoDialog(
                "Remove '${draft.displayName.ifBlank { draft.catalogId }}' from configuration? " +
                    "Its immutable offline cache is retained for recovery.",
                "Remove Organization Template Catalog",
                Messages.getQuestionIcon(),
            ) == Messages.YES
        ) {
            model.remove(row)
        }
    }

    private fun importSelected() {
        val draft = selectedDraft() ?: return
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Import Signed Jmix Template Catalog")
            .withFileFilter { it.extension == "jmix-template-catalog" || it.extension == "zip" }
        val selected = FileChooser.chooseFile(descriptor, null, null) ?: return
        if (!selected.isInLocalFileSystem) {
            Messages.showErrorDialog(
                "Choose a bundle from the local filesystem.",
                "Cannot Import Organization Template Catalog",
            )
            return
        }
        val path = selected.toNioPath()
        runCatalogOperation("Importing signed organization template catalog") {
            JmixTemplateCatalogManager.getInstance().importBundle(draft.toState(), path)
        }
    }

    private fun refreshSelected() {
        val draft = selectedDraft() ?: return
        if (offlineMode.isSelected) {
            Messages.showWarningDialog(
                "Disable offline mode before refreshing. Cached catalogs remain available offline.",
                "Organization Templates Are Offline",
            )
            return
        }
        runCatalogOperation("Refreshing organization template catalog") {
            JmixTemplateCatalogManager.getInstance().refresh(
                configured = draft.toState(),
                offlineMode = false,
            )
        }
    }

    private fun createSignedBundle() {
        val dialog = runCatching { JmixTemplateCatalogAuthoringDialog() }.getOrElse { failure ->
            Messages.showErrorDialog(
                failure.message ?: "Installed enterprise signing providers could not be loaded safely.",
                "Cannot Author Project Template",
            )
            return
        }
        val input = dialog.showAndGetInput() ?: return
        val planResult = runProgress("Comparing customized project with certified Jmix base") {
            JmixTemplateOverlayPlanner.plan(
                customizedProjectRoot = input.sourceRoot,
                request = input.baseRequest,
                progress = JmixTemplateOverlayProgress {
                    ProgressManager.checkCanceled()
                },
            )
        } ?: return
        val plan = planResult.getOrElse { failure ->
            Messages.showErrorDialog(
                failure.message ?: "The customized project could not be compared safely.",
                "Cannot Author Project Template",
            )
            return
        }
        if (!JmixTemplateOverlayPreviewDialog(plan).showAndGet()) return
        val exportResult = runProgress("Signing and self-verifying Jmix template catalog") {
            val currentPlan = JmixTemplateOverlayPlanner.plan(
                customizedProjectRoot = input.sourceRoot,
                request = input.baseRequest,
                progress = JmixTemplateOverlayProgress {
                    ProgressManager.checkCanceled()
                },
            )
            require(
                plan.matchesReviewedSource(currentPlan)
            ) {
                "The customized project changed after review. Review a fresh diff before signing."
            }
            val signer = dialog.resolveSigner(input)
            val bundle = JmixTemplateCatalogAuthoring.createSignedBundle(
                draft = input.draft(currentPlan),
                signer = signer,
            )
            JmixTemplateCatalogAuthoring.writeCreateOnly(input.output, bundle)
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bundle),
            )
        } ?: return
        exportResult.fold(
            onSuccess = { digest ->
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(input.output)
                Messages.showInfoMessage(
                    "Signed, self-verified catalog created without storing the private key.\n\n" +
                        "${input.output}\nSHA-256: $digest",
                    "Jmix Project Template Catalog Created",
                )
            },
            onFailure = { failure ->
                Messages.showErrorDialog(
                    failure.message ?: "Catalog signing or export failed.",
                    "Cannot Export Signed Project Template",
                )
            },
        )
    }

    private fun <T> runProgress(
        title: String,
        operation: () -> T,
    ): Result<T>? {
        var result: Result<T>? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                result = try {
                    Result.success(operation())
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            },
            title,
            true,
            null,
        )
        return result
    }

    private fun runCatalogOperation(
        title: String,
        operation: () -> JmixCachedTemplateCatalog,
    ) {
        val result = runProgress(title, operation)
        result?.fold(
            onSuccess = { cached ->
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(cached.bundlePath)
                Messages.showInfoMessage(
                    "Verified and cached ${cached.catalogId}:${cached.catalogVersion}\n" +
                        "SHA-256: ${cached.bundleSha256}",
                    "Organization Template Catalog Ready",
                )
            },
            onFailure = { failure ->
                Messages.showErrorDialog(
                    failure.message ?: "Catalog verification failed.",
                    "Cannot Use Organization Template Catalog",
                )
            },
        )
    }

    private fun selectedRow(): Int? =
        table.selectedRow.takeIf { it in model.rows.indices }

    private fun selectedDraft(): CatalogDraft? =
        selectedRow()?.let(model.rows::get)

    private data class CatalogDraft(
        val enabled: Boolean = true,
        val displayName: String = "",
        val catalogId: String = "",
        val catalogVersion: String = "",
        val sourceUrl: String = "",
        val expectedBundleSha256: String = "",
        val signingKeyId: String = "",
        val signingPublicKey: String = "",
        val minimumCatalogVersion: String = "",
    ) {
        fun toState(): JmixTemplateCatalogSettings.CatalogState =
            JmixTemplateCatalogSettings.CatalogState().also {
                it.enabled = enabled
                it.displayName = displayName.trim()
                it.catalogId = catalogId.trim()
                it.catalogVersion = catalogVersion.trim()
                it.sourceUrl = sourceUrl.trim()
                it.expectedBundleSha256 = expectedBundleSha256.trim()
                it.signingKeyId = signingKeyId.trim()
                it.signingPublicKey = signingPublicKey.trim()
                it.minimumCatalogVersion = minimumCatalogVersion.trim()
            }

        companion object {
            fun from(state: JmixTemplateCatalogSettings.CatalogState): CatalogDraft =
                CatalogDraft(
                    enabled = state.enabled,
                    displayName = state.displayName,
                    catalogId = state.catalogId,
                    catalogVersion = state.catalogVersion,
                    sourceUrl = state.sourceUrl,
                    expectedBundleSha256 = state.expectedBundleSha256,
                    signingKeyId = state.signingKeyId,
                    signingPublicKey = state.signingPublicKey,
                    minimumCatalogVersion = state.minimumCatalogVersion,
                )
        }
    }

    private class CatalogTableModel : AbstractTableModel() {
        private val values = mutableListOf<CatalogDraft>()
        val rows: List<CatalogDraft> get() = values

        override fun getRowCount(): Int = values.size
        override fun getColumnCount(): Int = 6
        override fun getColumnName(column: Int): String = when (column) {
            0 -> "Enabled"
            1 -> "Name"
            2 -> "Catalog"
            3 -> "Version"
            4 -> "Minimum"
            else -> "HTTPS source"
        }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java

        override fun isCellEditable(
            rowIndex: Int,
            columnIndex: Int,
        ): Boolean = columnIndex == 0

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val row = values[rowIndex]
            return when (columnIndex) {
                0 -> row.enabled
                1 -> row.displayName
                2 -> row.catalogId
                3 -> row.catalogVersion
                4 -> row.minimumCatalogVersion.ifBlank { row.catalogVersion }
                else -> row.sourceUrl
            }
        }

        override fun setValueAt(
            value: Any?,
            rowIndex: Int,
            columnIndex: Int,
        ) {
            if (columnIndex == 0) {
                values[rowIndex] = values[rowIndex].copy(enabled = value == true)
                fireTableRowsUpdated(rowIndex, rowIndex)
            }
        }

        fun replace(rows: List<CatalogDraft>) {
            values.clear()
            values.addAll(rows)
            fireTableDataChanged()
        }

        fun replace(
            row: Int,
            value: CatalogDraft,
        ) {
            values[row] = value
            fireTableRowsUpdated(row, row)
        }

        fun add(value: CatalogDraft) {
            val index = values.size
            values += value
            fireTableRowsInserted(index, index)
        }

        fun remove(row: Int) {
            values.removeAt(row)
            fireTableRowsDeleted(row, row)
        }
    }

    private class CatalogEditorDialog(
        initial: CatalogDraft?,
    ) : DialogWrapper(false) {
        private val enabled = JBCheckBox("Enabled", initial?.enabled ?: true)
        private val displayName = JBTextField(initial?.displayName.orEmpty())
        private val catalogId = JBTextField(initial?.catalogId.orEmpty())
        private val catalogVersion = JBTextField(initial?.catalogVersion.orEmpty())
        private val minimumVersion = JBTextField(initial?.minimumCatalogVersion.orEmpty())
        private val sourceUrl = JBTextField(initial?.sourceUrl.orEmpty())
        private val bundleSha256 = JBTextField(initial?.expectedBundleSha256.orEmpty())
        private val signingKeyId = JBTextField(initial?.signingKeyId.orEmpty())
        private val signingPublicKey = JBTextArea(initial?.signingPublicKey.orEmpty(), 4, 64).apply {
            lineWrap = true
            wrapStyleWord = false
        }

        init {
            title = if (initial == null) {
                "Add Organization Template Catalog"
            } else {
                "Edit Organization Template Catalog"
            }
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row {
                cell(enabled)
            }
            row("Name:") {
                cell(displayName).align(Align.FILL)
            }
            row("Catalog ID:") {
                cell(catalogId).align(Align.FILL)
                comment("Lowercase DNS-style identifier from the signed manifest.")
            }
            row("Exact version:") {
                cell(catalogVersion).align(Align.FILL)
            }
            row("Minimum accepted version:") {
                cell(minimumVersion).align(Align.FILL)
                comment("Optional anti-rollback floor; exact version is used when blank.")
            }
            row("HTTPS bundle URL (optional):") {
                cell(sourceUrl).align(Align.FILL)
                comment("Leave blank for import-only/offline catalogs. Redirects and credentials are rejected.")
            }
            row("Bundle SHA-256:") {
                cell(bundleSha256).align(Align.FILL)
            }
            row("Signing key ID:") {
                cell(signingKeyId).align(Align.FILL)
            }
            row("Ed25519 public key (X.509 Base64):") {
                scrollCell(signingPublicKey).align(Align.FILL)
                comment("The key verifies catalog.json; it is safe to distribute but must be authenticated.")
            }
        }

        override fun doValidate(): ValidationInfo? {
            fun problem(message: String, component: JComponent): ValidationInfo =
                ValidationInfo(message, component)
            if (displayName.text.trim().isEmpty()) return problem("Name is required.", displayName)
            if (!catalogId.text.trim().matches(Regex("[a-z][a-z0-9.-]{0,95}"))) {
                return problem("Use a lowercase DNS-style catalog ID.", catalogId)
            }
            val versionRegex = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?")
            if (!catalogVersion.text.trim().matches(versionRegex)) {
                return problem("Use a numeric dotted catalog version.", catalogVersion)
            }
            if (
                minimumVersion.text.isNotBlank() &&
                !minimumVersion.text.trim().matches(versionRegex)
            ) {
                return problem("Use a numeric dotted minimum version.", minimumVersion)
            }
            if (sourceUrl.text.isNotBlank()) {
                val uri = runCatching { URI(sourceUrl.text.trim()) }.getOrNull()
                if (
                    uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
                    uri.userInfo != null || uri.query != null || uri.fragment != null
                ) {
                    return problem("Use an HTTPS URL without credentials, query, or fragment.", sourceUrl)
                }
            }
            if (!bundleSha256.text.trim().matches(Regex("[0-9a-f]{64}"))) {
                return problem("Enter the exact lowercase SHA-256 bundle digest.", bundleSha256)
            }
            if (!signingKeyId.text.trim().matches(Regex("[a-z][a-z0-9.-]{0,95}"))) {
                return problem("Use a lowercase DNS-style signing key ID.", signingKeyId)
            }
            val keyValid = runCatching {
                val keyBytes =
                Base64.getDecoder().decode(signingPublicKey.text.trim())
                java.security.KeyFactory.getInstance("Ed25519").generatePublic(
                    java.security.spec.X509EncodedKeySpec(keyBytes),
                )
            }.isSuccess
            if (!keyValid) {
                return problem("Enter a valid Base64-encoded Ed25519 X.509 public key.", signingPublicKey)
            }
            return null
        }

        fun showAndGetDraft(): CatalogDraft? {
            if (!showAndGet()) return null
            return CatalogDraft(
                enabled = enabled.isSelected,
                displayName = displayName.text.trim(),
                catalogId = catalogId.text.trim(),
                catalogVersion = catalogVersion.text.trim(),
                sourceUrl = sourceUrl.text.trim(),
                expectedBundleSha256 = bundleSha256.text.trim(),
                signingKeyId = signingKeyId.text.trim(),
                signingPublicKey = signingPublicKey.text.trim(),
                minimumCatalogVersion = minimumVersion.text.trim(),
            )
        }
    }
}
