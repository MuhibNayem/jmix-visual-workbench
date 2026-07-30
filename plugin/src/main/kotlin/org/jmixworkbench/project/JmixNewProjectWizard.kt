package org.jmixworkbench.project

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.*
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Path
import javax.swing.Icon

class JmixNewProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "jmix-visual-workbench"

    override val name: String = "Jmix"

    override val icon: Icon =
        IconLoader.getIcon("/icons/workbench.svg", JmixNewProjectWizard::class.java)

    override val description: String =
        "Create a dependency-pinned Jmix application, add-on, or composite project."

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        NewProjectWizardChainStep(RootNewProjectWizardStep(context))
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::JmixProjectSettingsStep)
}

private class JmixProjectSettingsStep(
    parent: NewProjectWizardStep,
) : AbstractNewProjectWizardStep(parent) {
    private val templateKindProperty =
        propertyGraph.property(JmixProjectTemplateKind.APPLICATION)
    private val languageProperty =
        propertyGraph.property(JmixProjectLanguage.JAVA)
    private val uiKindProperty =
        propertyGraph.property(JmixProjectUiKind.FLOW_UI)
    private val jmixVersionProperty = propertyGraph.property("2.8.2")
    private val javaVersionProperty = propertyGraph.property(17)
    private val groupIdProperty = propertyGraph.property("com.company")
    private val artifactIdProperty = propertyGraph.property("jmix-application")
    private val basePackageProperty = propertyGraph.property("com.company.application")
    private val projectIdProperty = propertyGraph.property("app")
    private val localesProperty = propertyGraph.property("en")
    private val useMavenLocalProperty = propertyGraph.property(false)
    private val repositoriesProperty = propertyGraph.property("")

    override fun setupUI(builder: Panel) {
        builder.group("Jmix project") {
            row("Template:") {
                comboBox(JmixProjectTemplateKind.entries)
                    .bindItem(templateKindProperty)
            }
            row("Language:") {
                comboBox(JmixProjectLanguage.entries)
                    .bindItem(languageProperty)
                comment("Application, add-on, tests and composite modules use the selected language.")
            }
            row("Application UI:") {
                comboBox(JmixProjectUiKind.entries)
                    .bindItem(uiKindProperty)
                comment("Applies to application and composite templates. Add-ons remain UI-neutral.")
            }
            row("Jmix version:") {
                comboBox(JmixProjectTemplateGenerator.certifiedVersions.map { it.jmixVersion })
                    .bindItem(jmixVersionProperty)
                    .onChanged { combo ->
                        val selected = combo.selectedItem as? String ?: return@onChanged
                        val version = JmixProjectTemplateGenerator.certifiedVersions
                            .single { it.jmixVersion == selected }
                        if (javaVersionProperty.get() !in version.supportedJavaVersions) {
                            javaVersionProperty.set(version.supportedJavaVersions.min())
                        }
                    }
            }
            row("Java:") {
                comboBox(listOf(17, 21, 25))
                    .bindItem(javaVersionProperty)
                comment("Jmix 2.8: Java 17/21. Jmix 3.0: Java 21/25 (Java 21 bytecode).")
            }
        }
        builder.group("Coordinates") {
            row("Group:") {
                textField().bindText(groupIdProperty)
            }
            row("Artifact:") {
                textField().bindText(artifactIdProperty)
            }
            row("Base package:") {
                textField().bindText(basePackageProperty)
            }
            row("Project ID:") {
                textField().bindText(projectIdProperty)
                comment("Stable Jmix module prefix; maximum 7 lowercase characters.")
            }
            row("Locales:") {
                textField().bindText(localesProperty)
                comment("Comma-separated locale codes, for example en,bn or en_US.")
            }
        }
        builder.group("Dependency repositories") {
            row {
                checkBox("Include Maven Local")
                    .bindSelected(useMavenLocalProperty)
            }
            row("Additional HTTPS repositories:") {
                textArea()
                    .applyToComponent { rows = 3 }
                    .bindText(repositoriesProperty)
                comment("One HTTPS base URL per line. Credentials are never written to the project.")
            }
            row {
                label(
                    "The generated Gradle wrapper and distribution checksum are pinned. " +
                        "Production datasource secrets are not generated.",
                )
            }
        }
    }

    override fun setupProject(project: Project) {
        val request = JmixProjectTemplateRequest(
            projectName = context.projectName.orEmpty().ifBlank { artifactIdProperty.get() },
            groupId = groupIdProperty.get(),
            artifactId = artifactIdProperty.get(),
            basePackage = basePackageProperty.get(),
            projectId = projectIdProperty.get(),
            jmixVersion = jmixVersionProperty.get(),
            javaVersion = javaVersionProperty.get(),
            templateKind = templateKindProperty.get(),
            language = languageProperty.get(),
            uiKind = if (templateKindProperty.get() == JmixProjectTemplateKind.ADDON) {
                JmixProjectUiKind.HEADLESS
            } else {
                uiKindProperty.get()
            },
            locales = localesProperty.get().split(','),
            useMavenLocal = useMavenLocalProperty.get(),
            additionalRepositories = repositoriesProperty.get().lineSequence().toList(),
        )
        try {
            ensureSelectedJdkMatches(request)
            val generated = JmixProjectTemplateGenerator.generate(request)
            val root = Path.of(requireNotNull(project.basePath) {
                "IntelliJ did not provide the new project directory."
            })
            JmixProjectInstaller.install(root, generated)
            context.projectJdk?.let { sdk ->
                ProjectRootManager.getInstance(project).projectSdk = sdk
            }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)?.refresh(
                false,
                true,
            )
            linkGradleProject(project, root)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            Messages.showErrorDialog(
                project,
                failure.message ?: "Jmix project creation failed.",
                "Cannot Create Jmix Project",
            )
            throw failure
        }
    }

    private fun linkGradleProject(project: Project, root: Path) {
        val externalProjectPath = root.toAbsolutePath().normalize().toString()
        val gradleSettings = GradleSettings.getInstance(project)
        if (gradleSettings.getLinkedProjectSettings(externalProjectPath) != null) {
            return
        }
        val projectSettings = GradleProjectSettings().apply {
            setupNewProjectDefault()
            this.externalProjectPath = externalProjectPath
            distributionType = DistributionType.WRAPPED
            context.projectJdk?.name?.let { gradleJvm = it }
        }
        gradleSettings.linkProject(projectSettings)
    }

    private fun ensureSelectedJdkMatches(request: JmixProjectTemplateRequest) {
        val sdk = context.projectJdk ?: return
        val selectedVersion = JavaSdkVersion.fromVersionString(sdk.versionString.orEmpty()) ?: return
        val feature = selectedVersion.maxLanguageLevel.feature()
        require(feature == request.javaVersion) {
            "The selected SDK is Java $feature, but the template targets Java ${request.javaVersion}."
        }
    }
}
