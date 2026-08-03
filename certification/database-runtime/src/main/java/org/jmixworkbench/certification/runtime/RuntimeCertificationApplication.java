package org.jmixworkbench.certification.runtime;

import io.jmix.core.DataManager;
import io.jmix.core.JmixModules;
import io.jmix.core.Metadata;
import io.jmix.core.Resources;
import io.jmix.core.annotation.JmixModule;
import io.jmix.data.impl.JmixEntityManagerFactoryBean;
import io.jmix.data.persistence.DbmsSpecifics;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jmixworkbench.certification.runtime.entity.LoanApplication;
import org.jmixworkbench.certification.runtime.entity.OrgUnit;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@org.springframework.boot.autoconfigure.SpringBootApplication
@JmixModule(id = "org.jmixworkbench.certification.runtime")
public class RuntimeCertificationApplication {

    @Bean
    DataSource dataSource() {
        RuntimeTarget target = RuntimeTarget.fromEnvironment();
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName("JmixDatabaseCertification");
        configuration.setJdbcUrl(target.url());
        configuration.setUsername(target.username());
        configuration.setPassword(target.password());
        configuration.setDriverClassName(target.driver());
        configuration.setMaximumPoolSize(4);
        configuration.setMinimumIdle(1);
        return new HikariDataSource(configuration);
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource dataSource,
            JpaVendorAdapter jpaVendorAdapter,
            DbmsSpecifics dbmsSpecifics,
            JmixModules jmixModules,
            Resources resources
    ) {
        JmixEntityManagerFactoryBean factory = new JmixEntityManagerFactoryBean(
                "main",
                dataSource,
                jpaVendorAdapter,
                dbmsSpecifics,
                jmixModules,
                resources);
        factory.getJpaPropertyMap().put(
                "eclipselink.target-database",
                System.getProperty("cert.eclipselink.target-database"));
        return factory;
    }

