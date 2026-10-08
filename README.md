# Tanseki

[![CI](https://github.com/gokorei/tanseki/actions/workflows/ci.yml/badge.svg)](https://github.com/gokorei/tanseki/actions/workflows/ci.yml)

Tanseki is an open, self-hosted, agent-native knowledge store for humans and LLM
clients. It keeps canonical documents separate from the indexes used to search
and traverse them, so the index can be replaced or rebuilt without making it a
second source of truth.

Consumers use Tanseki through a versioned JSON HTTP API, an MCP stdio server, or
the generated Kotlin SDK.

> **The name.** 蛋白石 (*tanseki*) is "milky white stone" — the mineral whose colour
> shifts with the angle you view it from, while the stone itself stays fixed.
> That is the shape of this project: the Context Store is canonical and fixed, and
> the Lookup is a derived view that changes with how you look at it
> (`Lookup = f(ContextStore)`). The namespace is `gokorei.tanseki`, under the
> parent organisation.

> **Tanseki is published read-only.** External code contributions are not accepted
> and pull requests are not merged — including one-line fixes. You may read, run,
> fork, modify, and redistribute Tanseki freely under Apache-2.0 without asking
> anyone; forking is the supported way to change it. Bug reports and feature
> requests are welcome on a best-effort basis, and security reports are taken
> through the private channel in [SECURITY.md](SECURITY.md). The full policy is
> in [GOVERNANCE.md](GOVERNANCE.md).

## Project status

| | |
|---|---|
| License | Apache-2.0 |
| Contributions | Read-only. Pull requests are opened and closed, not merged. |
| Getting a change in | Fork the repository and change it there. No permission needed. |
| Issues | Read on a best-effort basis. No response-time commitment. |
| Security reports | Accepted privately and acted on. See [SECURITY.md](SECURITY.md). |
| Releases | No published cadence, and no supported-version window or LTS policy. |
| CLA | None required and none accepted. |

Treat Tanseki as source you may rely on, not as a service with a support contract.
Build it, run it, and read it; if you need a guarantee the project cannot make,
use your own fork.

## Design goals

- Keep notes portable, user-owned, and available as Markdown in vault mode.
- Support local, human-editable use without coupling the domain model to files.
- Expose one stable `/v1` contract for programmatic consumers.
- Give agents a separate, token-efficient MCP tool surface.
- Keep search and graph projections rebuildable from the canonical Context Store.
- Scale from a single-machine vault to a shared server without changing clients.

The core invariant is `Lookup = f(ContextStore)`: only the Context Store accepts
client writes, and only the indexer updates the derived Lookup.

## Supported modes

| Mode | Canonical data | Derived lookup | History and runtime | Typical use |
|---|---|---|---|---|
| `vault` (default) | Markdown files | Lucene | Pijul history, file watcher, single-writer daemon lock | Personal, local-first vault shared by a human and agents |
| `library` | SQLite | Lucene | No Pijul, watcher, or daemon socket | A local single-writer library with hand-editable export/import workflows |
| `server` | PostgreSQL | Meilisearch | Multi-node service behind the `/v1` API | Shared or remote consumers with collection-scoped credentials |

The Lookup directory and other indexes are derived state. Do not edit them or
treat them as canonical.

## Quick start

### Prerequisites

- JDK 21
- `pijul` on `PATH` for `vault` mode
- PostgreSQL and Meilisearch for `server` mode

Check the local prerequisites:

```bash
java -version
pijul --version
```

### Build the daemon and MCP distributions

```bash
./gradlew :service:installDist
```

The resulting distribution is `service/build/install/tanseki-daemon/` and contains
`bin/tanseki-daemon` and `bin/tanseki-mcp`.

### Run a local vault

Choose a writable directory for the Markdown vault. The daemon initializes the
Pijul repository and creates its derived `.tanseki/` state as needed.

```bash
TANSEKI_PROFILE=vault \
TANSEKI_PATH=/absolute/path/to/vault \
TANSEKI_HTTP_PORT=8088 \
service/build/install/tanseki-daemon/bin/tanseki-daemon
```

In another terminal, verify the public health endpoint:

```bash
curl -fsS http://127.0.0.1:8088/v1/health
```

### Import a plain Markdown vault

`tanseki import-vault` copies a foreign directory — an Obsidian vault, for
example — into a SQLite library. The source needs no Pijul repository and is
left byte-identical; only `*.md` files are read. Preview with `--dry-run`
first: it reports the same counts and writes nothing.

```bash
tanseki import-vault --from ~/obsidian-vault --to ~/library.db --dry-run
# would import documents=12 skipped=0 edges=9 revisions=0 unreadable=0 renamed=0 conflicting=0 unresolvedLinks=1

tanseki import-vault --from ~/obsidian-vault --to ~/library.db
# imported documents=12 skipped=0 edges=9 revisions=0 unreadable=0 renamed=0 conflicting=0 unresolvedLinks=1
```

The report is terse `key=value`: `imported`/`skipped` documents, `unreadable`
sources, `renamed` sanitizations, `conflicting` id collisions, and
`unresolvedLinks` for `[[wikilinks]]` with no target. A lossy run — anything
unreadable, renamed, conflicting, or failed — exits non-zero so scripts can
detect it, and re-running an unchanged vault imports nothing.

Run the full repository verification path from a clean checkout before release:

```bash
./gradlew build --no-daemon
```

Library/server startup, container packaging, and reverse-proxy requirements are
described by the settings below and enforced at startup; wildcard binds and
unscoped server credentials are rejected rather than warned about.

### Containers

There is a Dockerfile for the daemon and a compose stack per store. The stacks are
for local and development use — no TLS termination, no secret management, and
development credentials in plain text.

```bash
cp .env.example .env          # then set TANSEKI_API_KEY
docker compose --profile vault up     # file store over a mounted vault
docker compose --profile server up -d  # Postgres + Meilisearch
```

A vault bind mount has to be writable by uid `10001`, which is what the image runs
as; see [docs/deployment.md](docs/deployment.md) for that and for what to do
differently in production.

## Configuration

Tanseki reads configuration from environment variables. CLI arguments can override
the path, profile, and index directory for their supported operations.

> **Derived state lives in `.tanseki/`.** Vault mode writes its non-canonical
> bookkeeping — index, revisions, edges, blobs, tombstones, collection
> assignments — beside the documents in `.tanseki/`. Part of that state is not
> recoverable from the documents alone, so do not delete or move it.

| Variable | Purpose | Default |
|---|---|---|
| `TANSEKI_PROFILE` | `vault`, `library`, or `server` | `vault` |
| `TANSEKI_PATH` | Markdown vault directory for vault mode or SQLite database file for library mode | `.` |
| `TANSEKI_INDEX_DIR` | Derived local Lookup directory | `<path>/.tanseki/index` |
| `TANSEKI_SOCKET` | Vault daemon IPC socket | `<path>/.tanseki/tanseki.sock` |
| `TANSEKI_HTTP_HOST` | Explicit HTTP bind address; wildcard binds are rejected | `127.0.0.1` |
| `TANSEKI_HTTP_PORT` | HTTP API port | `8088` |
| `TANSEKI_LOG_LEVEL` | `DEBUG`, `INFO`, `WARN`, or `ERROR` | `INFO` |
| `TANSEKI_WATCH_DEBOUNCE_MS` | Vault watcher coalescing window | `200` |
| `TANSEKI_RECONCILE_INTERVAL_MS` | Periodic Context Store-to-Lookup reconciliation | `30000` |
| `TANSEKI_PIJUL_BINARY` | Pijul executable name or path | `pijul` |
| `TANSEKI_API_KEY` | Local-development compatibility key; not valid for server-profile exposure | unset |
| `TANSEKI_API_CREDENTIALS` | Semicolon-separated scoped credential registry | unset |
| `TANSEKI_TLS_MODE` | `none` for loopback or `trusted_proxy` when TLS terminates upstream | `none` |
| `TANSEKI_TRUST_PROXY` | Trust the configured reverse proxy for TLS | `false` |
| `TANSEKI_POSTGRES_URL` | PostgreSQL JDBC URL; required by server mode | unset |
| `TANSEKI_POSTGRES_USER` | PostgreSQL user | `tanseki` |
| `TANSEKI_POSTGRES_PASSWORD` | PostgreSQL password | unset |
| `TANSEKI_MEILI_URL` | Meilisearch base URL; required by server mode | unset |
| `TANSEKI_MEILI_KEY` | Meilisearch API key | unset |
| `TANSEKI_EMBEDDING_DIR` | Directory containing an available ONNX embedding model | unset; lexical lookup |
| `TANSEKI_EMBEDDING_MODEL` | Embedding model configuration identifier | adapter default |

Never commit API keys, database passwords, model directories containing private
artifacts, or runtime vault state.

For remote or server exposure, Tanseki requires an explicit non-wildcard bind,
`TANSEKI_API_CREDENTIALS`, `TANSEKI_TLS_MODE=trusted_proxy`, and
`TANSEKI_TRUST_PROXY=true`. A reverse proxy is the only supported public TLS
terminator. The credential format is:

```text
consumer|key|collection-a,collection-b|GET,LIST,QUERY,SEARCH,HISTORY,TRAVERSE
```

Multiple entries are separated with semicolons. Omitting the operation list
allows all operations for the listed collections. Every route publishes its own
auth requirements in [`docs/openapi.json`](docs/openapi.json).

## HTTP API usage

The versioned `/v1` API uses JSON. `/v1/health` is public; all document routes
require a key. Send `X-API-Key: <key>` or `Authorization: Bearer <key>`.

```bash
export TANSEKI_URL=http://127.0.0.1:8088

curl -fsS "$TANSEKI_URL/v1/health"

# Set TANSEKI_API_KEY only when this daemon requires local authentication.
curl -fsS -X POST "$TANSEKI_URL/v1/documents:upsert" \
  -H "X-API-Key: $TANSEKI_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: readme-example-1" \
  -d '{"id":"notes/getting-started","collection":"vault","path":"notes/getting-started.md","content":"Because the Context Store is canonical.","frontmatter":{"title":"Getting started","tags":["docs"]}}'

curl -fsS "$TANSEKI_URL/v1/search?q=canonical&limit=5" \
  -H "X-API-Key: $TANSEKI_API_KEY"
```

### Filtering by frontmatter

`fm=key=value` filters on a frontmatter key, and repeats for an AND of several.
It works **with or without** `q`: a filter on its own answers "which documents carry
this property", which is what an equality filter is usually for.

```bash
# Which documents in this repo?
curl -fsS -G "$TANSEKI_URL/v1/search" \
  -H "X-API-Key: $TANSEKI_API_KEY" \
  --data-urlencode 'fm=repo=org/repo' \
  --data-urlencode 'limit=20'

# Filters also narrow a text query.
curl -fsS -G "$TANSEKI_URL/v1/search" \
  -H "X-API-Key: $TANSEKI_API_KEY" \
  --data-urlencode 'q=rollback' \
  --data-urlencode 'fm=repo=org/repo'
```

A filter value is resolved the way a YAML or JSON scalar would be, because that is how
the document's own frontmatter was read. `pr: 42` is the number `42` and `pr: "42"` is
the string `"42"`, so `fm=pr=42` selects the number and `fm=pr="42"` selects the string.
Quote a value to force it to text:

| filter | selects |
|---|---|
| `fm=pr=42`, `fm=pr=-3` | the **number** |
| `fm=flag=true` | the **boolean** `true` |
| `fm=repo=org/repo`, `fm=ver=1.2.3` | the **string** — anything that is not a number or a boolean is text |
| `fm=pr="42"`, `fm=flag="true"` | the **string** — quoting overrides the resolution |

An unquoted `42` will not reach a document storing `"42"` as a string, so a zero result
there is the type talking rather than a mystery: quote it. Numbers the parser left as
strings — `0042`, `+42`, `1_000`, `0x1F` — are text here too, so they match as typed.
`true` and `false` are read in lower case only, so `TRUE` is the string `TRUE`.

A filter whose value cannot be resolved is rejected rather than answered with an empty
result: `fm=pr=` has no value, and `fm=pr="42` has an unclosed quote. Both come back as a
`400` naming the filter, because a filter that matches nothing is indistinguishable from a
document that does not exist.

Two limits worth knowing. A filter with neither `q` nor a filter returns nothing
rather than the whole store: an omitted parameter should not look like a
successful empty result, and enumerating a collection is `GET /v1/documents`.
And contract keys — `tags`, `title` — are not frontmatter values, so `fm=tags=…` is rejected
rather than answered with an empty result; filter tags with `tags=`. A frontmatter key written
with no value at all (`pr:`) is a null, and `fm=pr=null` selects the *string* `"null"`, not it.

Document IDs are path-derived and may contain `/`, so item operations are custom
HTTP methods with the ID in the body. The machine-readable contract — routes,
auth, concurrency, pagination, errors, idempotency, and request bounds — is
[docs/openapi.json](docs/openapi.json).

### Frontmatter values keep their type

A document's canonical form is Markdown, so every frontmatter value takes a YAML
round trip. That round trip preserves shape and type, which is the contract:

| sent | stored | returned |
|---|---|---|
| `{"files": ["src/a.py"]}` | a YAML block sequence | `["src/a.py"]` — an array |
| `{"pr": 42}` | `42` | `42` — a number |
| `{"big": 9007199254740993}` | `9007199254740993` | `9007199254740993`, digit for digit |
| `{"flag": true}` | `true` | `true` — a boolean |
| `{"meta": {"owner": "me"}}` | a nested mapping | `{"owner": "me"}` — an object |
| `{"build": "0042"}` | `"0042"` | `"0042"` — still a string |

A number is held internally as its digits so no float rounding can alter it, and
is re-emitted unquoted; that is why `9007199254740993` survives a JVM whose
doubles cannot represent it. A value sent as a quoted string stays a string and is
never re-typed — `"0042"` is a build number in some corpora and a measurement in
others, and the seam does not get to decide.

`title`, `author`, `tags`, `updated_at` and `content_hash` are contract keys: each
has a typed field, so they take a scalar, `tags` takes an array of strings, and a
shape they cannot hold is **refused by name** rather than dropped. Every other key
is an extra and takes any JSON value.

**`updated_at` is the one key whose text form is not preserved.** It is a contract
key typed as an instant, so a write renders it in one canonical spelling:
`2026-10-03T12:00:00+00:00` and `2026-10-03T14:00:00+02:00` are both stored as
`2026-10-03T12:00:00Z`. The instant survives; the spelling does not. Compare
instants rather than strings, and do not expect a round trip to hand back the
exact characters you sent.

**Key order is the caller's.** Sending `{"z": 1, "a": 2}` stores `z` then `a`, and
a block written as YAML keeps the order it was written in. The same values through
either route produce the same bytes, so a note's content does not depend on which
seam wrote it. Idempotency is unaffected: `documents:upsert` sorts its own
fingerprint, so two requests differing only in key order are one write and the
second replays rather than conflicting.

Nothing here is a migration. A document keeps whatever shape it was stored as, so
one written before this behaviour existed still reads back the text it was
written with — including a value an older release stored as a repr string, which
stays that string. That is the one asymmetry to be aware of: a consumer that
treats a missing key and a wrong-typed key alike will read an old `files` as
absent.

## MCP usage

`tanseki-mcp` is a stdio MCP server with three tools:

- `search` for lookup queries and filters, with `mode=lexical` (default) or
  `mode=hybrid`;
- `document` for `get`, `upsert`, `delete`, and `history`;
- `traverse` for relationship traversal.

The relationship vocabulary (`links-to`, `references`, `embeds`, `mentions`),
what each type derives from, and what a dangling `references` value means are
recorded in [`docs/document-schema.md`](docs/document-schema.md); `traverse`
names a relationship type, never a frontmatter key.

`search` takes the same modes as [`GET /v1/search`](docs/openapi.json), so the two
seams rank a query identically. `hybrid` fuses the lexical ranking with cosine kNN
by reciprocal rank fusion, which helps on paraphrases that share no terms with the
query. It needs an embedder — configure one with `TANSEKI_EMBEDDING_DIR`, or let
the running daemon hold it — and **degrades to `lexical` when there is none**, as
it does on a Meilisearch profile that has no kNN index. The response says nothing
about which ranking ran, so pass `mode=hybrid` knowingly rather than assuming it
was honoured.

Set `TANSEKI_URL` to proxy to a running daemon. This is the preferred MCP setup
because it respects the daemon's single-writer ownership:

```bash
TANSEKI_URL=http://127.0.0.1:8088 \
TANSEKI_API_KEY=local-development-key \
service/build/install/tanseki-daemon/bin/tanseki-mcp
```

Point an MCP client at that executable and environment. If `TANSEKI_URL` is unset,
the process opens the configured profile directly; vault mode then takes the
same single-writer lock, while library and server mode open their backends in
process. MCP stdout is reserved for JSON-RPC and diagnostics go to stderr. Tool
results use TOON rather than HTTP JSON, cutting tokens for LLM callers.

## SDK usage

The Ktor `/v1` routes in
`service/src/main/kotlin/gokorei/tanseki/service/api/StoreApi.kt` are the source of truth.
`docs/openapi.json` is their exported artifact, and the checked-in Kotlin client
is generated from that export.

```bash
./gradlew :service:exportOpenApi
./gradlew generateSdk
./gradlew checkSdkGenerated
```

Use the generated Kotlin module:

```kotlin
import org.openapitools.client.apis.DocumentsApi
import org.openapitools.client.infrastructure.ApiClient

ApiClient.apiKey["X-API-Key"] = System.getenv("TANSEKI_API_KEY")
val documents = DocumentsApi(basePath = "http://127.0.0.1:8088")
val page = documents.v1DocumentsGet()
```

There is no Python client. The `/v1` seam is plain JSON over HTTP, so any
language can call it directly against the contract in
[`docs/openapi.json`](docs/openapi.json).

Do not hand-edit `docs/openapi.json` or `sdk/generated/**`. Change the routes,
export the spec, regenerate the client, and include the resulting generated files
with the source change.

## Architecture

```text
HTTP /v1 ─┐
          ├─> application services ─> Context Store (canonical)
MCP stdio ┘             │
                        └─> Indexer/Reconciler ─> Lookup (derived)
```

- [`core/domain`](core/domain) contains backend-independent documents,
  revisions, edges, capabilities, and errors.
- [`core/ports`](core/ports) defines Context Store, Lookup, Pijul, watcher,
  embedder, clock, and logging boundaries.
- [`core/application`](core/application) owns query, indexing, reconciliation,
  projection, and document command use cases.
- [`adapters`](adapters) implements Pijul, files, SQLite, PostgreSQL, Lucene,
  Meilisearch, ONNX, and operating-system watcher integrations.
- [`composition`](composition) selects and wires the `vault`, `library`, and
  `server` profiles.
- [`service`](service) owns HTTP, MCP, daemon, logging, and runtime composition.
- [`cli`](cli) contains operator commands for initialization, indexing,
  rebuilding, status, export, and import.
- [`sdk/generated`](sdk/generated) contains wire-contract clients; consumers do
  not depend on internal core modules.
- [`testkit`](testkit) provides shared port contracts and fixtures.

The service boundary is the `/v1` contract published as
[`docs/openapi.json`](docs/openapi.json): consumers depend on the wire, never on
internal core modules, and `build-logic` convention plugins enforce the
hexagonal dependency rules. Design rationale and internal architecture decisions
are maintained outside this repository.

## Project guidance

- [Governance and contribution policy](GOVERNANCE.md)
- [Contributing (read-only; forkers)](CONTRIBUTING.md)
- [Code of conduct](CODE_OF_CONDUCT.md)
- [Security policy](SECURITY.md)
- [Code ownership](.github/CODEOWNERS)

## License

Tanseki is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE)
for the complete terms. Third-party material that Tanseki incorporates, and the
attribution its licenses require, is recorded in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The license grants you the
rights to use, modify, and redistribute this code without any permission from
the maintainer; the read-only status above governs what happens to
contributions sent here, not what you may do with the result. See
[GOVERNANCE.md](GOVERNANCE.md) for the full policy, including the absence of a
CLA.
