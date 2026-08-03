# Real database and Jmix runtime certification

This fixture starts the same Jmix domain model against five real database
engines and verifies:

- exact Jmix 2.8.2 and 3.0.0 application startup;
- Java 17, 21, and 25 runtime cells supported by each Jmix line. The Java 25
  cell compiles Jmix 3.0 application bytecode for Java 21 and executes it on
  Java 25, matching the framework enhancer's supported bytecode boundary while
  proving latest-JDK runtime compatibility;
- Jmix entity enhancement and metadata registration;
- `DataManager` save/load behavior, including `BigDecimal` values and
  relationships;
- Liquibase baseline application, primary keys, foreign keys, unique
  constraints, and indexes;
- an isolated forward migration, rollback, and reapplication in separate JVMs;
- database and JDBC driver versions in machine-readable evidence.
- explicit `OraclePlatform` selection on Oracle 23, avoiding the unsupported
  `Oracle23Platform` auto-detection path in the EclipseLink version shipped by
  Jmix 2.8; the selected platform is recorded in evidence;
- production plugin reverse engineering through both IntelliJ host lanes,
  including project-property resolution, project-library driver loading,
  catalog/schema browsing, PK/FK/index inspection, dependency closure, and
  database-first entity planning.

Run the complete matrix from this directory:

```bash
./run-matrix.sh
```

The script uses Docker Compose, waits for health checks, creates the isolated
SQL Server database idempotently, force-recreates the selected disposable
database containers so stale schemas and log history cannot mask defects, and
writes one evidence document per cell to `evidence/current`. Set
`CERT_DATABASES` to a comma-separated subset such as `postgres,oracle` for a
focused clean run; the default selects all five engines. A JDK 21 installation
launches the pinned Gradle 8.14.4 wrapper; the individual certification
processes use independently selected compile and runtime Java toolchains. Both
versions are recorded in every machine-readable runtime evidence document.
Each database/IntelliJ-host reverse-engineering execution also writes a
credential-free evidence document covering the production service,
project-property and project-library resolution, schema browsing, constraint
reconstruction, dependency closure, existing-entity reuse, import planning,
and response redaction.

The containers use only fixture credentials and isolated databases. No
production database or developer application data is read or changed. All
fixture passwords have reproducible local defaults and can be overridden with
the `CERT_POSTGRES_PASSWORD`, `CERT_MYSQL_PASSWORD`,
`CERT_MYSQL_ROOT_PASSWORD`, `CERT_MARIADB_PASSWORD`,
`CERT_MARIADB_ROOT_PASSWORD`, `CERT_MSSQL_PASSWORD`,
`CERT_ORACLE_ADMIN_PASSWORD`, and `CERT_ORACLE_PASSWORD` environment
variables. Host ports are bound to loopback only and can be overridden with
`CERT_POSTGRES_PORT`, `CERT_MYSQL_PORT`, `CERT_MARIADB_PORT`,
`CERT_MSSQL_PORT`, and `CERT_ORACLE_PORT`.

On a clean PostgreSQL container, the server log records an error-level
`SELECT COUNT(*) FROM public.databasechangeloglock` because Liquibase probes
for its lock table before creating it. This is expected only during bootstrap.
The certification fails unless every migration phase completes and the final
Liquibase lock row is released.