    public static void main(String[] args) throws Exception {
        RuntimeTarget target = RuntimeTarget.fromEnvironment();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.web-application-type", "none");
        properties.put("spring.jmx.enabled", "false");
        properties.put("logging.level.root", "WARN");
        properties.put("logging.level.io.jmix", "WARN");
        properties.put("main.datasource.url", target.url());
        properties.put("main.datasource.username", target.username());
        properties.put("main.datasource.password", target.password());
        properties.put("main.datasource.driver-class-name", target.driver());
        properties.put("main.liquibase.change-log",
                "org/jmixworkbench/certification/runtime/liquibase/changelog.xml");
        properties.put("jmix.core.available-locales", "en");

        long startedAt = System.nanoTime();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                RuntimeCertificationApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run(args)) {
            CertificationResult result = certify(context, target, startedAt);
            result.write(target.phaseEvidenceFile());
            System.out.println("JVW_DATABASE_CERTIFICATION_OK " + result.toJson());
        }
    }

    private static CertificationResult certify(
            ConfigurableApplicationContext context,
            RuntimeTarget target,
            long startedAt
    ) throws Exception {
        Metadata metadata = context.getBean(Metadata.class);
        DataManager dataManager = context.getBean(DataManager.class);
        DataSource dataSource = context.getBean("dataSource", DataSource.class);

        require(metadata.getClass(OrgUnit.class).getName().endsWith("OrgUnit"),
                "Jmix metadata did not register OrgUnit");
        require(metadata.getClass(LoanApplication.class).getName().endsWith("LoanApplication"),
                "Jmix metadata did not register LoanApplication");

        DatabaseFacts baseline = inspect(dataSource, target);
        require(baseline.orgUnitTable(), "Jmix startup did not apply the OrgUnit migration");
        require(baseline.loanTable(), "Jmix startup did not apply the LoanApplication migration");
        require(baseline.loanForeignKey(), "Baseline foreign key is missing");
        require(baseline.loanUniqueConstraint(), "Baseline unique constraint is missing");
        require(baseline.loanIndex(), "Baseline relationship index is missing");
        require(baseline.baselineChangeSet(), "Jmix startup did not record the baseline changeset");
        require(baseline.liquibaseLockReleased(), "Liquibase left the database migration lock held");

        String runSuffix = Long.toUnsignedString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        OrgUnit orgUnit = dataManager.create(OrgUnit.class);
        orgUnit.setId(Math.abs(System.nanoTime()));
        orgUnit.setCode("ORG-" + runSuffix);
        orgUnit.setName("Runtime certification");
        dataManager.save(orgUnit);

        LoanApplication loan = dataManager.create(LoanApplication.class);
        loan.setId(Math.abs(System.nanoTime()));
        loan.setApplicationNo("APP-" + runSuffix);
        loan.setAmount(new BigDecimal("125000.75"));
        loan.setStatus("APPROVED");
        loan.setOrgUnit(orgUnit);
        dataManager.save(loan);

        LoanApplication loaded = dataManager.load(LoanApplication.class)
                .id(loan.getId())
                .one();
        require(loaded.getApplicationNo().equals(loan.getApplicationNo()),
                "DataManager load returned the wrong application");
        require(loaded.getAmount().compareTo(loan.getAmount()) == 0,
                "DataManager did not preserve the decimal amount");

        boolean rollbackVerified = false;
        boolean reapplyVerified = false;
        switch (target.phase()) {
            case "forward" -> {
                updateRoundTrip(dataSource);
                require(tableExists(dataSource, target, "JVW_LOAN_DOCUMENT"),
                        "Liquibase forward migration did not create JVW_LOAN_DOCUMENT");
                DatabaseFacts forward = inspect(dataSource, target);
                require(forward.roundTripChangeSet(),
                        "Liquibase did not record the forward changeset");
            }
            case "rollback" -> {
                require(baseline.roundTripChangeSet(),
                        "Rollback phase did not observe the forward changeset");
                require(tableExists(dataSource, target, "JVW_LOAN_DOCUMENT"),
                        "Rollback phase did not observe JVW_LOAN_DOCUMENT");
                rollbackRoundTrip(dataSource);
                require(!tableExists(dataSource, target, "JVW_LOAN_DOCUMENT"),
                        "Liquibase rollback did not remove JVW_LOAN_DOCUMENT");
                require(!inspect(dataSource, target).roundTripChangeSet(),
                        "Liquibase rollback did not remove the history row");
                rollbackVerified = true;
            }
            case "reapply" -> {
                require(!baseline.roundTripChangeSet(),
                        "Reapply phase expected the rollback history row to be absent");
                require(!tableExists(dataSource, target, "JVW_LOAN_DOCUMENT"),
                        "Reapply phase expected JVW_LOAN_DOCUMENT to be absent");
                updateRoundTrip(dataSource);
                require(tableExists(dataSource, target, "JVW_LOAN_DOCUMENT"),
                        "Liquibase reapply did not recreate JVW_LOAN_DOCUMENT");
                require(inspect(dataSource, target).roundTripChangeSet(),
                        "Liquibase did not record the reapplied changeset");
                rollbackVerified = true;
                reapplyVerified = true;
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported certification phase " + target.phase());
        }

        DatabaseFacts completed = inspect(dataSource, target);
        require(completed.liquibaseLockReleased(),
                "Liquibase left the database migration lock held after " + target.phase());
        return new CertificationResult(
                target.databaseId(),
                target.phase(),
                System.getProperty("cert.jmix.version"),
                Integer.parseInt(System.getProperty("cert.compile.java.version")),
                Runtime.version().feature(),
                System.getProperty("cert.eclipselink.target-database"),
                completed.productName(),
                completed.productVersion(),
                completed.driverName(),
                completed.driverVersion(),
                (System.nanoTime() - startedAt) / 1_000_000L,
                true,
                true,
                true,
                true,
                rollbackVerified,
                reapplyVerified
        );
    }

    private static void updateRoundTrip(DataSource dataSource) throws Exception {
        withLiquibase(dataSource, liquibase ->
                liquibase.update(new Contexts()));
    }

    private static void rollbackRoundTrip(DataSource dataSource) throws Exception {
        withLiquibase(dataSource, liquibase ->
                liquibase.rollback(1, ""));
    }

    private static void withLiquibase(
            DataSource dataSource,
            LiquibaseOperation operation
    ) throws Exception {
        try (Connection connection = dataSource.getConnection();
             ClassLoaderResourceAccessor accessor = new ClassLoaderResourceAccessor(
                     RuntimeCertificationApplication.class.getClassLoader())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "org/jmixworkbench/certification/runtime/liquibase/roundtrip.xml",
                    accessor,
                    database)) {
                operation.run(liquibase);
            }
        }
    }

    private static DatabaseFacts inspect(
            DataSource dataSource,
            RuntimeTarget target
    ) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            return new DatabaseFacts(
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion(),
                    metadata.getDriverName(),
                    metadata.getDriverVersion(),
                    tableExists(metadata, connection, target, "JVW_ORG_UNIT"),
                    tableExists(metadata, connection, target, "JVW_LOAN_APPLICATION"),
                    hasImportedKey(metadata, connection, target, "JVW_LOAN_APPLICATION",
                            "ORG_UNIT_ID", "JVW_ORG_UNIT"),
                    hasUniqueIndex(metadata, connection, target,
                            "JVW_LOAN_APPLICATION", "APPLICATION_NO"),
                    hasIndex(metadata, connection, target,
                            "JVW_LOAN_APPLICATION", "ORG_UNIT_ID"),
                    changeSetExists(connection, "jvw-runtime-baseline"),
                    changeSetExists(connection, "jvw-runtime-roundtrip"),
                    liquibaseLockReleased(connection)
            );
        }
    }

    private static boolean tableExists(
            DataSource dataSource,
            RuntimeTarget target,
            String tableName
    ) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return tableExists(connection.getMetaData(), connection, target, tableName);
        }
    }

    private static boolean tableExists(
            DatabaseMetaData metadata,
            Connection connection,
            RuntimeTarget target,
            String tableName
    ) throws Exception {
        for (String candidate : identifierCandidates(tableName)) {
            try (ResultSet rows = metadata.getTables(
                    connection.getCatalog(),
                    target.schemaPattern(),
                    candidate,
                    new String[]{"TABLE"})) {
                while (rows.next()) {
                    if (tableName.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasImportedKey(
            DatabaseMetaData metadata,
            Connection connection,
            RuntimeTarget target,
            String tableName,
            String columnName,
            String referencedTable
    ) throws Exception {
        for (String tableCandidate : identifierCandidates(tableName)) {
            try (ResultSet rows = metadata.getImportedKeys(
                    connection.getCatalog(), target.schemaPattern(), tableCandidate)) {
                while (rows.next()) {
                    if (columnName.equalsIgnoreCase(rows.getString("FKCOLUMN_NAME"))
                            && referencedTable.equalsIgnoreCase(rows.getString("PKTABLE_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasUniqueIndex(
            DatabaseMetaData metadata,
            Connection connection,
            RuntimeTarget target,
            String tableName,
            String columnName
    ) throws Exception {
        return findIndex(metadata, connection, target, tableName, columnName, true);
    }

    private static boolean hasIndex(
            DatabaseMetaData metadata,
            Connection connection,
            RuntimeTarget target,
            String tableName,
            String columnName
    ) throws Exception {
        return findIndex(metadata, connection, target, tableName, columnName, false);
    }

    private static boolean findIndex(
            DatabaseMetaData metadata,
            Connection connection,
            RuntimeTarget target,
            String tableName,
            String columnName,
            boolean uniqueOnly
    ) throws Exception {
        for (String tableCandidate : identifierCandidates(tableName)) {
            try (ResultSet rows = metadata.getIndexInfo(
                    connection.getCatalog(), target.schemaPattern(), tableCandidate,
                    uniqueOnly, false)) {
                while (rows.next()) {
                    String indexedColumn = rows.getString("COLUMN_NAME");
                    if (indexedColumn != null
                            && columnName.equalsIgnoreCase(indexedColumn)
                            && (!uniqueOnly || !rows.getBoolean("NON_UNIQUE"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean changeSetExists(Connection connection, String id) throws Exception {
        try (var statement = connection.prepareStatement(
                "select count(*) from DATABASECHANGELOG where ID = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private static boolean liquibaseLockReleased(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement(
                "select LOCKED from DATABASECHANGELOGLOCK where ID = 1");
             ResultSet rows = statement.executeQuery()) {
            return rows.next() && !rows.getBoolean(1);
        }
    }

    private static String[] identifierCandidates(String name) {
        return new String[]{name, name.toLowerCase(Locale.ROOT), name.toUpperCase(Locale.ROOT)};
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    @FunctionalInterface
    private interface LiquibaseOperation {
        void run(Liquibase liquibase) throws Exception;
    }

    private record RuntimeTarget(
            String databaseId,
            String url,
            String username,
            String password,
            String driver,
            String schemaPattern,
            String phase,
            Path evidenceFile
    ) {
        static RuntimeTarget fromEnvironment() {
            String databaseId = required("CERT_DB_ID");
            String schema = System.getenv("CERT_DB_SCHEMA");
            String evidence = required("CERT_EVIDENCE_FILE");
            return new RuntimeTarget(
                    databaseId,
                    required("CERT_DB_URL"),
                    required("CERT_DB_USERNAME"),
                    required("CERT_DB_PASSWORD"),
                    required("CERT_DB_DRIVER"),
                    schema == null || schema.isBlank() ? null : schema,
                    required("CERT_PHASE").toLowerCase(Locale.ROOT),
                    Path.of(evidence)
            );
        }

        Path phaseEvidenceFile() {
            if ("reapply".equals(phase)) {
                return evidenceFile;
            }
            String fileName = evidenceFile.getFileName().toString();
            int extension = fileName.lastIndexOf('.');
            String phasedName = extension > 0
                    ? fileName.substring(0, extension) + "-" + phase + fileName.substring(extension)
                    : fileName + "-" + phase;
            return evidenceFile.resolveSibling(phasedName);
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required environment variable " + name);
            }
            return value;
        }
    }

    private record DatabaseFacts(
            String productName,
            String productVersion,
            String driverName,
            String driverVersion,
            boolean orgUnitTable,
            boolean loanTable,
            boolean loanForeignKey,
            boolean loanUniqueConstraint,
            boolean loanIndex,
            boolean baselineChangeSet,
            boolean roundTripChangeSet,
            boolean liquibaseLockReleased
    ) {
    }

    private record CertificationResult(
            String database,
            String phase,
            String jmixVersion,
            int compileJavaVersion,
            int runtimeJavaVersion,
            String eclipseLinkTargetDatabase,
            String databaseProduct,
            String databaseVersion,
            String driver,
            String driverVersion,
            long startupAndCertificationMillis,
            boolean jmixStartup,
            boolean dataManagerRoundTrip,
            boolean baselineMigration,
            boolean liquibaseLockReleased,
            boolean migrationRollback,
            boolean migrationReapply
    ) {
        void write(Path output) throws Exception {
            Files.createDirectories(output.getParent());
            Files.writeString(output, toJson() + System.lineSeparator(), StandardCharsets.UTF_8);
        }

        String toJson() {
            return "{"
                    + json("database", database) + ","
                    + json("phase", phase) + ","
                    + json("jmixVersion", jmixVersion) + ","
                    + "\"compileJavaVersion\":" + compileJavaVersion + ","
                    + "\"runtimeJavaVersion\":" + runtimeJavaVersion + ","
                    + json("eclipseLinkTargetDatabase", eclipseLinkTargetDatabase) + ","
                    + json("databaseProduct", databaseProduct) + ","
                    + json("databaseVersion", databaseVersion) + ","
                    + json("driver", driver) + ","
                    + json("driverVersion", driverVersion) + ","
                    + "\"startupAndCertificationMillis\":" + startupAndCertificationMillis + ","
                    + "\"jmixStartup\":" + jmixStartup + ","
                    + "\"dataManagerRoundTrip\":" + dataManagerRoundTrip + ","
                    + "\"baselineMigration\":" + baselineMigration + ","
                    + "\"liquibaseLockReleased\":" + liquibaseLockReleased + ","
                    + "\"migrationRollback\":" + migrationRollback + ","
                    + "\"migrationReapply\":" + migrationReapply
                    + "}";
        }

        private static String json(String name, String value) {
            return "\"" + escape(name) + "\":\"" + escape(value) + "\"";
        }

        private static String escape(String value) {
            StringBuilder escaped = new StringBuilder(value.length() + 16);
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\b' -> escaped.append("\\b");
                    case '\f' -> escaped.append("\\f");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) character));
                        } else {
                            escaped.append(character);
                        }
                    }
                }
            }
            return escaped.toString();
        }
    }
}
