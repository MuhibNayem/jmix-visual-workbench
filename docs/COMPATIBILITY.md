# Compatibility

Host compatibility and target-project compatibility are independent contracts.
An IntelliJ host JVM runs the plugin; a target-project JDK compiles a Jmix
application. One does not imply the other.

## IntelliJ host lanes

| Artifact | Descriptor range | Host JVM floor | Automated evidence |
| --- | --- | --- | --- |
| `jmix-visual-workbench-1.0.0-idea253.zip` | IU builds 253–261.* | JBR/Java 21 | Compiles and packages against exact IDEA Ultimate 2025.3; Plugin Verifier passed IU-253.28294.334 |
| `jmix-visual-workbench-1.0.0-idea262.zip` | IU build 262.* | JBR/Java 25 | Compiles and packages against exact IDEA Ultimate 2026.2 with explicit JCEF modules; Plugin Verifier passed IU-262.8665.258 |

The 253 descriptor range covers IntelliJ branches 253 through 261 on the
JBR/Java 21 host line. The 262 descriptor is a separate JBR/Java 25 artifact.
Both ZIPs contain the same plugin ID/version and frontend input digest.

Automated compilation, smoke, package-content, and Plugin Verifier checks are
green. Manual installation and workbench opening in the two minimum IDEs remain
pending; the host lanes are not presented as release-certified until that
checkpoint is recorded.

## Target Jmix project plan

Phase 1 does not certify any repository mutation. The initial fixture cells
planned for later adapter certification are:

| Jmix line | Target-project Java | Current mutation status |
| --- | --- | --- |
| Jmix 2.8.x | Java 17 and Java 21 | Non-certified and disabled for valuable repositories |
| Jmix 3.0.x | Java 21 and Java 25 | Non-certified and disabled for valuable repositories |

Newer JDKs that are officially compatible with a detected Jmix line remain a
read-only policy target until exact fixture evidence certifies an operation and
profile cell. Earlier Jmix 2.x, Jmix 1.x, CUBA-era, future, ambiguous, stale,
untrusted, or otherwise uncertified projects likewise must not receive write
capability.

The current prototype does not yet implement the Phase 2 compatibility registry
or safe read-only onboarding model. Existing generators and direct-write bridge
paths are non-certified prototypes and must not be used against a valuable
repository.
