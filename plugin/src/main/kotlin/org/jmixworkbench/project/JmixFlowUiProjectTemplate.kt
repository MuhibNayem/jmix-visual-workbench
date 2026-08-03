package org.jmixworkbench.project

import java.util.Locale

/**
 * Complete source-owned FlowUI starter assets. The generated project intentionally has no
 * production credential. Its development profile provisions an in-memory administrator with an
 * environment-provided password or a random one-time password logged only at local startup.
 */
internal object JmixFlowUiProjectTemplate {
    fun files(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        applicationClass: String,
        prefix: String,
    ): List<GeneratedProjectFile> {
        val packagePath = request.basePackage.replace('.', '/')
        val sourceRoot = if (request.language == JmixProjectLanguage.JAVA) "java" else "kotlin"
        val extension = if (request.language == JmixProjectLanguage.JAVA) "java" else "kt"
        val themeName = request.artifactId
        val tableName = "${request.projectId.uppercase(Locale.ROOT)}_USER"
        val sourceBase = "src/main/$sourceRoot/$packagePath"
        val resourceBase = "src/main/resources/$packagePath"
        val testBase = "src/test/$sourceRoot/$packagePath"

        val files = mutableListOf(
            file(
                prefix,
                "$sourceBase/$applicationClass.$extension",
                applicationSource(request, version, applicationClass, themeName),
            ),
            file(
                prefix,
                "$sourceBase/entity/User.$extension",
                userSource(request, tableName),
            ),
            file(
                prefix,
                "$sourceBase/security/DatabaseUserRepository.$extension",
                databaseUserRepositorySource(request),
            ),
            file(
                prefix,
                "$sourceBase/security/DevelopmentUserConfiguration.$extension",
                developmentUserConfigurationSource(request),
            ),
            file(
                prefix,
                "$sourceBase/security/FullAccessRole.$extension",
                fullAccessRoleSource(request),
            ),
            file(
                prefix,
                "$sourceBase/security/UiMinimalRole.$extension",
                uiMinimalRoleSource(request),
            ),
            file(
                prefix,
                "$sourceBase/view/main/MainView.$extension",
                mainViewSource(request),
            ),
            file(
                prefix,
                "$sourceBase/view/login/LoginView.$extension",
                loginViewSource(request),
            ),
            file(
                prefix,
                "$sourceBase/view/welcome/WelcomeView.$extension",
                welcomeViewSource(request),
            ),
            file(
                prefix,
                "$resourceBase/view/main/main-view.xml",
                mainViewXml(),
            ),
            file(
                prefix,
                "$resourceBase/view/login/login-view.xml",
                loginViewXml(),
            ),
            file(
                prefix,
                "$resourceBase/view/welcome/welcome-view.xml",
                welcomeViewXml(),
            ),
            file(
                prefix,
                "$resourceBase/menu.xml",
                menuXml(request),
            ),
            file(
                prefix,
                "$resourceBase/liquibase/changelog.xml",
                masterChangelog(request),
            ),
            file(
                prefix,
                "$resourceBase/liquibase/changelog/001-user.xml",
                userChangelog(tableName),
            ),
            file(
                prefix,
                "src/main/resources/application.properties",
                applicationProperties(request),
            ),
            file(
                prefix,
                "src/main/frontend/themes/$themeName/theme.json",
                themeJson(),
            ),
            file(
                prefix,
                "src/main/frontend/themes/$themeName/styles.css",
                themeStyles(),
            ),
            file(
                prefix,
                "src/main/frontend/themes/$themeName/application.css",
                applicationThemeCss(),
            ),
            file(
                prefix,
                "src/main/frontend/themes/$themeName/view/main-view.css",
                mainViewCss(),
            ),
            file(
                prefix,
                "src/main/frontend/themes/$themeName/view/login-view.css",
                loginViewCss(),
            ),
            file(
                prefix,
                "$testBase/${applicationClass}Test.$extension",
                applicationTestSource(request, applicationClass),
            ),
        )
        files += localizedMessageFiles(request, prefix, resourceBase)
        return files.sortedBy(GeneratedProjectFile::relativePath)
    }

    private fun applicationSource(
        request: JmixProjectTemplateRequest,
        version: JmixProjectVersion,
        applicationClass: String,
        themeName: String,
    ): String {
        val dataSourcePropertiesImport = if (version.jmixVersion.startsWith("3.")) {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceProperties"
        } else {
            "org.springframework.boot.autoconfigure.jdbc.DataSourceProperties"
        }
        return when (request.language) {
            JmixProjectLanguage.JAVA -> """
                package ${request.basePackage};

                import com.vaadin.flow.component.page.AppShellConfigurator;
                import com.vaadin.flow.theme.Theme;
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import $dataSourcePropertiesImport;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                import org.springframework.context.ConfigurableApplicationContext;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Primary;

                import javax.sql.DataSource;

                @Theme("$themeName")
                @SpringBootApplication
                public class $applicationClass implements AppShellConfigurator {

                    @Bean
                    @Primary
                    @ConfigurationProperties("main.datasource")
                    DataSourceProperties dataSourceProperties() {
                        return new DataSourceProperties();
                    }

                    @Bean
                    @Primary
                    @ConfigurationProperties("main.datasource.hikari")
                    DataSource dataSource(DataSourceProperties properties) {
                        return properties.initializeDataSourceBuilder().build();
                    }

                    public static void main(String[] args) {
                        ConfigurableApplicationContext context =
                                SpringApplication.run($applicationClass.class, args);
                        if (Boolean.getBoolean("jvw.certify.startup")) {
                            context.close();
                        }
                    }
                }
            """.trimIndent() + "\n"
            JmixProjectLanguage.KOTLIN -> """
                package ${request.basePackage}

                import com.vaadin.flow.component.page.AppShellConfigurator
                import com.vaadin.flow.theme.Theme
                import org.springframework.boot.autoconfigure.SpringBootApplication
                import org.springframework.boot.runApplication
                import $dataSourcePropertiesImport
                import org.springframework.boot.context.properties.ConfigurationProperties
                import org.springframework.context.annotation.Bean
                import org.springframework.context.annotation.Primary
                import javax.sql.DataSource

                @Theme("$themeName")
                @SpringBootApplication
                class $applicationClass : AppShellConfigurator {

                    @Bean
                    @Primary
                    @ConfigurationProperties("main.datasource")
                    fun dataSourceProperties(): DataSourceProperties = DataSourceProperties()

                    @Bean
                    @Primary
                    @ConfigurationProperties("main.datasource.hikari")
                    fun dataSource(properties: DataSourceProperties): DataSource =
                        properties.initializeDataSourceBuilder().build()

                    companion object {
                        @JvmStatic
                        fun main(args: Array<String>) {
                            val context = runApplication<$applicationClass>(*args)
                            if (java.lang.Boolean.getBoolean("jvw.certify.startup")) {
                                context.close()
                            }
                        }
                    }
                }
            """.trimIndent() + "\n"
        }
    }

