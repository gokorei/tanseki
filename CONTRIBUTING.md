# Contributing to Tanseki

## This project is read-only

**Tanseki does not accept external code contributions.** Pull requests are not
merged, including small ones. The full policy, and the reasoning behind it, is
in [GOVERNANCE.md](GOVERNANCE.md).

What that means for you:

- **You want a change?** Fork the repository. Tanseki is Apache-2.0, so you need no
  permission, you keep copyright in your fork, and you may license it however
  you like. This is the supported path and it is not a workaround.
- **You found a bug?** Open an issue. Reports are read on a best-effort basis
  with no response-time commitment; opening one is not a promise of a fix.
- **You found a vulnerability?** Report it privately. This is the one category
  the read-only rule does not cover. See [SECURITY.md](SECURITY.md).
- **You want to help anyway?** A fork, a write-up, a reproduction, or an issue
  with a specific failing case and the command that produces it are all useful,
  even when the upstream fix is not yours to make.

Everything below is aimed at people working on their own fork. The engineering
rules are the same ones the project holds itself to, so a fork that follows them
stays compatible with upstream.

## Working on a fork

- Use JDK 21 and the checked-in Gradle wrapper. Vault tests and runtime paths
  also require `pijul`; relevant container-backed tests may require Docker.
- Keep changes focused. Do not include unrelated generated output, local vault
  state, databases, logs, credentials, `.gradle/`, `.kotlin/`, or `build/`
  directories.
- Do not edit CI, the container definition, Gradle build files, or generated SDK
  files unless that work is explicitly assigned to you.

## Architecture boundaries

Tanseki uses ports and adapters. Preserve these dependencies:

- `core/domain` has no application dependencies;
- `core/ports` depends on `core/domain`;
- `core/application` depends on domain and ports;
- adapters depend on domain and ports, not service or CLI;
- service, CLI, and composition are outer layers;
- consumers depend on the `/v1` wire contract, not internal core types.

A new backend belongs behind a port and should pass the shared contract tests in
`testkit`. A Lookup must remain derivable from the Context Store; do not add
client writes or durable state that exists only in a projection.

The canonical invariant is `Lookup = f(ContextStore)`: only the Context Store
accepts client writes, and only the indexer updates the derived Lookup. The
design rationale is maintained outside this repository.

## Local verification

Run the repository-wide path before publishing a fork or opening a pull request
against upstream:

```bash
./gradlew build --no-daemon
```

Useful focused checks include:

```bash
./gradlew :core:application:test
./gradlew :service:test
./gradlew :adapters:lookup-lucene:test
./gradlew :adapters:context-postgres:test
./gradlew :adapters:lookup-meili:test
./gradlew :sdk:generated:kotlin:test
```

Build the runnable daemon/MCP distribution when changing startup, packaging, or
runtime composition:

```bash
./gradlew :service:installDist
```

Smoke-test the intended profile and both HTTP and MCP startup paths when the
change affects them. Server-profile smoke tests require reachable PostgreSQL
and Meilisearch services; use disposable data and credentials.

## OpenAPI and generated SDK policy

The source of truth is the Ktor route code in
`service/src/main/kotlin/gokorei/tanseki/service/api/StoreApi.kt`.

1. Change the route and its tests.
2. Export the code-first contract:

   ```bash
   ./gradlew :service:exportOpenApi
   ```

3. Regenerate both checked-in clients:

   ```bash
   ./gradlew generateSdk
   ```

4. Verify deterministic output:

   ```bash
   ./gradlew checkOpenApiAndSdkDrift
   ```

   The two halves are separate authorities. `:service:checkOpenApi` asks whether
   `docs/openapi.json` still matches the routes; `checkSdkGenerated` asks whether
   the checked-in clients still match the committed spec. `checkSdkGenerated`
   generates into `build/sdk-drift/**` and writes nothing in the work tree, so it
   is safe to run at any time and cannot race a regeneration.

5. Bump the version in `gradle.properties` (`tansekiVersion`) if the change is
   released. It is the only editable copy; `checkVersionSource` fails if
   `docs/openapi.json`, either generated client's packaging metadata, or the MCP
   `initialize` response reports anything else.

`docs/openapi.json` and `sdk/generated/**` are generated artifacts. Do not
hand-edit or partially regenerate them. Do not include temporary drift-check
directories in a change. The obsolete `sdk/kotlin` module must not be restored;
its routes and models are incompatible with the current API.

The repository identity stamped into the generated clients comes from
`gradle.properties` (`tansekiGitHost`, `tansekiGitUserId`, `tansekiGitRepoId`) and the
author/contact stamp from `tansekiInfoName` and `tansekiInfoEmail`. openapi-generator
cannot substitute the git identity from the command line, so the build rewrites
its `GIT_USER_ID`/`GIT_REPO_ID` placeholders after generation. Change the
identity in `gradle.properties`, never in the generated output.
## Changes to data and adapters

- Preserve the Context Store/Lookup boundary and idempotent write semantics.
- Treat Markdown, frontmatter, IDs, revisions, edges, and collection names as
  data or user input, not trusted code or paths.
- Add contract tests for new adapters and migration tests for schema changes.
- A failed read must occur before a rebuild mutates the Lookup; the canonical
  Context Store must remain available for a later retry.
- Do not weaken API-key scoping, reverse-proxy policy, daemon locking, or
  collection isolation in tests.

## Documentation and ADRs

Update the nearest existing document rather than creating a competing source of
truth. Link architecture changes from the root README, update the relevant API
or deployment guide, and add or update an ADR when a durable architectural
decision changes. Do not claim a command or profile is supported until its
build/runtime path has been verified.

## Expectations for a pull request

Upstream will not merge, so this section is about making a patch reviewable
wherever you send it — a fork, another project, or your own records. A focused
change should include:

- the behavior and rationale in the description;
- tests for changed behavior and failure paths;
- documentation and generated artifacts required by the wire contract;
- the exact local verification command and result;
- screenshots only for user-visible documentation changes;
- no secrets, private data, local runtime state, or unrelated cleanup.

If you are opening a pull request against upstream anyway, expect it to be
closed without review. Send the patch to the maintainer directly if that is
worth doing.

For security-sensitive changes, follow [SECURITY.md](SECURITY.md) for private
coordination before public disclosure.

## Project license

Tanseki is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE)
for the complete terms. No CLA or Developer Certificate of Origin is required or
accepted; see [GOVERNANCE.md](GOVERNANCE.md) for why, and for what you may do
with the code without asking.