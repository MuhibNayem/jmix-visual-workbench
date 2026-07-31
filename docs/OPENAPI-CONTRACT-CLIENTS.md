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
- The normalized operation/schema graph is backend-derived and removed from
  the persisted source marker.
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

The clean `phase1Check` release gate on 2026-07-31 passed 401 regression tests
and 3 host smoke tests on each IntelliJ lane. Plugin Verifier reported both
artifacts compatible:

| IntelliJ host | Packaged ZIP SHA-256 |
| --- | --- |
| IU-253.28294.334 | `3db844ff750ee8edcc6353738b095958dc4886414ba91401b3acec901931a915` |
| IU-262.8665.258 | `e19ded99d224d23ae3b6eb305e302815e87a5a20bcce4d8bcb383d672972b3a7` |

Both ZIPs contain the same verified web input digest:
`bcd9f56e7fed9dc290ee0bfad3130c3d6787f75fd93d62cf64b536ea1632c263`.

Responsive browser evidence on 2026-07-31 measured the real Integration
Designer through its isolated iframe harness:

| Embedded viewport | Layout | Document/client width | Region right edge |
| --- | --- | --- | --- |
| 1200 px | Three columns | 1200 / 1200 | 1200 |
| 720 px | Continuous stack | 720 / 720 | 712 |
| 360 px | Continuous stack | 360 / 360 | 352 |

At 360 pixels, selecting the Payment Provider contract and generating its Jmix
layer rendered both object mappings, package/service controls and all property
directions without document/body overflow. Each 590-pixel mapping table remained
inside a 196-pixel keyboard-focusable local scroll region. Mapping targets and
directions have schema-qualified accessible names. Generate, undo and redo were
executed successfully at that width.

## Deliberate remaining boundary

This milestone does not yet claim complete Jmix Studio OpenAPI parity. The
active remaining layers are:

- semantic contract diff and guided breaking-change migration;
- user-defined, versioned mapping converters and existing custom-enum adapters;
- Kotlin DTO/mapper/service generation for Kotlin-owned target source sets;
- controlled multi-file contract bundles and cross-operation shared models;
- provider/consumer contract suites and saved runtime scenarios;
- installed-IDE accessibility, memory/leak and large-contract performance
  certification.

Until those layers pass their own release gates, `STUDIO-ADV-001` remains
`SUBSTANTIAL`, not `STRONG`.
