# Organization Template Catalogs

Date: 2026-07-31

## Outcome

Jmix Visual Workbench supports organization-controlled project baselines without
requiring a Jmix subscription, a proprietary template runtime, or a network
connection during project creation.

The implementation deliberately exceeds a plain Maven-template override:

- every bundle is pinned by its complete SHA-256 digest;
- `catalog.json` is signed with an explicitly trusted Ed25519 key;
- every declared payload has an independent SHA-256 digest;
- catalog ID, exact version, minimum accepted version, signer and expiry are
  revalidated every time a cached bundle is opened;
- only declarative text/binary add, replace and delete operations are supported;
- bundle content is never executed by the importer or verifier;
- cached coordinates are immutable and content addressed;
- the New Project wizard performs no hidden network request;
- online refresh is explicit, bounded, HTTPS-only and refuses redirects,
  embedded credentials, query strings and fragments; and
- an administrator can import the exact signed bundle and use it completely
  offline.

## Native IntelliJ workflow

Open:

**Settings → Tools → Jmix Organization Catalogs**

Each catalog configuration contains:

- human-readable name;
- signed catalog ID and exact version;
- optional HTTPS bundle URL;
- pinned bundle SHA-256;
- signing key ID;
- Ed25519 X.509 public key in Base64; and
- optional minimum catalog version as an anti-rollback floor.

The settings page supports:

- enable/disable without deleting cached evidence;
- add, edit and remove;
- explicit signed-bundle import;
- explicit pinned HTTPS refresh; and
- global deterministic offline mode; and
- guided signed-bundle authoring from a customized certified project.

### Author a catalog natively

Select **Create Signed Bundle…** on the same settings page:

1. choose the customized project and enter the exact certified Jmix base that
   originally produced it;
2. enter catalog/template identity, version, compatibility and expiry;
3. choose transient Ed25519 PKCS#8/X.509 key files or an installed enterprise
   signing provider;
4. review every add, replace and delete in a sortable table with a side-by-side
   certified-base/customized-content inspector; and
5. sign and export a new `.jmix-template-catalog` file.

The scan never follows symbolic links. It ignores VCS, IDE, Gradle, build,
`node_modules`, `out` and operating-system metadata, rejects case collisions
and non-portable paths, bounds individual/total files and propagates
cancellation. Gradle wrapper resources must still match the bundled,
integrity-checked originals byte-for-byte, including executable state.

Immediately before signing, the plugin repeats the complete scan. If any
content, path or executable state differs from the reviewed source digest and
typed changes, export stops and requires a fresh review. The output write is
create-only and refuses an existing destination.

After a bundle is verified and cached, open **File → New → Project → Jmix**.
The **Project baseline** field shows only signed cached templates compatible
with the currently selected project type, Java/Kotlin language, headless/FlowUI
mode, Jmix version and Java runtime. Changing any of those selections updates
the list immediately.

The selected bundle and template metadata are reverified immediately before
generation. A cache change, key-policy change, expiry, incompatible selection
or stale wizard option fails closed; the wizard never silently falls back to a
different baseline.

## Bundle format

A `.jmix-template-catalog` file is a ZIP archive:

```text
catalog.json
catalog.ed25519
templates/
  acme-flowui/
    README.md
    .github/
      CODEOWNERS
```

`catalog.ed25519` contains the Base64 Ed25519 signature of the exact
`catalog.json` bytes. Each non-delete change in the manifest contains the
SHA-256 digest of its payload.

Project-only publishers emit schema version 2. Bundles containing declarative
connector policies emit schema version 3; see
`ORGANIZATION-CONNECTOR-CATALOGS.md`. Version 2 declares every non-delete
project payload as `TEXT` or `BINARY`; text is strict UTF-8 and may expand the
safe variables below, while binary bytes are preserved exactly and never
expanded. Legacy schema version 1 catalogs remain accepted as text-only
catalogs so existing immutable offline cache coordinates do not break during
upgrade.

Both schemas reject unknown properties. They also reject duplicate JSON
properties, duplicate or case-colliding paths and IDs, traversal, absolute
paths, symbolic-link bundles, non-portable Windows names, IDE/VCS/cache
internals at any project depth, undeclared archive entries, invalid text
payload UTF-8, oversized entries,
excessive entry counts and excessive compressed or expanded sizes.

