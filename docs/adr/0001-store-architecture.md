# ADR-0001: Store architecture and adapter dependency boundaries

Status: Accepted
Date: 2026-10-08

## Context

The `tanseki.kotlin-library` build convention permitted `adapters:*` to depend
on `core:application` (and `core:text`), while the intended architecture keeps
application orchestration above replaceable infrastructure adapters. Three
adapters (`pijul-cli`, `lookup-meili`, `embedder-onnx`) depended on
`core:application` for a single generic utility — `RetryPolicy`/`retry` — which
dragged the whole orchestration layer into the adapter compile classpath.

Decision needed: the ADR is authoritative on dependency direction; the build
rule is its executable form. Both now describe the same model.

## Decision

Dependency direction (allowed project dependencies, enforced per module by the
`checkDependencyRules` task in `tanseki.kotlin-library`):

- `core:domain` -> (none)
- `core:text` -> `core:domain`
- `core:ports` -> `core:domain`
- `core:application` -> `core:domain`, `core:ports`, `core:text`
- `adapters:*` -> `core:domain`, `core:ports`, `core:text` — **never
  `core:application`** unless explicitly justified and recorded here
- `testkit` -> `core:ports`, `core:text` (test configurations only)
- `sdk:*` -> (none; wire contract only)
- `service` / `cli` -> `core:*` + `adapters:*` (+ `composition`)
- nothing may depend on `service`/`cli`

Concretely:

1. Generic retry (`RetryPolicy`/`retry`) lives in `core:ports`
   (`gokorei.tanseki.core.ports`), which already carries the coroutine and
   domain-error dependencies it needs. Adapters reuse it without importing
   application orchestration.
2. `core:application` keeps deprecated re-exports (`typealias RetryPolicy`,
   `fun retry`) until external consumers migrate off the old import path.
3. The three adapter `implementation(project(":core:application"))`
   dependencies are removed.
4. `core:text` stays an allowed adapter dependency: it is a leaf utility
   module (markdown/frontmatter), not orchestration. Context adapters use it
   directly.

## Consequences

- `checkDependencyRules` (wired into `check` for every module) fails the build
  when a forbidden adapter dependency is introduced — no separate test file is
  needed; each adapter module owns its check task.
- Moving other orchestration-coupled utilities below adapters follows the same
  rule: leaf behavior goes to `core:domain`/`core:ports`/`core:text`, never the
  reverse.
- Any future exception (an adapter genuinely needing `core:application`) must
  be justified in this ADR and allow-listed in the build convention, not added
  ad hoc.
