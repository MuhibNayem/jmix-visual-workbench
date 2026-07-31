# OpenAPI Contract-First Clients

## Scope

The Integration Designer can bind an HTTP-based connector to an OpenAPI 3.0 or
3.1 operation stored in the IntelliJ project. The backend, not the browser,
owns contract parsing, schema normalization, operation resolution, security
evaluation and generated Java types.

The generated transport client can now be wrapped by a Jmix-facing layer in the
same visual workflow. That layer consists of Jmix DTO entities and enums,
explicit transport/entity mappers, and an application service that keeps
provider-owned transport types out of views and business logic.

Authoritative public references:

- [Jmix OpenAPI Client Generation](https://docs.jmix.io/jmix/2.8/studio/openapi-client.html)
- [Jmix OpenAPI integration guide](https://docs.jmix.io/jmix/2.7/openapi-integration-guide/index.html)
- [OpenAPI Specification 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)
- [Swagger Parser v3](https://github.com/swagger-api/swagger-parser)
- [Spring REST clients](https://docs.spring.io/spring-framework/reference/6.2/integration/rest-clients.html)

## Developer workflow

1. Open the Integration Designer in IntelliJ.
2. Select an indexed project contract or use **Choose project file…**.
3. Select an operation and supported request/success-response representations.
4. Review typed path, query, header and cookie parameters.
5. Configure an authentication alternative that satisfies the exact OpenAPI
   security requirement, including required OAuth scopes and mutual TLS.
6. Select **Generate Jmix layer** when application code must consume Jmix
   entities rather than provider transport records.
7. For every reachable object schema, generate a DTO entity or select an exact
   indexed existing entity, then review property direction, stable identifier
   and instance-name mappings.
8. Configure endpoint, credentials, SSL bundle and reliability policy through
   external properties.
9. Preview the connector, policy, DTOs, enums, mapper and application service as
   one immutable diff and apply them through the shared atomic workspace-change
   pipeline.

When a provider changes the selected contract, the existing connector is shown
as **Contract update · review required**, not as a generic manual-source
conflict. The designer then:

1. proves every generated source against the persisted old operation;
2. matches the operation by exact `operationId`, then exact method/path only
   when that fallback is unique;
3. presents separate wire and generated-source compatibility ratings;
4. lists operation, parameter, request, response, validation, enum and security
   changes, plus Jmix mappings that no longer have an exact identity;
5. presents bounded backend-ranked replacement schemas and property candidates;
6. requires an explicit carry-forward or new-target decision for every
   compatible previous Jmix target, prevents two targets from claiming the
   same current schema, and carries only exact property identities without a
   separate field decision;
7. exposes non-identity property candidates in the mapping table, where the
   developer chooses the retained Jmix property and direction;
8. shows the resulting schema, target and property decisions again in the
   native IntelliJ confirmation;
9. requires native IntelliJ approval before preview/apply; and
10. invalidates that approval immediately after any model or mapping edit.

The visual UI suggests contract-required authentication, but this is only a
convenience. Preview and apply always reconstruct and validate the requirement
in Kotlin.

## Trust and safety boundary

- Contracts must be project-owned `.json`, `.yaml` or `.yml` files under an
  IntelliJ content root.
- Documents are limited to 5 MiB. Discovery, operations, schemas, properties,
  nesting, representations and cache entries have explicit bounds.
- Parsing runs away from the IntelliJ event-dispatch thread.
- Parser reference resolution, external validation and network access are
  disabled. Only same-document `#/components/...` references are resolved by
  the workbench.
- Every binding records the project-relative path, SHA-256, specification
  version, method, path, operation ID and selected representations.
- Preview/apply reopen the file and reject stale digests, missing or ambiguous
  operations and changed representations.
- The transient normalized operation/schema graph is backend-derived and never
  trusted from the browser. A separate backend-issued semantic baseline is
  persisted solely as bounded comparison evidence (512 KiB maximum), is
  coordinate-checked against the exact binding, and is overwritten by current
  backend resolution before generation.
- Semantic evolution distinguishes HTTP/wire compatibility from generated Java
  and Jmix source compatibility. Optional parameter addition, for example, can
  be wire-compatible while still breaking existing Java callers.
- The analyzer covers operation identity, representations, typed parameters,
  recursive request/response graphs, schema/type identity, nullability,
  required/read-only/write-only properties, enum direction, security AND/OR
  alternatives and scopes, plus numeric, length, pattern, collection,
  uniqueness and `const` constraints (including merged `allOf` constraints).
- A five-minute native approval capability is bound to the connector source
  revision, old and new contract digests, deterministic semantic-report digest
  and the complete proposed mapping model. The capability is never persisted;
  edits, stale source, a new contract revision or changed mapping decisions
  fail closed.
- Remap candidates are calculated by the backend from the persisted baseline,
  current backend-resolved graph and previous Jmix mappings. Name and recursive
  shape evidence are ranked, bounded and disclosed, but non-identity evidence
  is never silently accepted. The final normalized mapping is independently
  type-checked and included in the native approval digest.
- Unsupported polymorphism, external references, arbitrary object parameters,
  media-type parameters, unsupported serialization styles, reserved
  characters, unsafe headers, form/multipart bodies and unproven message
  converters fail closed.
- OpenAPI security requirement objects preserve their AND semantics; separate
  objects preserve OR semantics. API-key location/name, HTTP auth kind, OAuth2
  flow/scopes, OpenID/bearer and mutual TLS are evaluated against the exact
  connector configuration.
- Generated headers are checked for ownership collisions across OpenAPI
  parameters, API-key auth, idempotency and configured headers.
- Existing generated connectors remain editable only when Java, policy,
  migration and every supplemental Jmix source regenerate byte-for-byte.
- Existing-entity targets are selected by backend-issued artifact ID, qualified
  name and exact source revision. Preview resolves attributes and types again
  from the schema index; browser-supplied entity shape is never trusted.
- Cross-module entity targets must be compile-visible through the indexed
  module-dependency graph. Missing, ambiguous, stale and inaccessible targets
  fail closed.
- Read-only target attributes, duplicate destinations, unsafe type conversion,
  missing required outbound mappings and unstable response DTO identity are
  rejected before source preview.
- Create, update and removal of all owned sources are one rollback-protected
  IntelliJ command. Injected partial-write tests prove that failure restores
  every byte and removes newly created directory topology.

## Generated contract

The adapter uses Spring `RestClient` with:

- exact HTTP method and path;
- encoded path expansion and query parameters;
- typed scalar/array parameters and exact enum wire values;
- explicit request/response media types;
- exact successful-status validation;
- `ResponseEntity` plus `ParameterizedTypeReference` for generic responses;
- Jackson 2 or Jackson 3 annotations selected from the owning module;
- immutable defensive copies for collections, maps and binary values;
- operation-specific nested records and enums with exact `@JsonProperty`
  names;
- the connector platform's timeout, retry, circuit-breaker, bulkhead,
  rate-limit, idempotency, OAuth2, mTLS and observability policies.

Generated operation and nested type names are collision-safe for Java keywords,
`Object` methods and the connector's enclosing class name.

## Generated Jmix layer

Reachable OpenAPI object graphs can produce:

- `@JmixEntity` DTO classes with `@JmixId`, `@InstanceName` and mandatory
  property metadata;
- `EnumClass<String>` enums that retain the exact provider wire identifier;
- nested object, list, set, UUID, temporal, decimal, floating-point and binary
  mappings with defensive copies where required;
- an explicit Spring mapper that creates instances through `Metadata` and marks
  inbound DTO entities not-new through `EntityStates` only after mapping;
- mappings to exact indexed Jmix DTO or persistent entity classes, including
  inherited attributes;
- a Spring application-service facade whose public request/response contract
  uses Jmix types and whose remote invocation is not incorrectly enclosed in a
  database transaction.

The mapper is deliberately explicit and dependency-free. It does not silently
add MapStruct or annotation-processor dependencies to an enterprise build.
OpenAPI maps are rejected because Jmix entity attributes do not support `Map`.
Unsupported conversions fail closed rather than emitting placeholder casts.

## Compatibility evidence

Production OpenAPI-generated source is part of
`certifyGeneratedCodeCompatibility` and compiles in these exact cells:

| Jmix | Target JDK | HTTP/JSON line |
| --- | --- | --- |
| 2.8.2 | 17 | Spring Boot 3 / Jackson 2 |
| 2.8.2 | 21 | Spring Boot 3 / Jackson 2 |
| 3.0.0 | 21 | Spring Boot 4 / Jackson 3 |
| 3.0.0 | 25 | Spring Boot 4 / Jackson 3 |

Focused parser/generator/workspace tests cover YAML and JSON, local references,
`allOf`, typed records/enums, exact status handling, stale documents, duplicate
operation IDs, external references, polymorphism, form serializers, unsupported
parameter/media serialization, security mismatch, Java name collisions,
generated and existing Jmix targets, read-only attributes, inbound/outbound
direction, source revision, complete create/reopen/update/remove round trips,
manual supplemental-source protection and injected partial-write rollback.
Evolution tests additionally cover distinct wire/source impact, validation
tightening, deterministic reports, exact current-operation recovery, forged
baseline replacement, stale generated ownership, native-capability scope and
expiry, post-approval tamper rejection, capability non-persistence and the full
create/change/review/approve/regenerate/reopen lifecycle. Remap tests prove
exact ranking, conservative rejection without evidence, renamed schema and
property candidates, retained DTO identity, generated mapper expressions and
the complete create/rename/remap/approve/preview lifecycle.

The clean `phase1Check` release gate on 2026-07-31 passed 412 regression tests
and 3 host smoke tests on each IntelliJ lane. Plugin Verifier reported both
artifacts compatible:

| IntelliJ host | Packaged ZIP SHA-256 |
| --- | --- |
| IU-253.28294.334 | `3412d280b5f377ff0c1b48563a5d1f8f1104cbf618172a34ddb08e5e8489480a` |
| IU-262.8665.258 | `bb1297b7eb53bd124136e9740053d37adee85c1d3ec8ee0db8d310b629b671bd` |

Both ZIPs contain the same verified web input digest:
`c785db7909168ea36794f07055601c4dc2a23f47524f6f6fda7bbc5f9f9fa4c0`.

Responsive browser evidence on 2026-07-31 measured the real Integration
Designer and the semantic-evolution workflow:

| Embedded viewport | Layout | Document/client width | Region right edge |
| --- | --- | --- | --- |
| 1280 px | Three columns | 1280 / 1280 | 1280 |
| 720 px | Continuous stack | 720 / 720 | 720 |
| 360 px | Continuous stack | 360 / 360 | 360 |

At 360 pixels, selecting the Payment Provider contract and generating its Jmix
layer rendered both object mappings, package/service controls and all property
directions without document/body overflow. Each 590-pixel mapping table remained
inside a 196-pixel keyboard-focusable local scroll region. Mapping targets and
directions have schema-qualified accessible names. Generate, undo and redo were
executed successfully at that width.

The changed-contract and explicit-remapping workflow also has zero global
overflow at 1280, 720 and 360 pixels. At 360 pixels the review chips, schema
decision, property suggestions and approval controls remain reachable without
being cut off; the 590-pixel mapping table stays inside a 196-pixel local scroll
region. The browser journey selected a renamed schema, carried `receiptId` and
`state` through two explicit property decisions, restored stable identifier and
instance-name metadata, reached revision-bound approval and enabled preview.
The console contained no warnings or errors.

## Deliberate remaining boundary

This milestone does not yet claim complete Jmix Studio OpenAPI parity. The
active remaining layers are:

- user-defined, versioned mapping converters and existing custom-enum adapters;
- Kotlin DTO/mapper/service generation for Kotlin-owned target source sets;
- controlled multi-file contract bundles and cross-operation shared models;
- provider/consumer contract suites and saved runtime scenarios;
- installed-IDE accessibility, memory/leak and large-contract performance
  certification.

Until those layers pass their own release gates, `STUDIO-ADV-001` remains
`SUBSTANTIAL`, not `STRONG`.