    private fun userSource(
        request: JmixProjectTemplateRequest,
        tableName: String,
    ): String = when (request.language) {
        JmixProjectLanguage.JAVA -> """
            package ${request.basePackage}.entity;

            import io.jmix.core.HasTimeZone;
            import io.jmix.core.annotation.Secret;
            import io.jmix.core.entity.annotation.JmixGeneratedValue;
            import io.jmix.core.entity.annotation.SystemLevel;
            import io.jmix.core.metamodel.annotation.DependsOnProperties;
            import io.jmix.core.metamodel.annotation.InstanceName;
            import io.jmix.core.metamodel.annotation.JmixEntity;
            import io.jmix.security.authentication.JmixUserDetails;
            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.Index;
            import jakarta.persistence.Table;
            import jakarta.persistence.Transient;
            import jakarta.persistence.Version;
            import jakarta.validation.constraints.Email;
            import org.springframework.security.core.GrantedAuthority;

            import java.util.Collection;
            import java.util.Collections;
            import java.util.UUID;

            @JmixEntity
            @Entity
            @Table(name = "$tableName", indexes = {
                    @Index(name = "IDX_${tableName}_USERNAME", columnList = "USERNAME", unique = true)
            })
            public class User implements JmixUserDetails, HasTimeZone {
                @Id
                @JmixGeneratedValue
                @Column(name = "ID", nullable = false)
                private UUID id;

                @Version
                @Column(name = "VERSION", nullable = false)
                private Integer version;

                @Column(name = "USERNAME", nullable = false)
                private String username;

                @Secret
                @SystemLevel
                @Column(name = "PASSWORD")
                private String password;

                @Column(name = "FIRST_NAME")
                private String firstName;

                @Column(name = "LAST_NAME")
                private String lastName;

                @Email
                @Column(name = "EMAIL")
                private String email;

                @Column(name = "ACTIVE", nullable = false)
                private Boolean active = true;

                @Column(name = "TIME_ZONE_ID")
                private String timeZoneId;

                @Transient
                private Collection<? extends GrantedAuthority> authorities;

                public UUID getId() {
                    return id;
                }

                public void setId(UUID id) {
                    this.id = id;
                }

                public Integer getVersion() {
                    return version;
                }

                public void setVersion(Integer version) {
                    this.version = version;
                }

                @Override
                public String getUsername() {
                    return username;
                }

                public void setUsername(String username) {
                    this.username = username;
                }

                @Override
                public String getPassword() {
                    return password;
                }

                public void setPassword(String password) {
                    this.password = password;
                }

                public String getFirstName() {
                    return firstName;
                }

                public void setFirstName(String firstName) {
                    this.firstName = firstName;
                }

                public String getLastName() {
                    return lastName;
                }

                public void setLastName(String lastName) {
                    this.lastName = lastName;
                }

                public String getEmail() {
                    return email;
                }

                public void setEmail(String email) {
                    this.email = email;
                }

                public Boolean getActive() {
                    return active;
                }

                public void setActive(Boolean active) {
                    this.active = active;
                }

                @Override
                public Collection<? extends GrantedAuthority> getAuthorities() {
                    return authorities != null ? authorities : Collections.emptyList();
                }

                @Override
                public void setAuthorities(Collection<? extends GrantedAuthority> authorities) {
                    this.authorities = authorities;
                }

                @Override
                public boolean isAccountNonExpired() {
                    return true;
                }

                @Override
                public boolean isAccountNonLocked() {
                    return true;
                }

                @Override
                public boolean isCredentialsNonExpired() {
                    return true;
                }

                @Override
                public boolean isEnabled() {
                    return Boolean.TRUE.equals(active);
                }

                @InstanceName
                @DependsOnProperties({"firstName", "lastName", "username"})
                public String getDisplayName() {
                    String name = ((firstName != null ? firstName : "") + " "
                            + (lastName != null ? lastName : "")).trim();
                    return name.isBlank() ? username : name + " [" + username + "]";
                }

                @Override
                public String getTimeZoneId() {
                    return timeZoneId;
                }

                public void setTimeZoneId(String timeZoneId) {
                    this.timeZoneId = timeZoneId;
                }
            }
        """.trimIndent() + "\n"
        JmixProjectLanguage.KOTLIN -> """
            package ${request.basePackage}.entity

            import io.jmix.core.HasTimeZone
            import io.jmix.core.annotation.Secret
            import io.jmix.core.entity.annotation.JmixGeneratedValue
            import io.jmix.core.entity.annotation.SystemLevel
            import io.jmix.core.metamodel.annotation.DependsOnProperties
            import io.jmix.core.metamodel.annotation.InstanceName
            import io.jmix.core.metamodel.annotation.JmixEntity
            import io.jmix.security.authentication.JmixUserDetails
            import jakarta.persistence.Access
            import jakarta.persistence.AccessType
            import jakarta.persistence.Column
            import jakarta.persistence.Entity
            import jakarta.persistence.Id
            import jakarta.persistence.Index
            import jakarta.persistence.Table
            import jakarta.persistence.Transient
            import jakarta.persistence.Version
            import jakarta.validation.constraints.Email
            import org.springframework.security.core.GrantedAuthority
            import java.util.UUID

            @JmixEntity
            @Entity
            @Access(AccessType.PROPERTY)
            @Table(
                name = "$tableName",
                indexes = [Index(
                    name = "IDX_${tableName}_USERNAME",
                    columnList = "USERNAME",
                    unique = true,
                )],
            )
            class User : JmixUserDetails, HasTimeZone {
                @JmixGeneratedValue
                private var idValue: UUID? = null
                private var versionValue: Int? = null
                private var usernameValue: String = ""
                @Secret
                @SystemLevel
                private var passwordValue: String? = null
                private var firstNameValue: String? = null
                private var lastNameValue: String? = null
                @Email
                private var emailValue: String? = null
                private var activeValue: Boolean = true
                private var timeZoneIdValue: String? = null
                private var authorityValues: Collection<GrantedAuthority> = emptyList()

                @Id
                @Column(name = "ID", nullable = false)
                fun getId(): UUID? = idValue

                fun setId(value: UUID?) {
                    idValue = value
                }

                @Version
                @Column(name = "VERSION", nullable = false)
                fun getVersion(): Int? = versionValue

                fun setVersion(value: Int?) {
                    versionValue = value
                }

                @Column(name = "USERNAME", nullable = false)
                override fun getUsername(): String = usernameValue

                fun setUsername(value: String) {
                    usernameValue = value
                }

                @Column(name = "PASSWORD")
                override fun getPassword(): String? = passwordValue

                fun setPassword(value: String?) {
                    passwordValue = value
                }

                @Column(name = "FIRST_NAME")
                fun getFirstName(): String? = firstNameValue

                fun setFirstName(value: String?) {
                    firstNameValue = value
                }

                @Column(name = "LAST_NAME")
                fun getLastName(): String? = lastNameValue

                fun setLastName(value: String?) {
                    lastNameValue = value
                }

                @Column(name = "EMAIL")
                fun getEmail(): String? = emailValue

                fun setEmail(value: String?) {
                    emailValue = value
                }

                @Column(name = "ACTIVE", nullable = false)
                fun getActive(): Boolean = activeValue

                fun setActive(value: Boolean) {
                    activeValue = value
                }

                @Transient
                override fun getAuthorities(): Collection<GrantedAuthority> = authorityValues

                override fun setAuthorities(authorities: Collection<GrantedAuthority>) {
                    authorityValues = authorities
                }

                override fun isAccountNonExpired(): Boolean = true

                override fun isAccountNonLocked(): Boolean = true

                override fun isCredentialsNonExpired(): Boolean = true

                override fun isEnabled(): Boolean = activeValue

                @InstanceName
                @DependsOnProperties("firstName", "lastName", "username")
                fun getDisplayName(): String {
                    val name = listOfNotNull(firstNameValue, lastNameValue)
                        .joinToString(" ")
                        .trim()
                    return if (name.isBlank()) usernameValue else "${'$'}name [${'$'}usernameValue]"
                }

                @Column(name = "TIME_ZONE_ID")
                override fun getTimeZoneId(): String? = timeZoneIdValue

                fun setTimeZoneId(value: String?) {
                    timeZoneIdValue = value
                }
            }
        """.trimIndent() + "\n"
    }

