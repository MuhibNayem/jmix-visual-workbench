# OpenAPI Contract-First Clients

## Scope

The Integration Designer can bind an HTTP-based connector to an OpenAPI 3.0 or
3.1 operation stored in the IntelliJ project. The backend, not the browser,
owns contract parsing, schema normalization, operation resolution, security
evaluation and generated Java types.

This milestone implements the contract-client foundation documented by Jmix:
client generation is the first layer, followed by Jmix entities/mappers and
application services. The latter two layers remain a separate active milestone
and are not implied by this document.

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
6. Configure endpoint, credentials, SSL bundle and reliability policy through
   external properties.
7. Preview the complete Java/configuration diff and apply it through the shared
   atomic workspace-change pipeline.

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
  migration and contract regeneration are byte-for-byte owned.

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

## Compatibility evidence

Production OpenAPI-generated source is part of
`certifyGeneratedCodeCompatibility` and compiles in these exact cells:

| Jmix | Target JDK | HTTP/JSON line |
| --- | --- | --- |
| 2.8.2 | 17 | Spring Boot 3 / Jackson 2 |
| 2.8.2 | 21 | Spring Boot 3 / Jackson 2 |
| 3.0.0 | 21 | Spring Boot 4 / Jackson 3 |
| 3.0.0 | 25 | Spring Boot 4 / Jackson 3 |

Focused parser/workspace tests cover YAML and JSON, local references, `allOf`,
typed records/enums, exact status handling, stale documents, duplicate
operation IDs, external references, polymorphism, form serializers, unsupported
parameter/media serialization, security mismatch and Java name collisions.

The clean `phase1Check` release gate on 2026-07-31 passed 396 regression tests
and 3 host smoke tests on each IntelliJ lane. Plugin Verifier reported both
artifacts compatible:

| IntelliJ host | Packaged ZIP SHA-256 |
| --- | --- |
| IU-253.28294.334 | `44c92b238a4bbf378235e922dcf1afaf8789b504bf7e1767fcc152863d6b4120` |
| IU-262.8665.258 | `977b567c0a1d820abc376a9b59eddfd7d5b9fa8ad8fee3c38fe971c9d742ed1f` |

Both ZIPs contain the same verified web input digest:
`3729dade3039c1d75f83fbb59d2f93df5213f2bc7979fca191e696e0934f53f0`.

Responsive browser evidence on 2026-07-31 measured the real Integration
Designer through its isolated iframe harness:

| Embedded viewport | Layout | Document/client width | Region right edge |
| --- | --- | --- | --- |
| 1200 px | Three columns | 1200 / 1200 | 1200 |
| 720 px | Continuous stack | 720 / 720 | 712 |
| 360 px | Continuous stack | 360 / 360 | 352 |

At 360 pixels, selecting the Payment Provider contract rendered the complete
operation, request/response representations, generated types, OAuth scope and
typed parameters. The OpenAPI region ended at 340 pixels; all seven visible
controls stayed within the 360-pixel viewport and document/body horizontal
overflow was zero. Direct application execution reported no application-origin
console errors.

## Deliberate remaining boundary

This foundation does not yet claim complete Jmix Studio OpenAPI parity. The
active next layers are:

- visual generation/customization of Jmix DTO entities;
- lossless mappings between transport models and new or existing Jmix entities;
- application-level services that expose Jmix entity types;
- safe regeneration and semantic breaking-change migration;
- controlled multi-file contract bundles;
- provider/consumer contract suites and saved runtime scenarios;
- installed-IDE accessibility and large-contract performance certification.

Until those layers pass their own release gates, `STUDIO-ADV-001` remains
`SUBSTANTIAL`, not `STRONG`.
