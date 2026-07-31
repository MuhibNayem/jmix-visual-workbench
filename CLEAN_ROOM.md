# Clean-Room Policy

Jmix Visual Workbench is an original compatibility project. Its behavior and
implementation must be derived from sources that contributors are permitted to
use, and every compatibility claim must remain traceable to public evidence.

## Allowed inputs

- Public Jmix documentation, schemas, release notes, examples, and issue reports.
- Publicly licensed Jmix framework source used in accordance with its license.
- Public IntelliJ Platform APIs and official JetBrains documentation.
- Independently created test fixtures and examples with documented provenance.
- Authorized, sanitized user reports or fixtures whose license permits the use.

## Prohibited inputs and conduct

Contributors must not:

- copy or adapt proprietary Jmix Studio code, assets, templates, protocols, or
  trade dress;
- use decompilation-derived behavior, private implementation details, leaked
  materials, or unauthorized screenshots as implementation specifications;
- reproduce a proprietary protocol by observing non-public internals;
- bypass a license, subscription, entitlement, technical protection, or feature
  limit;
- redistribute commercial add-on runtimes or claim that this project supplies
  functionality that requires a separately licensed runtime;
- submit material whose ownership or redistribution rights cannot be explained.

Compatibility tests may exercise public interfaces in an authorized environment,
but they must not extract proprietary implementation material. When the origin
of an idea is uncertain, stop and ask the maintainers before contributing it.

## Provenance record

Changes that implement compatibility behavior must cite the public
specification, documentation, issue, or openly licensed source that informed the
behavior. The pull request must identify the source and explain the independent
implementation. The contribution attestation in [CONTRIBUTING.md](CONTRIBUTING.md)
applies to every submission.

See [TRADEMARKS.md](TRADEMARKS.md) for descriptive trademark use and
[SECURITY.md](SECURITY.md) for private vulnerability reporting.