    private fun databaseUserRepositorySource(request: JmixProjectTemplateRequest): String =
        when (request.language) {
            JmixProjectLanguage.JAVA -> """
                package ${request.basePackage}.security;

                import ${request.basePackage}.entity.User;
                import io.jmix.securitydata.user.AbstractDatabaseUserRepository;
                import org.springframework.context.annotation.Primary;
                import org.springframework.context.annotation.Profile;
                import org.springframework.security.core.GrantedAuthority;
                import org.springframework.stereotype.Component;

                import java.util.Collection;

                @Primary
                @Profile("!dev")
                @Component("UserRepository")
                public class DatabaseUserRepository extends AbstractDatabaseUserRepository<User> {
                    @Override
                    protected Class<User> getUserClass() {
                        return User.class;
                    }

                    @Override
                    protected void initSystemUser(User systemUser) {
                        Collection<GrantedAuthority> authorities = getGrantedAuthoritiesBuilder()
                                .addResourceRole(FullAccessRole.CODE)
                                .build();
                        systemUser.setAuthorities(authorities);
                    }

                    @Override
                    protected void initAnonymousUser(User anonymousUser) {
                    }
                }
            """.trimIndent() + "\n"
            JmixProjectLanguage.KOTLIN -> """
                package ${request.basePackage}.security

                import ${request.basePackage}.entity.User
                import io.jmix.securitydata.user.AbstractDatabaseUserRepository
                import org.springframework.context.annotation.Primary
                import org.springframework.context.annotation.Profile
                import org.springframework.stereotype.Component

                @Primary
                @Profile("!dev")
                @Component("UserRepository")
                class DatabaseUserRepository : AbstractDatabaseUserRepository<User>() {
                    override fun getUserClass(): Class<User> = User::class.java

                    override fun initSystemUser(systemUser: User) {
                        systemUser.setAuthorities(
                            grantedAuthoritiesBuilder
                                .addResourceRole(FullAccessRole.CODE)
                                .build(),
                        )
                    }

                    override fun initAnonymousUser(anonymousUser: User) = Unit
                }
            """.trimIndent() + "\n"
        }

