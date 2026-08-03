# Organization Connector Catalogs

Date: 2026-07-31

## Outcome

Jmix Visual Workbench supports organization-governed connector presets without
shipping executable catalog code or exposing endpoint, credential, certificate
or private-key values to a catalog.

An organization connector catalog is:

- declarative;
- exactly versioned;
- Ed25519 signed;
- pinned by the complete bundle SHA-256;
- reverified whenever its immutable cache coordinate is opened;
- compatible only with explicitly listed Spring Boot APIs and indexed module
  capabilities;
- enforced again by the native backend before preview and apply; and
- optionally protected by a short-lived native IntelliJ approval capability.

Schema versions 1 and 2 remain valid for existing project-template catalogs.
Schema version 3 adds an optional `connectors` inventory. A schema-3 bundle may
contain project templates, connector templates, or both.

## Native IntelliJ administration

Open:

**Settings → Tools → Jmix Organization Catalogs**

The native settings page manages project and connector catalogs through the
same trust boundary:

- trusted Ed25519 key ID and X.509 public key;
- exact catalog ID and version;
- optional semantic anti-rollback floor;
- expected bundle SHA-256;
- optional credential-free HTTPS source;
- explicit refresh or signed local import;
- enable/disable without deleting recovery evidence; and
- deterministic offline mode.

Opening a project or Integration Designer never performs a hidden network
request. Only an administrator-triggered refresh can access the configured
HTTPS URL. Redirects, URL credentials, query strings and fragments are
rejected.

### Native connector authoring

Select **Create Signed Connector Catalog…** to author one reviewed connector
template. The form covers:

- catalog/template identity, version, display order, provider and expiry;
- HTTP, webhook, Kafka, RabbitMQ, SFTP, Jmix email/file storage, object
  storage, SMS, payment and identity connector kinds;
- Spring Boot 3/Jmix 2 and Spring Boot 4/Jmix 3 compatibility;
- required indexed dependencies/capabilities;
- externalized configuration and address property suffixes;
- required headers and whether their values are sensitive;
- authentication and mutual-TLS requirements;
- transaction, idempotency, outbox and inbox requirements;
- maximum connection/request timeouts and minimum retry attempts;
- metrics, tracing, structured logging, audit and observability API policy;
- standard, sensitive or restricted risk classification;
- native approval policy identity;
- transient PKCS#8/X.509 Ed25519 files or an installed HSM/PKCS#11/remote
  signing provider; and
- create-only `.jmix-connector-catalog` export.

Before signing, a separate native review dialog displays the complete immutable
compatibility and policy contract. Signing produces a deterministic archive,
passes it through the production verifier and refuses to overwrite an existing
target.

## Catalog contract

Each connector template contains:

```text
identity
  id, version, name, provider, kind
compatibility
  springBootApis, requiredCapabilities
externalized configuration
  configurationPrefixSuffix, addressPropertySuffix, headers
policy
  risk, approvalPolicyId, authentication, mTLS
  transaction, idempotency, outbox, inbox
  timeout ceilings, retry floor
  metrics, tracing, structured logging, audit, observability API
```

The schema intentionally has no field for:

- executable generator code or scripts;
- Java/Kotlin source;
- endpoint URLs, broker addresses or filesystem locations;
- passwords, tokens, API keys or client secrets;
- certificate, private-key or trust-store bytes; or
- a browser-controlled security override.

Unknown fields fail parsing before signature acceptance. Required headers carry
only a property suffix and sensitivity classification; their values remain
external configuration. Authentication secrets remain in the target
application's property/secret-provider chain. Mutual TLS refers only to a named
Spring Boot SSL-bundle property.

## Backend enforcement

Selecting a catalog card in the responsive Integration Designer creates a
model bound to:

```text
catalog ID + exact catalog version + complete bundle SHA-256
+ connector template ID + exact template version
```

The browser cannot submit catalog policy as authority. For every preview or
apply, the native backend:

1. reopens the configured immutable cache coordinate;
2. reverifies catalog identity, exact/minimum version, expiry, signing key,
   signature and complete bundle digest;
3. resolves the exact connector template/version;
4. verifies target Spring Boot API and indexed dependencies;
5. compares every submitted connector control to the signed policy; and
6. rejects any downgrade before reaching the shared workspace mutation
   boundary.

