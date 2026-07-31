# Generated integration runtime certification

This disposable lab executes production-generated connector sources inside
real Jmix applications. It is deliberately separate from unit and compile-only
tests.

The current matrix proves on Jmix 2.8/Java 17 and Jmix 3.0/Java 21:

- Spring can proxy the generated connectors and apply store-qualified
  transactions;
- generated Liquibase migrations create Kafka/RabbitMQ outbox and inbox
  schemas in PostgreSQL;
- Kafka acknowledgements and RabbitMQ correlated publisher confirms/returns
  advance events to `SENT`;
- Toxiproxy broker outages produce bounded retry state and recovery retains
  the stable event ID;
- strict per-key ordering stops behind a terminal event and permission-gated
  system replay restores the original order;
- corrupted payloads fail checksum validation without publishing;
- expired leases are reclaimed after a simulated dispatcher crash;
- low-cardinality Micrometer delivery counters are emitted;
- SFTP transfers preserve binary payloads, use a temporary remote name plus
  atomic rename, clean temporary files and reject path traversal;
- HTTP calls send externalized API-key and stable idempotency headers, enforce
  connect/read timeouts, perform one bounded retry after a transient `503`, and
  open a Resilience4j circuit breaker that fails fast without contacting the
  provider;
- the Jmix 2 cell uses the Resilience4j Spring Boot 3 integration and the Jmix 3
  cell uses its Spring Boot 4 integration;
- Kafka and RabbitMQ consumers persist message identity and payload checksum
  before invoking the selected application handler, commit the inbox record and
  handler database effect in one transaction, and acknowledge successful
  duplicates without invoking the handler twice;
- both brokers pass six inbound scenarios: identical redelivery, conflicting
  payload under the same message ID, transient handler failure with bounded
  retry, poison-message dead-letter routing, missing-ID quarantine, and
  permission-controlled replay;
- conflicting IDs never overwrite a completed payload, missing IDs receive a
  transport-derived non-forgeable quarantine identity, and replay and
  maintenance use separate Jmix specific permissions;
- the Jmix 3 cell proves the modular Spring Boot 4 Kafka runtime and the
  generator rejects a target that declares only `spring-kafka` without the
  required Boot 4 Kafka auto-configuration capability.

Run:

```bash
./run-matrix.sh
```

The script generates the connector Java and Liquibase resources directly from
the production generator, force-recreates disposable Docker services, and
writes credential-free JSON evidence to `evidence/current`. No production
endpoint, credential or developer database is used. A single cell can be
selected with `CERT_INTEGRATION_CELL=jmix28-jdk17` or
`CERT_INTEGRATION_CELL=jmix30-jdk21`.

This lab does not yet certify OAuth2 token refresh or mTLS rotation, multi-node
dispatcher/consumer contention, sustained load/soak behavior, every supported
database, remaining provider families, or installed-IDE runtime launching.
Those remain separate gates rather than being inferred from the passing
publisher, consumer and provider scenarios.