    private fun developmentUserConfigurationSource(request: JmixProjectTemplateRequest): String =
        when (request.language) {
            JmixProjectLanguage.JAVA -> """
                package ${request.basePackage}.security;

                import ${request.basePackage}.entity.User;
                import io.jmix.core.security.InMemoryUserRepository;
                import io.jmix.core.security.UserRepository;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.annotation.Primary;
                import org.springframework.context.annotation.Profile;
                import org.springframework.core.env.Environment;
                import org.springframework.security.core.authority.SimpleGrantedAuthority;
                import org.springframework.security.crypto.password.PasswordEncoder;

                import java.security.SecureRandom;
                import java.util.Base64;
                import java.util.List;

                @Configuration(proxyBeanMethods = false)
                @Profile("dev")
                public class DevelopmentUserConfiguration {
                    private static final Logger log =
                            LoggerFactory.getLogger(DevelopmentUserConfiguration.class);

                    @Bean
                    @Primary
                    UserRepository developmentUserRepository(
                            PasswordEncoder passwordEncoder,
                            Environment environment
                    ) {
                        InMemoryUserRepository repository = new InMemoryUserRepository();
                        String username = environment.getProperty(
                                "JMIX_DEV_ADMIN_USERNAME",
                                "admin");
                        String configuredPassword =
                                environment.getProperty("JMIX_DEV_ADMIN_PASSWORD");
                        String password = configuredPassword == null || configuredPassword.isBlank()
                                ? randomPassword()
                                : configuredPassword;

                        User admin = new User();
                        admin.setUsername(username);
                        admin.setPassword(passwordEncoder.encode(password));
                        admin.setActive(true);
                        admin.setAuthorities(List.of(
                                new SimpleGrantedAuthority("ROLE_" + FullAccessRole.CODE)));
                        repository.addUser(admin);

                        if (configuredPassword == null || configuredPassword.isBlank()) {
                            log.warn(
                                    "Generated local-only Jmix administrator '{}' password: {}. "
                                            + "Set JMIX_DEV_ADMIN_PASSWORD to control it.",
                                    username,
                                    password);
                        }
                        return repository;
                    }

                    private String randomPassword() {
                        byte[] bytes = new byte[18];
                        new SecureRandom().nextBytes(bytes);
                        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                    }
                }
            """.trimIndent() + "\n"
            JmixProjectLanguage.KOTLIN -> """
                package ${request.basePackage}.security

                import ${request.basePackage}.entity.User
                import io.jmix.core.security.InMemoryUserRepository
                import io.jmix.core.security.UserRepository
                import org.slf4j.LoggerFactory
                import org.springframework.context.annotation.Bean
                import org.springframework.context.annotation.Configuration
                import org.springframework.context.annotation.Primary
                import org.springframework.context.annotation.Profile
                import org.springframework.core.env.Environment
                import org.springframework.security.core.authority.SimpleGrantedAuthority
                import org.springframework.security.crypto.password.PasswordEncoder
                import java.security.SecureRandom
                import java.util.Base64

                @Configuration(proxyBeanMethods = false)
                @Profile("dev")
                class DevelopmentUserConfiguration {
                    @Bean
                    @Primary
                    fun developmentUserRepository(
                        passwordEncoder: PasswordEncoder,
                        environment: Environment,
                    ): UserRepository {
                        val repository = InMemoryUserRepository()
                        val username =
                            environment.getProperty("JMIX_DEV_ADMIN_USERNAME", "admin")
                        val configuredPassword =
                            environment.getProperty("JMIX_DEV_ADMIN_PASSWORD")
                        val password = configuredPassword
                            ?.takeIf(String::isNotBlank)
                            ?: randomPassword()

                        val admin = User().apply {
                            setUsername(username)
                            setPassword(passwordEncoder.encode(password))
                            setActive(true)
                            setAuthorities(listOf(
                                SimpleGrantedAuthority("ROLE_${'$'}{FullAccessRole.CODE}"),
                            ))
                        }
                        repository.addUser(admin)

                        if (configuredPassword.isNullOrBlank()) {
                            log.warn(
                                "Generated local-only Jmix administrator '{}' password: {}. " +
                                    "Set JMIX_DEV_ADMIN_PASSWORD to control it.",
                                username,
                                password,
                            )
                        }
                        return repository
                    }

                    private fun randomPassword(): String {
                        val bytes = ByteArray(18)
                        SecureRandom().nextBytes(bytes)
                        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                    }

                    private companion object {
                        val log = LoggerFactory.getLogger(DevelopmentUserConfiguration::class.java)
                    }
                }
            """.trimIndent() + "\n"
        }

