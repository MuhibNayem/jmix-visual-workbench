# Native Service, Event and Configuration Intelligence

This milestone extends the source-backed IntelliJ semantic layer beyond
FlowUI, entities, menus and security. It targets runtime contracts that are
easy to break with ordinary text editing in a multi-module Jmix application.

## Jmix REST service descriptors

For Jmix `rest-services.xml`, the plugin provides:

- indexed Java and Kotlin Spring bean completion and navigation;
- `@RestService`, stereotype bean and Java/Kotlin `@Bean` factory-product
  discovery, including explicit aliases;
- public method and overload completion with signatures;
- overload resolution by method name, parameter count and optional JVM type;
- positional navigation from public XML payload names to source parameters;
- Find Usages and safe rename for coupled bean, method, parameter and class
  type declarations;
- preservation of intentionally different public payload aliases;
- JVM-aware Kotlin primitive, nullable primitive, collection, array and vararg
  type handling;
- diagnostics for unresolved or ambiguous beans/methods, non-public methods,
  wrong arity/types, duplicate service/method mappings and duplicate public
  parameter names;
- bounded nearest-symbol quick fixes.

The behavior follows the public Jmix
[Services API documentation](https://docs.jmix.io/jmix/2.7/rest/business-logic.html)
and
[`RestServicesConfiguration` contract](https://docs.jmix.io/api/3.0/io/jmix/rest/impl/config/RestServicesConfiguration.html).
XML parameter names are request-contract names, not an assertion that the
source parameter has identical spelling. Parameter types may be omitted unless
same-arity overloads need disambiguation.

## Configuration resources

The properties-language integration recognizes the canonical and relaxed
binding forms of:

- `jmix.rest.services-config`;
- `jmix.rest.queries-config`.

Comma-separated classpath resources have independent reference ranges.
Completion, navigation, file rename, wrong-kind detection, duplicate
detection and multi-module ambiguity diagnostics are backed by a dedicated
REST-descriptor content index. `classpath:` and leading-slash presentation is
preserved. External `file:` values and unresolved `${...}` placeholders remain
runtime-owned rather than receiving false local errors.

This is intentionally not a claim of complete Jmix configuration intelligence.
YAML, `.env`, resolved placeholder graphs and the full Jmix/Spring property
catalog remain tracked parity work.

## Application and entity events

Java and Kotlin inspections validate:

- listener ownership by an indexed Spring bean;
- instance/implementation and event-parameter arity contracts;
- parameterless listeners declaring event classes in the annotation;
- exact entity generic binding for `EntityChangedEvent`,
  `EntitySavingEvent` and `EntityLoadingEvent`;
- `@EventListener` for the pre-store saving/loading events;
- an explicit `REQUIRES_NEW` transaction when an after-commit changed-event
  listener visibly performs data access.

The transaction rules follow the official Jmix
[entity-event documentation](https://docs.jmix.io/jmix/2.7/data-access/entity-events.html).
The analysis is deliberately fail-closed for known invalid declarations but
does not pretend to prove arbitrary method-body transaction safety.

## Scale and ownership

REST descriptor discovery uses its own persistent content-sensitive IntelliJ
index. Unrelated Java, XML or properties typing cannot evict the cached REST
inventory. The existing 3,000-file, sixteen-module-shaped dual-host regression
now checks eight independent symbol inventories plus project-version Studio
metadata, and verifies that a real REST descriptor edit invalidates only its
own inventory.

All changes remain ordinary Jmix Java, Kotlin, XML and properties source. No
generated application requires a proprietary workbench runtime.

## Explicit remaining boundary

This milestone does not yet certify:

- custom Spring composed stereotypes and `@AliasFor` naming;
- application-event publisher-to-listener gutters and transitive impact;
- BPMN/expression consumers of Spring service methods;
- annotation-only REST endpoint authoring for every `@RestMethod` option;
- the complete configuration catalog, YAML or placeholder resolution;
- installed-IDE cold indexing, completion latency, memory and leak budgets.

Those items remain mandatory under `STUDIO-CORE-012` and the certification
ledger.
