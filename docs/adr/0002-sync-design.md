# ADR 0002: Multi-Device Sync Design (CRDT vs Object-Store Push-Pull)

- Status: Accepted
- Date: 2026-10-08
- Ticket: RPFRDRVZ
- Context: deployment profiles (vault = single-writer Markdown+Pijul; server = multi-writer Postgres), `ContextStore` port (`core/ports/.../ContextStore.kt`), `PijulClient` port (`core/ports/.../PijulClient.kt`), `FileContextStore` (`adapters/context-file/.../FileContextStore.kt`), `StoreCapabilities` (`core/domain/.../Capabilities.kt`)

Decided alongside `docs/adr/0001-store-architecture.md`, which records the store boundary this design builds on.

## Decision

Use **object-store push-pull over the existing patch DAG** (Pijul for vaults, `exportTransferStates`/`importTransferState` for DB stores). **Defer CRDT** (Automerge/Yjs). No per-document CRDT type is introduced.

## Conflict model (chosen)

1. **Optimistic CAS per document.** `write`/`delete`/`rename`/`restore` take `ifRevision`; mismatch raises `ConflictException` (HTTP 409). Sync reuses this: a pull that applies a remote write onto a locally-moved revision conflicts instead of overwriting.
2. **Stale-transfer guard.** `isStaleTransfer` (same id, different hash, `updatedAt` older than local) skips the incoming copy. Wall-clock LWW, but only as a *skip-stale* rule, never as a silent merge.
3. **Tombstones win on delete.** A delete replicates as a tombstone; concurrent edit-vs-delete surfaces as a conflict (restore path), never a resurrection.
4. **Text-layer commutation only.** Non-overlapping text edits commute inside the Pijul patch DAG on pull. Overlapping concurrent edits to one document do **not** auto-merge at the API layer; the loser gets 409 and retries.
5. **Derived state never syncs.** Edges, projection outbox/leases, Lucene/Meilisearch indexes, and `.tanseki/` bookkeeping are rebuilt per node (`FullRebuilder`/reindex). Only documents, tombstones, blobs (content-addressed), and history cross the wire.

## Options considered

| | CRDT (Automerge/Yjs) | Object-store push-pull (chosen) |
|---|---|---|
| Convergence | Automatic, no conflicts surfaced | Explicit conflicts (409) on true concurrency |
| Vault fit | Poor: binary/sidecar state breaks byte-for-byte Markdown round-trip and Obsidian interop | Native: files stay canonical, history stays in the Pijul DAG |
| Offline | Excellent (any order merge) | Good (buffer ops, push/pull when reachable) |
| Cost | New dep per platform, doc-format migration, dual-format clients | Reuses `PijulClient.record/apply/log`, `TransferState`, CAS |
| Correctness risk | Silent surprising merges of prose; edge derivation (`[[wikilink]]` re-index) still needs a deterministic rule outside the CRDT | Conflicts are visible; merge responsibility stays in one place (patch DAG) |

CRDT would be reconsidered only if conflict-free offline co-editing of a single note becomes a product requirement; that would still be scoped to note body text, never to edges/index/outbox.

## Ports/adapters impact

- **No `ContextStore` signature change.** Sync is built from existing operations: `read`/`write(ifRevision)`/`delete`/`history`/`exportTransferStates`/`importTransferState`/`putBlob`/`getBlob`.
- **New small sync surface (adapter, not port change):** a `SyncTransport` with `push(heads, patches)` / `pull(heads)` / `heads()` per replica. Vault transport = Pijul channel push/pull (`PijulClient.record` + `apply`); server/DB transport = `TransferState` batches over HTTP.
- **`StoreCapabilities`:** add `supportsSync: Boolean` (vault + Postgres true; SQLite/local-file false initially). No other capability changes; `supportsHistoryImport == false` for vault remains (history travels as patches, not ledger rows).
- **Server profile** is the multi-device target today; vault-to-vault sync ships second, reusing the same conflict rules.
- **API:** sync endpoints return 409 with the winning `Revision` on conflict so clients can rebase; no new error type.

## Consequences

- Multi-device works with current adapters plus one transport; no format migration.
- Clients must handle 409 on sync (show conflict, keep both revisions) — a client UX task, not a store task.
- Wall-clock `updatedAt` stays a heuristic for skip-stale only; true ordering comes from revision CAS + patch dependencies.