    private fun fullAccessRoleSource(request: JmixProjectTemplateRequest): String =
        when (request.language) {
            JmixProjectLanguage.JAVA -> """
                package ${request.basePackage}.security;

                import io.jmix.security.model.EntityAttributePolicyAction;
                import io.jmix.security.model.EntityPolicyAction;
                import io.jmix.security.role.annotation.EntityAttributePolicy;
                import io.jmix.security.role.annotation.EntityPolicy;
                import io.jmix.security.role.annotation.ResourceRole;
                import io.jmix.security.role.annotation.SpecificPolicy;
                import io.jmix.securityflowui.role.annotation.MenuPolicy;
                import io.jmix.securityflowui.role.annotation.ViewPolicy;

                @ResourceRole(name = "Full Access", code = FullAccessRole.CODE)
                public interface FullAccessRole {
                    String CODE = "system-full-access";

                    @EntityPolicy(entityName = "*", actions = EntityPolicyAction.ALL)
                    @EntityAttributePolicy(
                            entityName = "*",
                            attributes = "*",
                            action = EntityAttributePolicyAction.MODIFY)
                    @ViewPolicy(viewIds = "*")
                    @MenuPolicy(menuIds = "*")
                    @SpecificPolicy(resources = "*")
                    void fullAccess();
                }
            """.trimIndent() + "\n"
            JmixProjectLanguage.KOTLIN -> """
                package ${request.basePackage}.security

                import io.jmix.security.model.EntityAttributePolicyAction
                import io.jmix.security.model.EntityPolicyAction
                import io.jmix.security.role.annotation.EntityAttributePolicy
                import io.jmix.security.role.annotation.EntityPolicy
                import io.jmix.security.role.annotation.ResourceRole
                import io.jmix.security.role.annotation.SpecificPolicy
                import io.jmix.securityflowui.role.annotation.MenuPolicy
                import io.jmix.securityflowui.role.annotation.ViewPolicy

                @ResourceRole(name = "Full Access", code = FullAccessRole.CODE)
                interface FullAccessRole {
                    @EntityPolicy(entityName = "*", actions = [EntityPolicyAction.ALL])
                    @EntityAttributePolicy(
                        entityName = "*",
                        attributes = ["*"],
                        action = EntityAttributePolicyAction.MODIFY,
                    )
                    @ViewPolicy(viewIds = ["*"])
                    @MenuPolicy(menuIds = ["*"])
                    @SpecificPolicy(resources = ["*"])
                    fun fullAccess()

                    companion object {
                        const val CODE = "system-full-access"
                    }
                }
            """.trimIndent() + "\n"
        }

    private fun uiMinimalRoleSource(request: JmixProjectTemplateRequest): String =
        when (request.language) {
            JmixProjectLanguage.JAVA -> """
                package ${request.basePackage}.security;

                import io.jmix.security.model.SecurityScope;
                import io.jmix.security.role.annotation.ResourceRole;
                import io.jmix.security.role.annotation.SpecificPolicy;
                import io.jmix.securityflowui.role.UiMinimalPolicies;
                import io.jmix.securityflowui.role.annotation.MenuPolicy;
                import io.jmix.securityflowui.role.annotation.ViewPolicy;

                @ResourceRole(
                        name = "UI: minimal access",
                        code = UiMinimalRole.CODE,
                        scope = SecurityScope.UI)
                public interface UiMinimalRole extends UiMinimalPolicies {
                    String CODE = "ui-minimal";

                    @ViewPolicy(viewIds = {"MainView", "LoginView", "WelcomeView"})
                    @MenuPolicy(menuIds = {
                            "application",
                            "workspace",
                            "getting-started",
                            "welcome"
                    })
                    @SpecificPolicy(resources = "ui.loginToUi")
                    void applicationShell();
                }
            """.trimIndent() + "\n"
            JmixProjectLanguage.KOTLIN -> """
                package ${request.basePackage}.security

                import io.jmix.security.model.SecurityScope
                import io.jmix.security.role.annotation.ResourceRole
                import io.jmix.security.role.annotation.SpecificPolicy
                import io.jmix.securityflowui.role.UiMinimalPolicies
                import io.jmix.securityflowui.role.annotation.MenuPolicy
                import io.jmix.securityflowui.role.annotation.ViewPolicy

                @ResourceRole(
                    name = "UI: minimal access",
                    code = UiMinimalRole.CODE,
                    scope = [SecurityScope.UI],
                )
                interface UiMinimalRole : UiMinimalPolicies {
                    @ViewPolicy(viewIds = ["MainView", "LoginView", "WelcomeView"])
                    @MenuPolicy(menuIds = [
                        "application",
                        "workspace",
                        "getting-started",
                        "welcome",
                    ])
                    @SpecificPolicy(resources = ["ui.loginToUi"])
                    fun applicationShell()

                    companion object {
                        const val CODE = "ui-minimal"
                    }
                }
            """.trimIndent() + "\n"
        }

    private fun mainViewSource(request: JmixProjectTemplateRequest): String =
        when (request.language) {
            JmixProjectLanguage.JAVA -> """
                package ${request.basePackage}.view.main;

                import com.vaadin.flow.router.Route;
                import io.jmix.flowui.app.main.StandardMainView;
                import io.jmix.flowui.view.ViewController;
                import io.jmix.flowui.view.ViewDescriptor;

                @Route("")
                @ViewController(id = "MainView")
                @ViewDescriptor(path = "main-view.xml")
                public class MainView extends StandardMainView {
                }
            """.trimIndent() + "\n"
            JmixProjectLanguage.KOTLIN -> """
                package ${request.basePackage}.view.main

                import com.vaadin.flow.router.Route
                import io.jmix.flowui.app.main.StandardMainView
                import io.jmix.flowui.view.ViewController
                import io.jmix.flowui.view.ViewDescriptor

                @Route("")
                @ViewController(id = "MainView")
                @ViewDescriptor(path = "main-view.xml")
                class MainView : StandardMainView()
            """.trimIndent() + "\n"
        }

