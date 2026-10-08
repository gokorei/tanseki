# Postgres Context Store (server profile)

`PostgresContextStore` is the server-profile implementation of the `ContextStore`
port: documents, revisions, edges and blob content in a single PostgreSQL
database. Embeddings are **not** stored here — they are derived and live in the
Lookup (`lookup-meili`).

## Schema

| Table | Purpose | Key columns |
|-------|---------|-------------|
| `documents` | Current state of each document (one row per id). | `id` PK, `collection`, `path` UNIQUE, `frontmatter` (serialized typed view), `frontmatter_raw` (verbatim block, NULL when synthesized), `content`, `content_hash`, `revision`, `updated_at`, `deleted` |
| `revisions` | Append-only revision ledger. | `(doc_id, revision)` PK, `author`, `message`, `content_hash`, `content`, `created_at`, `deps` |
| `edges` | Derived graph edges (canonical, recomputed on write). | `(src, rel, dst)` PK, `props` |
| `blobs` / `blob_content` | Content-addressed base64/byte payloads. | `blobs.hash` PK, `blob_content.hash` FK → `blobs.hash`, `bytes BYTEA` |
| `schema_migrations` | Migration ledger (see below). | `version` PK, `name`, `applied_at` |

Indexes: `documents_collection_idx` (v1), `edges_dst_idx` (v1),
`edges_src_idx` (v2).

## Migrations

The schema is applied by `PostgresSchema.migrate(dataSource)`, called on startup
by `PostgresContextStore.open(...)`:

- Each change is an entry in the ordered `MIGRATIONS` list in
  `PostgresSchema.kt` with a unique `version`, a `name`, and replay-safe
  (`IF NOT EXISTS`) statements.
- `migrate` creates `schema_migrations` if needed, takes a session advisory lock
  (`pg_advisory_xact_lock`) to serialize concurrent starters, then runs only the
  migrations whose `version` is not yet recorded. Each migration and its version
  row are committed atomically, so a failure rolls back the whole batch.
- It is **idempotent**: running it again applies nothing. An existing
  pre-versioning database is adopted on first run (v1 uses `IF NOT EXISTS` and
  the version is then recorded).

### Adding a migration

1. Append a new `Migration(version = N + 1, name = "...", statements = [...])`
   to `PostgresSchema.MIGRATIONS`. Never edit or renumber an existing migration.
2. Keep statements replay-safe where possible (`IF NOT EXISTS`, `ADD COLUMN IF
   NOT EXISTS`).
3. Cover it in `PostgresSchemaMigrationTest`.
