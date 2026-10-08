# Deployment

Tanseki runs as a single daemon. How you run it depends on which store you want:
the **vault** profile keeps Markdown files you can also open in Obsidian, and the
**server** profile keeps rows in Postgres with a Meilisearch index beside them.

- [Choosing a profile](#choosing-a-profile)
- [Local and development](#local-and-development)
- [The vault profile](#the-vault-profile)
- [The server profile](#the-server-profile)
- [Environment variables](#environment-variables)
- [Health and readiness](#health-and-readiness)
- [Production](#production)

## Choosing a profile

| | vault | server |
|---|---|---|
| Canonical store | Markdown files | PostgreSQL |
| Index | Lucene, on disk | Lucene on disk + Meilisearch |
| Your notes stay readable in Obsidian | yes | no |
| Concurrent writers | one process | several |
| Best for | a personal knowledge base you own as files | shared or multi-device |

The server profile exists because a vault is **single-writer**. Tanseki takes an
advisory lock on the vault directory, so two daemons on one vault is one daemon
refusing to start. Put the notes in Postgres when more than one process needs to
write them.

You can migrate between them: `tanseki export` copies a vault into a library,
`tanseki import` copies it back. Neither overwrites a newer local write.
A directory that was never a Tanseki vault (an Obsidian folder, for example)
comes in through `tanseki import-vault --from <dir> --to <library.db>`
instead: no Pijul prerequisite, with `--dry-run` to preview the report.

## Local and development

Two compose stacks, one per profile. They are separate because they are separate
stores, not two ways to run one store:

```bash
cp .env.example .env      # then set TANSEKI_API_KEY
docker compose --profile vault up
docker compose --profile server up -d
```

`TANSEKI_API_KEY` is **required**, not defaulted, and the stacks refuse to start
without it. The daemon exposes an HTTP API and ships with no authentication
unless a key is set, so a default would be a trap rather than a convenience.
Generate one:

```bash
openssl rand -hex 32
```

### A vault the container can write

The image runs as uid `10001`. A bind mount owned by your user is not writable by
it, and the daemon fails at startup with an opaque error on `/vault/.tanseki` —
nothing about permissions in the message.

```bash
# Linux: one-time
sudo chown -R 10001:10001 "$VAULT"

# or let the container run as you, in a compose.override.yml:
services:
  vault:
    user: "${UID:-1000}:${GID:-1000}"
```

macOS and Windows with Docker Desktop map the uid for you, so the bind mount works
as written.

## The vault profile

```bash
docker compose --profile vault up
```

| Variable | Default | Meaning |
|---|---|---|
| `TANSEKI_VAULT_PATH` | `./vault` | Host directory holding your notes |
| `TANSEKI_VAULT_PORT` | `8088` | Host port for the HTTP API |
| `TANSEKI_API_KEY` | **required** | Bearer key for the API |

The daemon writes `.tanseki/` and `.pijul/` inside the vault: the derived index,
the revision ledger, and the Pijul working copy. Deleting `.tanseki/` costs a
reindex, not documents. Deleting `.pijul/` costs history.

The image ships the pinned `pijul` binary, so a container initializes a vault and
serves reads and indexing out of the box. Pijul signs every recorded change, so
**writing** to a vault from the container additionally needs a signing identity
the daemon can reach. A headless container has none by default, and pijul's
identity tooling needs an SSH agent (`SSH_AUTH_SOCK`); provide one — mount an
agent socket or run `pijul identity new` in a container that has an agent — or
writes fail while reads keep working.

Obsidian's `.obsidian/`, `.trash/` and `.pijul/` are never walked as
documents, so a deleted note in Obsidian's trash will not appear in a listing.

## The server profile

```bash
docker compose --profile server up -d
```

| Variable | Default | Meaning |
|---|---|---|
| `TANSEKI_POSTGRES_PASSWORD` | `tanseki-dev` | Password for the `tanseki` role |
| `TANSEKI_MEILI_KEY` | `tanseki-dev-master-key` | Meilisearch master key |
| `TANSEKI_SERVER_PORT` | `8088` | Host port for the HTTP API |
| `TANSEKI_API_KEY` | **required** | Bearer key for the API |

The daemon waits for both dependencies to report healthy, so `up -d` and then
`curl localhost:8088/v1/health` is enough.

Postgres holds the documents. Meilisearch holds the index. The Lucene index for
the server profile lives in a named volume outside the database and is derived
state: losing it costs a reindex, not documents.

## Environment variables

| Variable | Applies to | Meaning |
|---|---|---|
| `TANSEKI_PROFILE` | all | `vault`, `library` or `server` |
| `TANSEKI_PATH` | all | Vault directory, or library database file |
| `TANSEKI_INDEX_DIR` | all | Where the derived Lucene index lives |
| `TANSEKI_API_KEY` | all | Bearer key. Without one the API is unauthenticated |
| `TANSEKI_HTTP_HOST` | all | Bind address; defaults to loopback |
| `TANSEKI_HTTP_PORT` | all | Defaults to `8088` |
| `TANSEKI_SOCKET` | all | Unix socket for the local IPC channel |
| `TANSEKI_LOG_LEVEL` | all | `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `TANSEKI_POSTGRES_URL` | server | JDBC URL |
| `TANSEKI_POSTGRES_USER` | server | Database role |
| `TANSEKI_POSTGRES_PASSWORD` | server | Password for that role |
| `TANSEKI_MEILI_URL` | server | Meilisearch base URL |
| `TANSEKI_MEILI_KEY` | server | Meilisearch API key |
| `TANSEKI_TRUST_PROXY` | all | Honour `X-Forwarded-*` |
| `TANSEKI_TRUSTED_PROXY_SOURCES` | all | Which proxies to honour it from |
| `TANSEKI_CORS_ALLOW_ORIGINS` | all | Allowed browser origins |
| `TANSEKI_TLS_MODE` | all | `none` for loopback, or `trusted_proxy` when TLS terminates upstream |
| `TANSEKI_EMBEDDING_MODEL` | all | Enables hybrid search when set |

`TANSEKI_TRUST_PROXY` is only safe behind a proxy you control: it lets a client
set the scheme and host the daemon reports. Enable it with an explicit
`TANSEKI_TRUSTED_PROXY_SOURCES` list, never on its own on a public interface.

## Health and readiness

`GET /v1/health` is liveness. `GET /v1/ready` is readiness, and it is the one to
put in an orchestrator's readiness probe.

Readiness reports `degraded` when the daemon has **work outstanding that it has
not yet applied** — a pending outbox, an unprojected document, a projection that
has fallen behind. It does **not** report degraded merely because no writes have
happened for a while: an idle daemon with an accurate index is ready, and treating
idleness as a fault makes a readiness probe restart-loop a healthy process.

The response carries `projection.idle` so you can tell the two apart without
inferring it from a timestamp.

`/v1/metrics` requires `TANSEKI_API_KEY` and returns Prometheus text.

## Production

The compose stacks are **not** for production. They have no TLS termination, no
secret management, and development credentials in plain text.

- **Put TLS in front.** Terminate at a proxy and set `TANSEKI_TLS_MODE=trusted_proxy`
  with an explicit `TANSEKI_TRUSTED_PROXY_SOURCES`. A reverse proxy is the only
  supported public TLS terminator.
- **Generate the credentials.** `TANSEKI_API_KEY`, `TANSEKI_POSTGRES_PASSWORD`
  and `TANSEKI_MEILI_KEY` from a secret store. The compose defaults are
  placeholders and are in a file anyone can read.
- **Pin your images by digest.** The compose file does; if you write your own,
  do the same, or your production build drifts from the one CI tested.
- **Back up the store, not the index.** For `vault`, the notes are the backup
  unit and `.tanseki/` is derived. For `server`, back up Postgres.
- **One writer per vault.** A vault is single-writer by advisory lock. Use the
  server profile when that is not what you want.

See also: [`../README.md`](../README.md) for the HTTP API, and
[`compose.yaml`](../compose.yaml) for the stacks themselves.