Downgrade checks cover connector kind, configuration/address suffix, headers
and sensitivity, authentication, mTLS, timeout ceilings, retry floor,
transaction, idempotency, outbox, inbox, metrics, tracing, structured logging,
audit and observability API.

Generated source retains the immutable catalog coordinates for audit and
round-trip discovery. It never persists the native approval capability.

## Sensitive and restricted approval

Sensitive and restricted templates require explicit approval in a native
IntelliJ warning dialog. The browser can request review but cannot mint or
choose the capability.

The backend-issued random capability:

- lives only in project-service memory;
- expires after five minutes;
- is bound to the exact catalog, version, digest, template and template
  version;
- is bound to the exact destination module;
- is bound to the signed approval-policy ID; and
- is removed from generated metadata.

Unknown, expired, wrong-module, wrong-template, wrong-policy and wrong-digest
capabilities fail closed. Standard-risk templates cannot mint an approval
capability.

## Responsive workflow

The signed organization catalog is shown above built-in adapters in the
permanent catalog/canvas/inspector Integration Designer. Compatible templates
are filtered using the selected module's indexed runtime.

Browser measurements on 2026-07-31 proved:

| Viewport | Layout | Horizontal overflow | Catalog/approval |
|---|---|---:|---|
| 1200 × 720 | Three simultaneous columns | 0 px | Visible |
| 720 × 900 | Continuous three-region stack | 0 px | Visible |
| 360 × 800 | Narrow continuous stack | 0 px | Visible |

The 360-pixel layout retained the signed card, catalog coordinates, risk badge,
vertical connector-flow diagram and native approval action. The browser console
reported no errors.

## Verification evidence

The clean release gate passed:

| Host | Regression | Smoke | Plugin Verifier | ZIP SHA-256 |
|---|---:|---:|---|---|
| IDEA 2025.3 | 389 passed | 3 passed | Compatible | `81e5103e740f7dab3a89a39720aa8356e74ea15bff1a0a86d55b5ab1f132fd40` |
| IDEA 2026.2 | 389 passed | 3 passed | Compatible | `5fd06a4fed3925659d79a989418a083646e5e112003196f2f9165d8a94427044` |

Both ZIPs contain web input SHA-256
`498eea9bd584a158abc5995d8bbd35ee16ead82aa5853fc62931f181d3cc1d6b`.
The gate also passed the four generated Java/Kotlin Jmix/JDK compatibility
cells, mutation/index architecture checks, strict dependency verification,
Kotlin-runtime exclusion and nested package inspection.

Focused tests additionally prove:

- deterministic authoring and production self-verification;
- no executable/payload entries in connector-only catalogs;
- tampered policy/signature rejection;
- forbidden endpoint and secret-value field rejection;
- immutable content-addressed cache resolution;
- exact-version and digest binding;
- version-floor anti-rollback;
- signing-key rotation rejection until trust configuration changes;
- disabled/offline catalog behavior;
- compatibility and invalid outbox/inbox/authentication combinations;
- complete policy-downgrade detection;
- exact module/template/digest approval scope;
- approval expiration and invalid replay; and
- approval-capability removal from generated markers.

## Remaining integration scope

This completes `SURPASS-INT-003`. It does not complete the entire Integration
Designer program. `SURPASS-INT-001`, `SURPASS-INT-002` and
`SURPASS-SIM-001` still track protocol import/mapping breadth, remaining
provider runtime fixtures, authorization-code and RFC 8705 flows,
cross-database inbox/outbox runtime, multi-node/load/soak behavior, installed
IDE runtime interaction and visual failure-scenario authoring.

## Research baseline

The design aligns with Jmix's documented
[add-on marketplace workflow](https://docs.jmix.io/jmix/2.0/studio/marketplace.html)
and [BOM-based add-on compatibility](https://docs.jmix.io/jmix/2.0/publish-add-on.html).
Its fail-closed signed-bundle behavior follows the same security principle as
[OPA signed bundles](https://www.openpolicyagent.org/docs/management-bundles):
verify the trusted signature and declared content before activation, and retain
the prior valid state when a replacement is invalid.
