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
- only declarative UTF-8 add, replace and delete operations are supported;
- bundle content is never executed by the importer or verifier;
- cached coordinates are immutable and content addressed;
- the New Project wizard performs no hidden network request;
- online refresh is explicit, bounded, HTTPS-only and refuses redirects,
  embedded credentials, query strings and fragments; and
- an administrator can import the exact signed bundle and use it completely
  offline.

## Native IntelliJ workflow

Open:

**Settings → Tools → Jmix Organization Templates**

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
- global deterministic offline mode.

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

Schema version 1 rejects unknown properties. It also rejects duplicate JSON
properties, duplicate or case-colliding paths and IDs, traversal, absolute
paths, symbolic-link bundles, non-portable Windows names, IDE/VCS/cache
internals, undeclared archive entries, invalid UTF-8, oversized entries,
excessive entry counts and excessive compressed or expanded sizes.

Supported declarative operations are:

| Operation | Contract |
|---|---|
| `ADD` | Target must not exist in the certified base project |
| `REPLACE` | Target must already exist |
| `DELETE` | Target must already exist and has no payload |

Bundled binary resources such as the integrity-checked Gradle wrapper JAR
cannot be replaced by an organization overlay.

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

1. accepts typed catalog, template, compatibility and file-change models;
2. computes payload digests;
3. emits canonical ordered manifest content;
4. signs it with an in-memory PKCS#8 Ed25519 key;
5. writes a deterministic archive;
6. passes the result through the production verifier; and
7. offers create-only output that refuses replacement.

The private signing key is never persisted in plugin settings. Organizations
can supply it transiently from their CI secret manager, hardware-backed signing
adapter or reviewed local release process. The public verification key is not a
secret but must be distributed through an authenticated organization channel.

## Runtime evidence

The regular built-in template matrix contains 40 variants. A separate signed
organization-template matrix adds eight real FlowUI applications:

| Jmix | Runtime JDK | Languages | Result |
|---|---:|---|---|
| 2.8.2 | 17 | Java, Kotlin | PASS |
| 2.8.2 | 21 | Java, Kotlin | PASS |
| 3.0.0 | 21 | Java, Kotlin | PASS |
| 3.0.0 | 25 | Java, Kotlin | PASS |

For each cell, the production authoring boundary creates and signs a catalog;
the production verifier validates it; the compatible template is applied to
the certified FlowUI base; the normal create-only installer materializes the
project; Gradle compiles and tests it; Vaadin builds the production frontend;
Liquibase runs; and the Jmix application starts on the exact selected runtime.

The final post-hardening eight-cell run passed on 2026-07-31 in 4 minutes 41
seconds.

## Remaining certification

The backend, native consumption workflow, offline behavior and generated
runtime matrix are complete. Before claiming the complete custom-template
authoring row `STRONG`, the product still needs:

- a guided native authoring/diff/export interface over the typed publisher;
- pluggable enterprise key-provider/HSM integration; and
- installed-IDE keyboard and screen-reader journeys for the settings and
  authoring dialogs.

## Research baseline

This design was checked against the official Jmix documentation for
[custom project templates](https://docs.jmix.io/jmix/2.3/studio/custom-project-templates.html),
[plugin settings](https://docs.jmix.io/jmix/studio/plugin-settings.html),
[new-project creation](https://docs.jmix.io/3.x/jmix/studio/project.html) and
[offline operation](https://docs.jmix.io/3.x/jmix/studio/install.html).
Compatibility with Studio's custom-template concept is retained, while signed
content, exact digest pinning, anti-rollback policy and explicit offline cache
verification are additional Workbench controls.