    private fun loginViewSource(request: JmixProjectTemplateRequest): String =
        when (request.language) {
            JmixProjectLanguage.JAVA -> """
                package ${request.basePackage}.view.login;

                import com.vaadin.flow.component.login.AbstractLogin.LoginEvent;
                import com.vaadin.flow.router.Route;
                import io.jmix.flowui.component.loginform.JmixLoginForm;
                import io.jmix.flowui.view.StandardView;
                import io.jmix.flowui.view.Subscribe;
                import io.jmix.flowui.view.ViewComponent;
                import io.jmix.flowui.view.ViewController;
                import io.jmix.flowui.view.ViewDescriptor;
                import io.jmix.securityflowui.authentication.AuthDetails;
                import io.jmix.securityflowui.authentication.LoginViewSupport;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.security.core.AuthenticationException;

                @Route("login")
                @ViewController(id = "LoginView")
                @ViewDescriptor(path = "login-view.xml")
                public class LoginView extends StandardView {
                    @Autowired
                    private LoginViewSupport loginViewSupport;

                    @ViewComponent
                    private JmixLoginForm login;

                    @Subscribe("login")
                    public void onLogin(LoginEvent event) {
                        try {
                            loginViewSupport.authenticate(
                                    AuthDetails.of(event.getUsername(), event.getPassword())
                                            .withLocale(login.getSelectedLocale())
                                            .withRememberMe(login.isRememberMe()));
                        } catch (AuthenticationException exception) {
                            event.getSource().setError(true);
                        }
                    }
                }
            """.trimIndent() + "\n"
            JmixProjectLanguage.KOTLIN -> """
                package ${request.basePackage}.view.login

                import com.vaadin.flow.component.login.AbstractLogin.LoginEvent
                import com.vaadin.flow.router.Route
                import io.jmix.flowui.component.loginform.JmixLoginForm
                import io.jmix.flowui.view.StandardView
                import io.jmix.flowui.view.Subscribe
                import io.jmix.flowui.view.ViewComponent
                import io.jmix.flowui.view.ViewController
                import io.jmix.flowui.view.ViewDescriptor
                import io.jmix.securityflowui.authentication.AuthDetails
                import io.jmix.securityflowui.authentication.LoginViewSupport
                import org.springframework.beans.factory.annotation.Autowired
                import org.springframework.security.core.AuthenticationException

                @Route("login")
                @ViewController(id = "LoginView")
                @ViewDescriptor(path = "login-view.xml")
                class LoginView : StandardView() {
                    @Autowired
                    private lateinit var loginViewSupport: LoginViewSupport

                    @ViewComponent
                    private lateinit var login: JmixLoginForm

                    @Subscribe("login")
                    fun onLogin(event: LoginEvent) {
                        try {
                            loginViewSupport.authenticate(
                                AuthDetails.of(event.username, event.password)
                                    .withLocale(login.selectedLocale)
                                    .withRememberMe(login.isRememberMe),
                            )
                        } catch (exception: AuthenticationException) {
                            event.source.isError = true
                        }
                    }
                }
            """.trimIndent() + "\n"
        }

    private fun welcomeViewSource(request: JmixProjectTemplateRequest): String =
        when (request.language) {
            JmixProjectLanguage.JAVA -> """
                package ${request.basePackage}.view.welcome;

                import ${request.basePackage}.view.main.MainView;
                import com.vaadin.flow.router.Route;
                import io.jmix.flowui.view.StandardView;
                import io.jmix.flowui.view.ViewController;
                import io.jmix.flowui.view.ViewDescriptor;

                @Route(value = "welcome", layout = MainView.class)
                @ViewController(id = "WelcomeView")
                @ViewDescriptor(path = "welcome-view.xml")
                public class WelcomeView extends StandardView {
                }
            """.trimIndent() + "\n"
            JmixProjectLanguage.KOTLIN -> """
                package ${request.basePackage}.view.welcome

                import ${request.basePackage}.view.main.MainView
                import com.vaadin.flow.router.Route
                import io.jmix.flowui.view.StandardView
                import io.jmix.flowui.view.ViewController
                import io.jmix.flowui.view.ViewDescriptor

                @Route(value = "welcome", layout = MainView::class)
                @ViewController(id = "WelcomeView")
                @ViewDescriptor(path = "welcome-view.xml")
                class WelcomeView : StandardView()
            """.trimIndent() + "\n"
        }

    private fun applicationTestSource(
        request: JmixProjectTemplateRequest,
        applicationClass: String,
    ): String = when (request.language) {
        JmixProjectLanguage.JAVA -> """
            package ${request.basePackage};

            import org.junit.jupiter.api.Test;

            import static org.junit.jupiter.api.Assertions.assertNotNull;

            class ${applicationClass}Test {
                @Test
                void applicationTypeIsAvailable() {
                    assertNotNull($applicationClass.class);
                }
            }
        """.trimIndent() + "\n"
        JmixProjectLanguage.KOTLIN -> """
            package ${request.basePackage}

            import kotlin.test.Test
            import kotlin.test.assertNotNull

            class ${applicationClass}Test {
                @Test
                fun applicationTypeIsAvailable() {
                    assertNotNull($applicationClass::class.java)
                }
            }
        """.trimIndent() + "\n"
    }

    private fun applicationProperties(request: JmixProjectTemplateRequest): String = """
        main.datasource.url=jdbc:hsqldb:file:.jmix/hsqldb/${request.artifactId}
        main.datasource.username=sa
        main.datasource.password=

        main.liquibase.change-log=${request.basePackage.replace('.', '/')}/liquibase/changelog.xml

        jmix.ui.login-view-id=LoginView
        jmix.ui.main-view-id=MainView
        jmix.ui.menu-config=${request.basePackage.replace('.', '/')}/menu.xml
        jmix.ui.composite-menu=true
        jmix.core.available-locales=${request.locales.joinToString(",")}

        vaadin.launch-browser=false
        logging.level.org.atmosphere=warn
        logging.level.eclipselink.logging.sql=info
        logging.level.io.jmix.core.datastore=info
        logging.level.io.jmix.core.AccessLogger=debug
        logging.level.io.jmix=info
        logging.level.org.springframework.security=info
    """.trimIndent() + "\n"