Supported declarative operations are:

| Operation | Contract |
|---|---|
| `ADD` | Target must not exist in the certified base project |
| `REPLACE` | Target must already exist |
| `DELETE` | Target must already exist and has no payload |

Organization overlays may add or replace normal binary application assets.
Bundled plugin resources such as the integrity-checked Gradle wrapper scripts
and JAR remain protected and cannot be replaced or deleted.

## Safe variables

Text payloads may use this fixed allowlist:

- `${JMIX_PROJECT_NAME}`
- `${JMIX_GROUP_ID}`
- `${JMIX_ARTIFACT_ID}`
- `${JMIX_BASE_PACKAGE}`
- `${JMIX_BASE_PACKAGE_PATH}`
- `${JMIX_PROJECT_ID}`
- `${JMIX_VERSION}`
- `${JMIX_JAVA_VERSION}`
- `${JMIX_LANGUAGE}`
- `${JMIX_UI_KIND}`
- `${JMIX_LOCALES}`

Any other `${JMIX_*}` value fails generation. Repository credentials, signing
keys and environment secrets are intentionally unavailable to templates.

## Deterministic authoring

`JmixTemplateCatalogAuthoring` is the source-first publishing boundary for
organization build and release tooling. It:

1. accepts typed catalog, template, compatibility and text/binary file-change
   models;
2. copies mutable byte inputs and computes payload digests;
3. emits canonical ordered manifest content;
4. signs it with an in-memory PKCS#8 Ed25519 key;
5. writes a deterministic archive;
6. passes the result through the production verifier; and
7. offers create-only output that refuses replacement.

The local workflow reads PEM or DER PKCS#8/X.509 key files only for the signing
operation. The private key path and key are never persisted in plugin settings,
and signer objects redact private-key identity from their string rendering.
Organizations can instead register
`org.jmixworkbench.templateCatalogSigningProvider`. A provider receives only a
copy of the canonical manifest bytes and may delegate to PKCS#11, an HSM,
secure enclave or approved remote signing service. Duplicate provider IDs,
invalid identity metadata and invalid Ed25519 public keys fail closed. The
returned signature is immediately verified against the captured provider
identity through the production verifier.

The public verification key is not a secret but must be distributed through an
authenticated organization channel.

## Runtime evidence

The regular built-in template matrix contains 40 variants. A separate signed
organization-template matrix adds eight real FlowUI applications:

| Jmix | Runtime JDK | Languages | Result |
|---|---:|---|---|
| 2.8.2 | 17 | Java, Kotlin | PASS |
| 2.8.2 | 21 | Java, Kotlin | PASS |
| 3.0.0 | 21 | Java, Kotlin | PASS |
| 3.0.0 | 25 | Java, Kotlin | PASS |

For each cell, the production authoring boundary creates and signs a schema-v2
catalog containing Java/Kotlin text plus an exact binary asset; the production
verifier validates it; the compatible template is applied to the certified
FlowUI base; the normal create-only installer materializes the project; Gradle
compiles and tests it; Vaadin builds the production frontend; Liquibase runs;
and the Jmix application starts on the exact selected runtime.

The current schema-v2 text/binary eight-cell run passed on 2026-07-31 in
4 minutes 49 seconds.

## Remaining certification

The backend, guided native authoring/consumption workflows, offline behavior,
binary round trip and enterprise signing extension are implemented. Direct
dual-host component tests cover keyboard-discoverable actions, accessible
region names, sortable change inventory and the side-by-side review structure.
Before claiming the complete custom-template authoring row `STRONG`, installed
IDE keyboard, screen-reader, validation, cancellation and recovery journeys
still need certification under `CERT-ACCESS-001` and `CERT-IDE-001`.

## Research baseline

This design was checked against the official Jmix documentation for
[custom project templates](https://docs.jmix.io/jmix/2.3/studio/custom-project-templates.html),
[plugin settings](https://docs.jmix.io/jmix/studio/plugin-settings.html),
[new-project creation](https://docs.jmix.io/3.x/jmix/studio/project.html) and
[offline operation](https://docs.jmix.io/3.x/jmix/studio/install.html).
Compatibility with Studio's custom-template concept is retained, while signed
content, exact digest pinning, anti-rollback policy and explicit offline cache
verification are additional Workbench controls.
