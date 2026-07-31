package org.jmixworkbench.project

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import org.jmixworkbench.model.IntegrationAuthenticationKind
import org.jmixworkbench.model.IntegrationCapability
import org.jmixworkbench.model.IntegrationConnectorKind
import org.jmixworkbench.model.IntegrationObservabilityApi
import org.jmixworkbench.model.IntegrationSpringBootApi
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal data class JmixConnectorCatalogAuthoringInput(
    val output: Path,
    val catalogId: String,
    val catalogVersion: String,
    val catalogDisplayName: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val connectorTemplate: JmixOrganizationConnectorTemplate,
    val signingChoice: JmixTemplateSigningChoice,
) {
    fun draft(): JmixTemplateCatalogDraft =
        JmixTemplateCatalogDraft(
            catalogId = catalogId,
            catalogVersion = catalogVersion,
            displayName = catalogDisplayName,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            templates = emptyList(),
            connectorTemplates = listOf(connectorTemplate),
        )
}

/**
 * Native, keyboard-accessible authoring surface for one declarative connector
 * template. The resulting bundle contains policy metadata only: no executable
 * generator code, endpoint, credential, certificate, or private key.
 */
internal class JmixConnectorCatalogAuthoringDialog(
    private val clock: Clock = Clock.systemUTC(),
) : DialogWrapper(false) {
    private val catalogId = JBTextField("company.connectors")
    private val catalogVersion = JBTextField("1.0.0")
    private val catalogDisplayName = JBTextField("Company integration connectors")
    private val lifetimeDays = JBTextField("365")

    private val templateId = JBTextField("company-http")
    private val templateVersion = JBTextField("1.0.0")
    private val templateName = JBTextField("Company governed HTTP connector")
    private val templateDescription = JBTextArea(2, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val templateOrder = JBTextField("10")
    private val provider = JBTextField("Company integration platform")
    private val kind = JComboBox(IntegrationConnectorKind.entries.toTypedArray())
    private val boot3 = JBCheckBox("Spring Boot 3 / Jmix 2", true)
    private val boot4 = JBCheckBox("Spring Boot 4 / Jmix 3", true)
    private val capabilities = IntegrationCapability.entries.associateWith { capability ->
        JBCheckBox(capability.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase))
    }
    private val configurationPrefixSuffix = JBTextField("company.http")
    private val addressPropertySuffix = JBTextField("base-url")
    private val headers = JBTextArea(3, 60).apply {
        lineWrap = false
        text = "X-Correlation-ID=correlation-id"
    }

    private val risk = JComboBox(JmixOrganizationConnectorRisk.entries.toTypedArray()).apply {
        selectedItem = JmixOrganizationConnectorRisk.SENSITIVE
    }
    private val approvalPolicyId = JBTextField("company.integration.sensitive")
    private val authenticationOptions =
        listOf(AuthenticationRequirement(null)) +
            IntegrationAuthenticationKind.entries.map(::AuthenticationRequirement)
    private val requiredAuthentication = JComboBox(
        DefaultComboBoxModel(authenticationOptions.toTypedArray()),
    )
    private val requireMutualTls = JBCheckBox("Require mutual TLS")
    private val requireTransactional = JBCheckBox("Require transactional boundary")
    private val requireIdempotency = JBCheckBox("Require idempotency", true)
    private val requireOutbox = JBCheckBox("Require transactional outbox")
    private val requireInbox = JBCheckBox("Require persistent inbox")
    private val maximumConnectTimeoutMs = JBTextField("5000")
    private val maximumRequestTimeoutMs = JBTextField("15000")
    private val minimumRetryAttempts = JBTextField("3")
    private val requireMetrics = JBCheckBox("Require metrics", true)
    private val requireTracing = JBCheckBox("Require tracing", true)
    private val requireStructuredLogging = JBCheckBox("Require structured logging", true)
    private val requireAudit = JBCheckBox("Require audit events", true)
    private val observabilityOptions =
        listOf(ObservabilityRequirement(null)) +
            IntegrationObservabilityApi.entries.map(::ObservabilityRequirement)
    private val requiredObservabilityApi = JComboBox(
        DefaultComboBoxModel(observabilityOptions.toTypedArray()),
    ).apply {
        selectedItem = observabilityOptions.single {
            it.api == IntegrationObservabilityApi.MICROMETER_OBSERVATION
        }
    }

    private val signingChoices = listOf<JmixTemplateSigningChoice>(
        JmixTemplateSigningChoice.TransientFiles,
    ) + JmixTemplateCatalogSigningProvider.available().map(JmixTemplateSigningChoice::Enterprise)
    private val signingChoice = JComboBox(DefaultComboBoxModel(signingChoices.toTypedArray()))
    private val signingKeyId = JBTextField("company-release")
    private val privateKeyPath = JBTextField()
    private val publicKeyPath = JBTextField()
    private val outputDirectory = JBTextField()
    private val outputFileName = JBTextField("company-connectors-1.0.0.jmix-connector-catalog")

    init {
        title = "Create Signed Jmix Connector Catalog"
        setOKButtonText("Review Policy")
        isResizable = true
        kind.addActionListener { selectRequiredCapabilities() }
        risk.addActionListener {
            val approvalRequired = risk.selectedItem != JmixOrganizationConnectorRisk.STANDARD
            approvalPolicyId.isEnabled = approvalRequired
            if (approvalRequired && approvalPolicyId.text.isBlank()) {
                approvalPolicyId.text = "company.integration.sensitive"
            }
        }
        signingChoice.addActionListener { refreshSigningFields() }
        catalogVersion.document.addDocumentListener(
            object : DocumentListener {
                private fun updateSuggestedFilename() {
                    val prefix = "company-connectors-"
                    if (outputFileName.text.startsWith(prefix)) {
                        outputFileName.text =
                            "$prefix${catalogVersion.text.trim()}.jmix-connector-catalog"
                    }
                }

                override fun insertUpdate(event: DocumentEvent) = updateSuggestedFilename()

                override fun removeUpdate(event: DocumentEvent) = updateSuggestedFilename()

                override fun changedUpdate(event: DocumentEvent) = updateSuggestedFilename()
            },
        )
        selectRequiredCapabilities()
        init()
        refreshSigningFields()
    }

    public override fun createCenterPanel(): JComponent {
        val content = panel {
            group("Catalog identity and lifecycle") {
                row("Catalog ID / version:") {
                    cell(catalogId).align(Align.FILL)
                    cell(catalogVersion).align(Align.FILL)
                }
                row("Catalog name / lifetime days:") {
                    cell(catalogDisplayName).align(Align.FILL)
                    cell(lifetimeDays)
                }
            }
            group("Connector identity and compatibility") {
                row("Template ID / version:") {
                    cell(templateId).align(Align.FILL)
                    cell(templateVersion).align(Align.FILL)
                }
                row("Name / order:") {
                    cell(templateName).align(Align.FILL)
                    cell(templateOrder)
                }
                row("Provider / kind:") {
                    cell(provider).align(Align.FILL)
                    cell(kind)
                }
                row("Description:") {
                    scrollCell(templateDescription).align(Align.FILL)
                }
                row("Supported runtime:") {
                    cell(boot3)
                    cell(boot4)
                }
                row {
                    comment(
                        "Compatibility is checked against the selected module's indexed Spring Boot API " +
                            "and dependencies before preview or generation.",
                    )
                }
                IntegrationCapability.entries.chunked(2).forEach { rowCapabilities ->
                    row(if (rowCapabilities.first() == IntegrationCapability.entries.first()) {
                        "Required capabilities:"
                    } else {
                        ""
                    }) {
                        rowCapabilities.forEach { capability ->
                            cell(requireNotNull(capabilities[capability]))
                        }
                    }
                }
                row("Configuration / address suffix:") {
                    cell(configurationPrefixSuffix).align(Align.FILL)
                    cell(addressPropertySuffix).align(Align.FILL)
                }
                row("Required headers:") {
                    scrollCell(headers).align(Align.FILL)
                    comment(
                        "One per line: Header-Name=property-suffix. Append '; sensitive' for secret-bearing values.",
                    )
                }
            }
            group("Enforced policy") {
                row("Risk / approval policy:") {
                    cell(risk)
                    cell(approvalPolicyId).align(Align.FILL)
                }
                row("Required authentication:") {
                    cell(requiredAuthentication).align(Align.FILL)
                    cell(requireMutualTls)
                }
                row("Required delivery controls:") {
                    cell(requireTransactional)
                    cell(requireIdempotency)
                }
                row {
                    cell(requireOutbox)
                    cell(requireInbox)
                }
                row("Maximum connect/request ms:") {
                    cell(maximumConnectTimeoutMs)
                    cell(maximumRequestTimeoutMs)
                }
                row("Minimum bounded attempts:") {
                    cell(minimumRetryAttempts)
                }
                row("Required telemetry:") {
                    cell(requireMetrics)
                    cell(requireTracing)
                }
                row {
                    cell(requireStructuredLogging)
                    cell(requireAudit)
                }
                row("Required observability API:") {
                    cell(requiredObservabilityApi).align(Align.FILL)
                }
            }
            group("Signing and create-only export") {
                row {
                    comment(
                        "The signed bundle is declarative and self-verified. Endpoints, secrets, certificates " +
                            "and executable generator code cannot be included.",
                    )
                }
                row("Signing method:") {
                    cell(signingChoice).align(Align.FILL)
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
            preferredSize = Dimension(960, 720)
            border = null
            verticalScrollBar.unitIncrement = 16
            accessibleContext.accessibleName = "Signed Jmix connector catalog authoring form"
        }
    }

    override fun doValidate(): ValidationInfo? {
        fun problem(message: String, component: JComponent): ValidationInfo =
            ValidationInfo(message, component)
        val identifier = Regex("[a-z][a-z0-9.-]{0,95}")
        val version = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?")
        val propertySuffix = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
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
        if (!identifier.matches(templateId.text.trim())) {
            return problem("Use a lowercase DNS-style connector template ID.", templateId)
        }
        if (!version.matches(templateVersion.text.trim())) {
            return problem("Use a numeric dotted connector template version.", templateVersion)
        }
        if (templateName.text.trim().isEmpty() || templateName.text.trim().length > 120) {
            return problem("Connector name must be 1-120 characters.", templateName)
        }
        if (templateDescription.text.length > 1_000) {
            return problem("Description cannot exceed 1,000 characters.", templateDescription)
        }
        if (provider.text.trim().isEmpty() || provider.text.trim().length > 120) {
            return problem("Provider must be 1-120 characters.", provider)
        }
        if (templateOrder.text.trim().toIntOrNull() !in -100_000..100_000) {
            return problem("Order must be between -100000 and 100000.", templateOrder)
        }
        if (!boot3.isSelected && !boot4.isSelected) {
            return problem("Select at least one supported Spring Boot runtime.", boot3)
        }
        if (!propertySuffix.matches(configurationPrefixSuffix.text.trim())) {
            return problem("Use a safe lowercase configuration property suffix.", configurationPrefixSuffix)
        }
        if (!propertySuffix.matches(addressPropertySuffix.text.trim())) {
            return problem("Use a safe lowercase address property suffix.", addressPropertySuffix)
        }
        runCatching { parsedHeaders() }.exceptionOrNull()?.let { failure ->
            return problem(failure.message ?: "Required headers are invalid.", headers)
        }
        val selectedRisk = requireNotNull(risk.selectedItem as? JmixOrganizationConnectorRisk)
        if (
            selectedRisk != JmixOrganizationConnectorRisk.STANDARD &&
            !approvalPolicyId.text.trim().matches(Regex("[a-z][a-z0-9.-]{2,127}"))
        ) {
            return problem("Sensitive and restricted templates require a safe approval policy ID.", approvalPolicyId)
        }
        val connectTimeout = maximumConnectTimeoutMs.text.trim().toLongOrNull()
        if (connectTimeout == null || connectTimeout !in 100..120_000) {
            return problem("Connect timeout must be 100-120000 ms.", maximumConnectTimeoutMs)
        }
        val requestTimeout = maximumRequestTimeoutMs.text.trim().toLongOrNull()
        if (requestTimeout == null || requestTimeout !in 100..600_000) {
            return problem("Request timeout must be 100-600000 ms.", maximumRequestTimeoutMs)
        }
        val attempts = minimumRetryAttempts.text.trim().toIntOrNull()
        if (attempts == null || attempts !in 1..20) {
            return problem("Minimum attempts must be 1-20.", minimumRetryAttempts)
        }
        val selectedKind = requireNotNull(kind.selectedItem as? IntegrationConnectorKind)
        val auth = (requiredAuthentication.selectedItem as AuthenticationRequirement).kind
        if (
            auth == IntegrationAuthenticationKind.SSH_KEY &&
            selectedKind !in setOf(
                IntegrationConnectorKind.SFTP_UPLOAD,
                IntegrationConnectorKind.SFTP_DOWNLOAD,
            )
        ) {
            return problem("SSH-key authentication is available only for SFTP connectors.", requiredAuthentication)
        }
        if (
            requireOutbox.isSelected &&
            selectedKind !in setOf(
                IntegrationConnectorKind.KAFKA_PUBLISHER,
                IntegrationConnectorKind.RABBIT_PUBLISHER,
            )
        ) {
            return problem("Transactional outbox is available only for broker publishers.", requireOutbox)
        }
        if (
            requireInbox.isSelected &&
            selectedKind !in setOf(
                IntegrationConnectorKind.KAFKA_CONSUMER,
                IntegrationConnectorKind.RABBIT_CONSUMER,
            )
        ) {
            return problem("Persistent inbox is available only for broker consumers.", requireInbox)
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
        if (!output.fileName.toString().matches(Regex("[A-Za-z0-9._-]+\\.jmix-connector-catalog"))) {
            return problem("Use a portable .jmix-connector-catalog filename.", outputFileName)
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            return problem("The output already exists; choose a new filename.", outputFileName)
        }
        return null
    }

    fun showAndGetInput(): JmixConnectorCatalogAuthoringInput? {
        if (!showAndGet()) return null
        val issued = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val days = lifetimeDays.text.trim().toLong()
        return JmixConnectorCatalogAuthoringInput(
            output = requireNotNull(outputPath()),
            catalogId = catalogId.text.trim(),
            catalogVersion = catalogVersion.text.trim(),
            catalogDisplayName = catalogDisplayName.text.trim(),
            issuedAt = issued,
            expiresAt = if (days == 0L) null else issued.plus(Duration.ofDays(days)),
            connectorTemplate = connectorTemplate(),
            signingChoice = requireNotNull(signingChoice.selectedItem as? JmixTemplateSigningChoice),
        )
    }

    fun resolveSigner(input: JmixConnectorCatalogAuthoringInput): JmixTemplateCatalogSigningIdentity =
        when (val selected = input.signingChoice) {
            JmixTemplateSigningChoice.TransientFiles ->
                JmixTemplateCatalogSigner.fromFiles(
                    keyId = signingKeyId.text.trim(),
                    privateKeyPkcs8 = requireNotNull(path(privateKeyPath.text)),
                    publicKeyX509 = requireNotNull(path(publicKeyPath.text)),
                )

            is JmixTemplateSigningChoice.Enterprise -> selected.provider
        }

    private fun connectorTemplate(): JmixOrganizationConnectorTemplate {
        val selectedRisk = requireNotNull(risk.selectedItem as? JmixOrganizationConnectorRisk)
        return JmixOrganizationConnectorTemplate(
            id = templateId.text.trim(),
            version = templateVersion.text.trim(),
            name = templateName.text.trim(),
            description = templateDescription.text.trim(),
            order = templateOrder.text.trim().toInt(),
            provider = provider.text.trim(),
            kind = requireNotNull(kind.selectedItem as? IntegrationConnectorKind),
            springBootApis = buildSet {
                if (boot3.isSelected) add(IntegrationSpringBootApi.BOOT_3)
                if (boot4.isSelected) add(IntegrationSpringBootApi.BOOT_4)
            },
            requiredCapabilities = capabilities
                .filterValues(JBCheckBox::isSelected)
                .keys,
            configurationPrefixSuffix = configurationPrefixSuffix.text.trim(),
            addressPropertySuffix = addressPropertySuffix.text.trim(),
            headers = parsedHeaders(),
            policy = JmixOrganizationConnectorPolicy(
                risk = selectedRisk,
                approvalPolicyId = approvalPolicyId.text.trim()
                    .takeIf { selectedRisk != JmixOrganizationConnectorRisk.STANDARD },
                requiredAuthentication =
                    (requiredAuthentication.selectedItem as AuthenticationRequirement).kind,
                requireMutualTls = requireMutualTls.isSelected,
                requireTransactional = requireTransactional.isSelected,
                requireIdempotency = requireIdempotency.isSelected,
                requireOutbox = requireOutbox.isSelected,
                requireInbox = requireInbox.isSelected,
                maximumConnectTimeoutMs = maximumConnectTimeoutMs.text.trim().toLong(),
                maximumRequestTimeoutMs = maximumRequestTimeoutMs.text.trim().toLong(),
                minimumRetryAttempts = minimumRetryAttempts.text.trim().toInt(),
                requireMetrics = requireMetrics.isSelected,
                requireTracing = requireTracing.isSelected,
                requireStructuredLogging = requireStructuredLogging.isSelected,
                requireAudit = requireAudit.isSelected,
                requiredObservabilityApi =
                    (requiredObservabilityApi.selectedItem as ObservabilityRequirement).api,
            ),
        )
    }

    private fun parsedHeaders(): List<JmixOrganizationConnectorHeader> {
        val headerPattern = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}""")
        val suffixPattern = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
        val parsed = headers.text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapIndexed { index, line ->
                val sections = line.split(';').map(String::trim)
                require(sections.size in 1..2) {
                    "Header line ${index + 1} must use Header-Name=property-suffix[; sensitive]."
                }
                val assignment = sections[0].split('=', limit = 2).map(String::trim)
                require(assignment.size == 2 && headerPattern.matches(assignment[0])) {
                    "Header line ${index + 1} has an invalid HTTP header name."
                }
                require(suffixPattern.matches(assignment[1])) {
                    "Header line ${index + 1} has an unsafe property suffix."
                }
                val sensitive = when (sections.getOrNull(1)?.lowercase()) {
                    null -> false
                    "sensitive" -> true
                    else -> error("Header line ${index + 1} supports only the '; sensitive' flag.")
                }
                JmixOrganizationConnectorHeader(
                    name = assignment[0],
                    propertySuffix = assignment[1],
                    sensitive = sensitive,
                )
            }
            .toList()
        require(parsed.size <= 64) { "A connector can require at most 64 headers." }
        require(parsed.map { it.name.lowercase() }.distinct().size == parsed.size) {
            "Header names must be unique ignoring case."
        }
        return parsed
    }

    private fun selectRequiredCapabilities() {
        val selected = kind.selectedItem as? IntegrationConnectorKind ?: return
        val required = when (selected) {
            IntegrationConnectorKind.HTTP_CLIENT,
            IntegrationConnectorKind.WEBHOOK,
            IntegrationConnectorKind.OBJECT_STORAGE,
            IntegrationConnectorKind.SMS_GATEWAY,
            IntegrationConnectorKind.PAYMENT_GATEWAY,
            -> setOf(IntegrationCapability.SPRING_WEB, IntegrationCapability.RESILIENCE4J)

            IntegrationConnectorKind.IDENTITY_PROVIDER -> setOf(
                IntegrationCapability.SPRING_WEB,
                IntegrationCapability.OAUTH2_CLIENT,
                IntegrationCapability.SPRING_BOOT_SSL_BUNDLES,
                IntegrationCapability.RESILIENCE4J,
            )

            IntegrationConnectorKind.KAFKA_PUBLISHER,
            IntegrationConnectorKind.KAFKA_CONSUMER,
            -> setOf(IntegrationCapability.SPRING_KAFKA)

            IntegrationConnectorKind.RABBIT_PUBLISHER,
            IntegrationConnectorKind.RABBIT_CONSUMER,
            -> setOf(IntegrationCapability.SPRING_AMQP)

            IntegrationConnectorKind.SFTP_UPLOAD,
            IntegrationConnectorKind.SFTP_DOWNLOAD,
            -> setOf(IntegrationCapability.SPRING_INTEGRATION_SFTP)

            IntegrationConnectorKind.JMIX_EMAIL -> setOf(IntegrationCapability.JMIX_EMAIL)
            IntegrationConnectorKind.JMIX_FILE_STORAGE ->
                setOf(IntegrationCapability.JMIX_FILE_STORAGE)
        }
        required.forEach { capability ->
            requireNotNull(capabilities[capability]).isSelected = true
        }
    }

    private fun refreshSigningFields() {
        val local = signingChoice.selectedItem == JmixTemplateSigningChoice.TransientFiles
        signingKeyId.isEnabled = local
        privateKeyPath.isEnabled = local
        publicKeyPath.isEnabled = local
        if (!local) {
            val signingProvider =
                (signingChoice.selectedItem as? JmixTemplateSigningChoice.Enterprise)?.provider
            signingKeyId.text = signingProvider?.keyId.orEmpty()
        } else if (signingKeyId.text.isBlank()) {
            signingKeyId.text = "company-release"
        }
    }

    private fun chooseDirectory(
        target: JBTextField,
        title: String,
    ) {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle(title)
        FileChooser.chooseFile(descriptor, null, null)
            ?.takeIf { it.isInLocalFileSystem }
            ?.let { target.text = it.toNioPath().toString() }
    }

    private fun chooseFile(
        target: JBTextField,
        title: String,
    ) {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle(title)
        FileChooser.chooseFile(descriptor, null, null)
            ?.takeIf { it.isInLocalFileSystem }
            ?.let { target.text = it.toNioPath().toString() }
    }

    private fun outputPath(): Path? {
        val directory = path(outputDirectory.text) ?: return null
        val name = outputFileName.text.trim()
        return name.takeIf(String::isNotEmpty)
            ?.let(directory::resolve)
            ?.toAbsolutePath()
            ?.normalize()
    }

    private fun realFile(value: String): Boolean {
        val candidate = path(value) ?: return false
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(candidate)
    }

    private fun path(value: String): Path? =
        value.trim().takeIf(String::isNotEmpty)?.let { text ->
            runCatching { Path.of(text).toAbsolutePath().normalize() }.getOrNull()
        }

    private data class AuthenticationRequirement(
        val kind: IntegrationAuthenticationKind?,
    ) {
        override fun toString(): String =
            kind?.name?.replace('_', ' ')?.lowercase()?.replaceFirstChar(Char::uppercase)
                ?: "No catalog requirement"
    }

    private data class ObservabilityRequirement(
        val api: IntegrationObservabilityApi?,
    ) {
        override fun toString(): String =
            api?.name?.replace('_', ' ')?.lowercase()?.replaceFirstChar(Char::uppercase)
                ?: "Any compatible API"
    }
}

internal class JmixConnectorCatalogReviewDialog(
    private val input: JmixConnectorCatalogAuthoringInput,
) : DialogWrapper(false) {
    init {
        title = "Review Signed Connector Policy"
        setOKButtonText("Sign and Export")
        isResizable = true
        init()
    }

    public override fun createCenterPanel(): JComponent {
        val template = input.connectorTemplate
        val policy = template.policy
        val review = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            text = buildString {
                appendLine("Catalog: ${input.catalogId}:${input.catalogVersion}")
                appendLine("Template: ${template.id}:${template.version} — ${template.name}")
                appendLine("Provider / kind: ${template.provider} / ${template.kind}")
                appendLine("Spring Boot APIs: ${template.springBootApis.sorted().joinToString()}")
                appendLine("Required capabilities: ${template.requiredCapabilities.sorted().joinToString()}")
                appendLine("Configuration suffix: ${template.configurationPrefixSuffix}")
                appendLine("Address suffix: ${template.addressPropertySuffix}")
                appendLine(
                    "Headers: " +
                        template.headers.joinToString().ifBlank { "none" },
                )
                appendLine()
                appendLine("Risk: ${policy.risk}")
                appendLine("Native approval policy: ${policy.approvalPolicyId ?: "not required"}")
                appendLine("Authentication: ${policy.requiredAuthentication ?: "not constrained"}")
                appendLine("Mutual TLS: ${policy.requireMutualTls}")
                appendLine("Transactional / idempotent: ${policy.requireTransactional} / ${policy.requireIdempotency}")
                appendLine("Outbox / inbox: ${policy.requireOutbox} / ${policy.requireInbox}")
                appendLine(
                    "Maximum connect/request timeout: " +
                        "${policy.maximumConnectTimeoutMs}/${policy.maximumRequestTimeoutMs} ms",
                )
                appendLine("Minimum attempts: ${policy.minimumRetryAttempts}")
                appendLine(
                    "Metrics / tracing / structured logs / audit: " +
                        "${policy.requireMetrics} / ${policy.requireTracing} / " +
                        "${policy.requireStructuredLogging} / ${policy.requireAudit}",
                )
                appendLine("Observability API: ${policy.requiredObservabilityApi ?: "not constrained"}")
                appendLine()
                appendLine(
                    "This bundle contains no endpoint, credential, certificate, private key, " +
                        "or executable generator code. The target file is created once and never replaced.",
                )
            }
            accessibleContext.accessibleName = "Connector catalog policy review"
        }
        return JBScrollPane(review).apply {
            preferredSize = Dimension(760, 540)
            border = null
        }
    }
}
