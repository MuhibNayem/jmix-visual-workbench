# Database and Jmix runtime certification

Certification date: 2026-07-31

This document records the reproducible database/runtime milestone for Jmix
Visual Workbench. It certifies the behavior listed below; it does not imply
complete Jmix Studio parity or certify unrelated designers.

## Certified matrix

Every database was force-recreated from disposable storage before the final
run. Published ports were bound to loopback and all fixture credentials and
ports were environment-overridable.

| Database | Certified engine | Runtime JDBC driver evidence |
|---|---|---|
| PostgreSQL | 16.9 | PostgreSQL JDBC 42.7.11 |
| MySQL | 8.4.6 | MySQL Connector/J 9.7.0 |
| MariaDB | 11.4.8 | MariaDB Connector/J 3.5.8 |
| Microsoft SQL Server | 2022 CU20 / 16.00.4205 | Microsoft JDBC 12.10.2 and 13.2.1 |
| Oracle | 23ai Free 23.7 | Oracle JDBC 23.8 and 23.9 |

Each engine passed these four cells:

| Jmix | Compile Java | Runtime Java |
|---|---:|---:|
| 2.8.2 | 17 | 17 |
| 2.8.2 | 21 | 21 |
| 3.0.0 | 21 | 21 |
| 3.0.0 | 21 | 25 |

The Java 25 cell intentionally compiles Jmix 3 application bytecode for Java
21 and runs it on Java 25. This respects the enhancer's supported bytecode
boundary while proving current-JDK runtime execution.

## Runtime proof

The final run produced 60 runtime evidence documents: forward, rollback and
reapply evidence for 20 database/Jmix/Java cells. Every document proves:

- real Spring/Jmix application startup and Jmix entity enhancement;
- Jmix metadata registration;
- constrained `DataManager` save/load behavior with a `BigDecimal` field and a
  relationship;
- Liquibase baseline application;
- primary key, foreign key, unique constraint and index creation;
- an isolated forward change, rollback and reapplication in separate JVMs; and
- release of the Liquibase lock.

Oracle uses the explicit EclipseLink `OraclePlatform` on Jmix 2.8 because the
framework's EclipseLink version does not support Oracle 23 auto-detection. The
selected platform is part of the evidence rather than a hidden runtime
override.

## Production reverse-engineering proof

Ten additional evidence documents cover every database on both IntelliJ host
lanes: IDEA 2025.3 and IDEA 2026.2. They execute the production
`DatabaseReverseEngineeringService`, not a replacement metadata reader, and
prove:

- active project-property resolution;
- JDBC driver loading from project libraries;
- catalog/schema/table browsing;
- primary-key, foreign-key, unique-constraint and index reconstruction;
- recursive dependency closure;
- exact existing-entity reuse;
- database-first import planning; and
- response redaction.

The production driver loader is project-scoped, sorted and cached. It is
replaced when the synced project library URLs change and is closed on project
disposal. MySQL and Oracle driver-owned cleanup is executed before classloader
close to avoid retaining plugin/IDE classloaders through driver background
threads.

## Log audit

All five containers remained healthy after the matrix. PostgreSQL logs one
expected first-start error-level entry:

```text
relation "public.databasechangeloglock" does not exist
SELECT COUNT(*) FROM public.databasechangeloglock
```

This is Liquibase's initial existence probe on a clean database. Liquibase then
creates its metadata tables and completes successfully. A final direct query
proved one unlocked row (`locked = false`) and the two expected applied change
sets.

Other log warnings were image/bootstrap behavior: local-only PostgreSQL trust
initialization, MySQL container initialization and self-signed CA warnings,
MariaDB's container `io_uring` fallback, and one transient SQL Server health
probe while the `sa` password was still being initialized. No warning
corresponded to a failed certification cell.

## Reproduction and evidence boundary

Run the complete clean matrix with:

```bash
CERT_BUILD_JAVA_HOME=/path/to/jdk-21 \
  certification/database-runtime/run-matrix.sh
```

`CERT_DATABASES` can select a comma-separated subset for diagnosis. The default
selects all five engines. Runtime evidence is written to the ignored
`certification/database-runtime/evidence/current` directory so connection
details and ephemeral results cannot become release source by accident. The
committed runner validates that reverse-engineering evidence exists for every
selected engine and host lane.

The final evidence audit found:

- 70 JSON documents;
- 60 runtime documents and 10 reverse-engineering documents;
- 20 distinct runtime cells;
- 20 forward, 20 rollback and 20 reapply results;
- zero failed runtime, rollback, reapply or reverse-engineering assertions; and
- no JDBC URL or fixture password in any evidence document.

`CERT-DB-001` is therefore `STRONG` for the behavior advertised above.
Arbitrary customer-schema coverage, every vendor-specific type, destructive
production migrations, installed-IDE performance and the complete generated
change catalog remain tracked by their own parity and certification gates.

## Plugin release gate

After the final runner, evidence and documentation changes, the strict
`phase1Check` release gate passed in 7 minutes 24 seconds:

- 70 shared/core tests passed;
- 329 tests passed on IDEA 2025.3 and 329 passed on IDEA 2026.2, with zero
  failures or errors;
- both host smoke suites passed;
- both host ZIP content and no-bundled-Kotlin-runtime checks passed;
- Plugin Verifier reported both ZIPs compatible;
- IDEA 2025.3 reported 8 deprecated and 17 experimental API usages;
- IDEA 2026.2 reported 8 deprecated and 12 experimental API usages; and
- strict dependency verification, generated-code compatibility, mutation/index
  architecture guards and the self-managed Node/web bundle gate passed.

Reproducible ZIP SHA-256 values:

```text
idea253 8bd52c3e948b7645847bbf67a1ec0869ded228b1075bc8dcc6cdc987d0da63a9
idea262 bec75aa235bea89caa36f13287e04baed9ff20a5e7c4d0429e5ea4df11b8493d
```