    private fun masterChangelog(request: JmixProjectTemplateRequest): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <databaseChangeLog
                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.31.xsd">
            <include file="/io/jmix/data/liquibase/changelog.xml"/>
            <include file="/io/jmix/flowuidata/liquibase/changelog.xml"/>
            <include file="/io/jmix/securitydata/liquibase/changelog.xml"/>
            <include file="/${request.basePackage.replace('.', '/')}/liquibase/changelog/001-user.xml"/>
        </databaseChangeLog>
    """.trimIndent() + "\n"

    private fun userChangelog(tableName: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <databaseChangeLog
                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.31.xsd">
            <changeSet id="001-create-user" author="jmix-visual-workbench">
                <createTable tableName="$tableName">
                    <column name="ID" type="UUID">
                        <constraints nullable="false" primaryKey="true"
                                     primaryKeyName="PK_$tableName"/>
                    </column>
                    <column name="VERSION" type="INT">
                        <constraints nullable="false"/>
                    </column>
                    <column name="USERNAME" type="VARCHAR(255)">
                        <constraints nullable="false"/>
                    </column>
                    <column name="PASSWORD" type="VARCHAR(255)"/>
                    <column name="FIRST_NAME" type="VARCHAR(255)"/>
                    <column name="LAST_NAME" type="VARCHAR(255)"/>
                    <column name="EMAIL" type="VARCHAR(255)"/>
                    <column name="ACTIVE" type="BOOLEAN" defaultValueBoolean="true">
                        <constraints nullable="false"/>
                    </column>
                    <column name="TIME_ZONE_ID" type="VARCHAR(255)"/>
                </createTable>
                <addUniqueConstraint tableName="$tableName"
                                     columnNames="USERNAME"
                                     constraintName="IDX_${tableName}_USERNAME"/>
                <rollback>
                    <dropTable tableName="$tableName"/>
                </rollback>
            </changeSet>
        </databaseChangeLog>
    """.trimIndent() + "\n"

    private fun menuXml(request: JmixProjectTemplateRequest): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <menu-config xmlns="http://jmix.io/schema/flowui/menu">
            <menu id="application"
                  title="msg://${request.basePackage}/menu.application.title"
                  opened="true">
                <menu id="workspace"
                      title="msg://${request.basePackage}/menu.workspace.title"
                      opened="true">
                    <menu id="getting-started"
                          title="msg://${request.basePackage}/menu.gettingStarted.title"
                          opened="true">
                        <item id="welcome"
                              view="WelcomeView"
                              title="msg://${request.basePackage}.view.welcome/welcomeView.title"/>
                    </menu>
                </menu>
            </menu>
        </menu-config>
    """.trimIndent() + "\n"

    private fun mainViewXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <mainView xmlns="http://jmix.io/schema/flowui/main-view"
                  title="msg://MainView.title">
            <actions>
                <action id="logoutAction" type="logout"/>
            </actions>
            <appLayout>
                <navigationBar>
                    <header id="header" classNames="jmix-main-view-header">
                        <drawerToggle id="drawerToggle"
                                      classNames="jmix-main-view-drawer-toggle"
                                      themeNames="contrast"
                                      ariaLabel="msg://drawerToggle.ariaLabel"/>
                        <h1 id="viewTitle" classNames="jmix-main-view-title"/>
                    </header>
                </navigationBar>
                <drawerLayout>
                    <section id="section" classNames="jmix-main-view-section">
                        <h2 id="applicationTitle"
                            classNames="jmix-main-view-application-title">
                            <anchor id="baseLink"
                                    href="."
                                    text="msg://applicationTitle.text"
                                    classNames="jmix-main-view-application-title-base-link"/>
                        </h2>
                        <nav id="navigation"
                             classNames="jmix-main-view-navigation"
                             ariaLabel="msg://navigation.ariaLabel">
                            <listMenu id="menu"/>
                        </nav>
                        <footer id="footer" classNames="jmix-main-view-footer">
                            <userIndicator id="userIndicator"/>
                            <button id="logoutButton"
                                    action="logoutAction"
                                    classNames="jmix-logout-button"/>
                        </footer>
                    </section>
                </drawerLayout>
                <initialLayout/>
            </appLayout>
        </mainView>
    """.trimIndent() + "\n"

    private fun loginViewXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <view xmlns="http://jmix.io/schema/flowui/view"
              focusComponent="login"
              title="msg://LoginView.title">
            <layout justifyContent="CENTER"
                    alignItems="CENTER"
                    classNames="jmix-login-main-layout">
                <loginForm id="login"
                           rememberMeVisible="true"
                           forgotPasswordButtonVisible="false">
                    <form title="msg://loginForm.headerTitle"
                          username="msg://loginForm.username"
                          password="msg://loginForm.password"
                          rememberMe="msg://loginForm.rememberMe"
                          submit="msg://loginForm.submit"
                          forgotPassword="msg://loginForm.forgotPassword"/>
                    <errorMessage title="msg://loginForm.errorTitle"
                                  message="msg://loginForm.badCredentials"
                                  username="msg://loginForm.errorUsername"
                                  password="msg://loginForm.errorPassword"/>
                </loginForm>
            </layout>
        </view>
    """.trimIndent() + "\n"

    private fun welcomeViewXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <view xmlns="http://jmix.io/schema/flowui/view"
              title="msg://welcomeView.title">
            <layout classNames="welcome-view"
                    padding="true"
                    spacing="true">
                <h2 text="msg://welcomeView.heading"/>
                <span text="msg://welcomeView.description"
                      classNames="welcome-view-description"/>
            </layout>
        </view>
    """.trimIndent() + "\n"

    private fun localizedMessageFiles(
        request: JmixProjectTemplateRequest,
        prefix: String,
        resourceBase: String,
    ): List<GeneratedProjectFile> {
        val bundles = mutableListOf(
            file(prefix, "$resourceBase/messages.properties", baseMessages(request)),
            file(prefix, "$resourceBase/view/main/messages.properties", mainMessages(request)),
            file(prefix, "$resourceBase/view/login/messages.properties", loginMessages()),
            file(prefix, "$resourceBase/view/welcome/messages.properties", welcomeMessages()),
        )
        request.locales.map(::localeSuffix).distinct().forEach { locale ->
            bundles += file(
                prefix,
                "$resourceBase/messages_$locale.properties",
                baseMessages(request),
            )
            bundles += file(
                prefix,
                "$resourceBase/view/main/messages_$locale.properties",
                mainMessages(request),
            )
            bundles += file(
                prefix,
                "$resourceBase/view/login/messages_$locale.properties",
                loginMessages(),
            )
            bundles += file(
                prefix,
                "$resourceBase/view/welcome/messages_$locale.properties",
                welcomeMessages(),
            )
        }
        return bundles
    }

    private fun baseMessages(request: JmixProjectTemplateRequest): String = """
        applicationTitle=${request.projectName}
        menu.application.title=Application
        menu.workspace.title=Workspace
        menu.gettingStarted.title=Getting started
        ${request.basePackage}.entity/User=User
        ${request.basePackage}.entity/User.id=ID
        ${request.basePackage}.entity/User.username=Username
        ${request.basePackage}.entity/User.firstName=First name
        ${request.basePackage}.entity/User.lastName=Last name
        ${request.basePackage}.entity/User.email=Email
        ${request.basePackage}.entity/User.active=Active
        ${request.basePackage}.entity/User.timeZoneId=Time zone
    """.trimIndent() + "\n"

    private fun mainMessages(request: JmixProjectTemplateRequest): String = """
        MainView.title=${request.projectName}
        applicationTitle.text=${request.projectName}
        navigation.ariaLabel=Application navigation
        drawerToggle.ariaLabel=Toggle navigation
    """.trimIndent() + "\n"

    private fun loginMessages(): String = """
        LoginView.title=Sign in
        loginForm.headerTitle=Sign in
        loginForm.username=Username
        loginForm.password=Password
        loginForm.rememberMe=Remember me
        loginForm.submit=Sign in
        loginForm.forgotPassword=Forgot password
        loginForm.errorTitle=Login failed
        loginForm.badCredentials=Check the username and password and try again
        loginForm.errorUsername=Username is required
        loginForm.errorPassword=Password is required
    """.trimIndent() + "\n"

    private fun welcomeMessages(): String = """
        welcomeView.title=Welcome
        welcomeView.heading=Your Jmix application is ready
        welcomeView.description=Use Jmix Visual Workbench to model entities, views, security, workflows and integrations.
    """.trimIndent() + "\n"

    private fun themeJson(): String = """
        {
          "parent": "jmix-lumo"
        }
    """.trimIndent() + "\n"

    private fun themeStyles(): String = """
        @import url('./application.css');
        @import url('./view/main-view.css');
        @import url('./view/login-view.css');
    """.trimIndent() + "\n"

    private fun applicationThemeCss(): String = """
        html {
          --lumo-border-radius-m: 0.625rem;
          --lumo-primary-color: hsl(216, 88%, 48%);
          --lumo-primary-text-color: hsl(216, 88%, 42%);
        }

        .welcome-view {
          box-sizing: border-box;
          inline-size: min(100%, 64rem);
          margin-inline: auto;
        }

        .welcome-view-description {
          color: var(--lumo-secondary-text-color);
          max-inline-size: 48rem;
        }
    """.trimIndent() + "\n"

    private fun mainViewCss(): String = """
        .jmix-main-view-header {
          align-items: center;
          display: flex;
          gap: var(--lumo-space-s);
          min-inline-size: 0;
          padding-inline: var(--lumo-space-m);
        }

        .jmix-main-view-title {
          font-size: var(--lumo-font-size-l);
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .jmix-main-view-section {
          box-sizing: border-box;
          display: flex;
          flex-direction: column;
          block-size: 100%;
          inline-size: min(88vw, 20rem);
          min-inline-size: 15rem;
        }

        .jmix-main-view-navigation {
          flex: 1 1 auto;
          min-block-size: 0;
          overflow: auto;
        }

        .jmix-main-view-footer {
          align-items: center;
          display: flex;
          gap: var(--lumo-space-s);
          justify-content: space-between;
          padding: var(--lumo-space-s) var(--lumo-space-m);
        }

        @media (max-width: 40rem) {
          .jmix-main-view-header {
            padding-inline: var(--lumo-space-s);
          }

          .jmix-main-view-title {
            font-size: var(--lumo-font-size-m);
          }
        }
    """.trimIndent() + "\n"

    private fun loginViewCss(): String = """
        .jmix-login-main-layout {
          background:
            radial-gradient(circle at top right, var(--lumo-primary-color-10pct), transparent 42%),
            var(--lumo-base-color);
          box-sizing: border-box;
          min-block-size: 100%;
          padding: clamp(var(--lumo-space-m), 5vw, var(--lumo-space-xl));
        }

        .jmix-login-main-layout vaadin-login-form {
          inline-size: min(100%, 28rem);
        }
    """.trimIndent() + "\n"

    private fun localeSuffix(locale: String): String =
        locale.trim().replace('-', '_')

    private fun file(
        prefix: String,
        relativePath: String,
        content: String,
    ): GeneratedProjectFile = GeneratedProjectFile(
        relativePath = "$prefix$relativePath",
        content = content,
    )
}
