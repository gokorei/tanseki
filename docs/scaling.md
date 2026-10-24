# Scaling

How Tanseki behaves on large vaults, and the harness that measures it.

## Harness

`./gradlew :cli:benchLargeVault -PbenchDocs=N` (default `N=1000`) builds a
deterministic synthetic vault under `cli/build/bench-vault/` (never committed),
then times three operator paths on the vault profile (Markdown + Lucene):

- **index build** — `FullRebuilder.rebuild()`, the same path as `tanseki rebuild`;
- **search latency** — mean/p50/p95/max over 30 `Lookup.searchText` queries
  (`limit=20`, mixed hit sizes plus one zero-hit term);
- **reconcile** — cold (fresh `Reconciler`, full pass), warm (same instance,
  no changes), and incremental (same instance, 10 docs touched).

The full report (host, seed, per-phase table) is written to
`cli/build/reports/bench/bench-large-vault.md`.

Fixtures are prose plus `title`/`author`/`tags` frontmatter and deliberately
contain no `[[wikilinks]]` or `files:` refs: each link would send edge
derivation back to the store for resolution on every indexed document, which
is real behaviour but would dominate the timings and hide the build/search
costs this harness tracks.

## Baseline (2026-10-08)

Host: macOS aarch64, 12 cores, JDK 21.0.7. 300 docs, avg 1912 bytes, seed 7.

| phase | ms | notes |
| --- | --- | --- |
| index build (full rebuild) | 42943.8 | 7.0 docs/s |
| search mean | 3.5 | over 30 text queries, limit 20 |
| search p50 | 3.2 |  |
| search p95 | 4.7 |  |
| search max | 10.3 |  |
| reconcile cold | 31265.3 | fresh reconciler, full pass |
| reconcile warm | 152.0 | same reconciler, no changes |
| reconcile incremental | 1774.0 | same reconciler, 10 docs touched |

## Re-measurement (2026-10-24, current main)

Host: macOS aarch64, 12 cores, JDK 21.0.7 (same class of machine as the
baseline). Seed 7, avg ~1912 bytes/doc at all sizes.

| docs | build ms | docs/s | cold ms | warm ms | incr (10 docs) ms | search mean/p95 ms |
| --- | --- | --- | --- | --- | --- | --- |
| 100 | 1255.8 | 79.6 | 966.2 | 14.5 | 87.1 | 0.9 / 1.8 |
| 300 | ~2200 | ~135 | ~1650 | ~40 | ~100 | 0.9 / 1.6 |
| 1000 | 5556.6 | 180.0 | 4779.4 | 98.6 | 145.9 | 1.2 / 1.5 |

(300-doc build ran 2176–2312 ms across runs; cold 1503–1778 ms. Range is
run-to-run variance, not a trend.)

The superlinear scaling from the 2026-10-08 baseline is gone: per-document
build cost falls with size (12.6 ms at 100 docs → 7.3 ms at 300 → 5.6 ms at
1000), i.e. fixed overhead amortized, no blowup. The old 9.8 s / 42.9 s
numbers predate the reference-index narrowing (`b9c14b4`) and the
`indexWithoutRepair` batch path; both landed since.

### Where the build time goes (300 docs, profiled on a built vault)

| op | total ms | per-doc ms | share of build |
| --- | --- | --- | --- |
| `lookup.index` per doc (Lucene write + NRT refresh, no embed) | 1505.8 | 5.02 | ~69% |
| `store.replaceEdges` per doc × 2 passes (index + graph rewrite) | ~216 | 0.36 | ~10% |
| `store.read` all | 35.6 | 0.12 | ~2% |
| `store.list` | 35.3 | — | ~2% |
| `store.neighbors` all | 22.1 | 0.07 | ~1% |
| edge derive all (seeded) | 9.6 | 0.03 | <1% |
| `repairTarget` all 300 docs (candidates 0, repairs 0) | 0.4 | 0.001 | ~0% |
| `pending(MAX)` × 300 (`unprojectedDocuments` per repair) | 0.2 | 0.001 | ~0% |
| embed | 0 | 0 | 0% — vault profile configures no embedder |

Remainder (~0.4 s) is `clearEdges`, prune, outbox drain, and progress/GC.

Suspect verdicts: (1) `repairTarget` per-write cost — eliminated; early exit
via `ReferenceIndex.candidatesFor` costs 0.4 ms for the whole vault, and the
rebuild path skips repair entirely. (2) `pending(MAX)` per repair — not a
problem; it scans only the (empty) projection outbox, 0.2 ms over 300 calls.
(3) ONNX per-doc inference — not applicable here: the bench profile has no
embedder (`Indexer` skips the embed branch), so this cost is unmeasured; note
`Indexer` still embeds one document per `embed()` call with no batching when
an embedder *is* configured. (4) Per-doc store commits — real but minor:
~10% via doubled edge-sidecar writes.

### Conclusion and follow-up

No superlinear behaviour remains and no hot-fix is needed. The dominant cost
(~69%) is `LuceneLookup.index` refreshing NRT readers once per document; the
batch primitive already exists (`indexWithoutCommit` + `commit()`, currently
private with `commit()` public only on `LuceneLookup`, not on the `Lookup`
port). Recommended follow-up: expose a bulk-index path on the `Lookup` port
and use it from `FullRebuilder`, mirroring what `indexWithoutRepair` did for
edges — expected to remove most of the remaining ~5 ms/doc. Second, if the
vector path matters, profile ONNX per-doc inference with
`TANSEKI_EMBEDDING_DIR` set; the unbatched `embed()`-per-document call is the
obvious candidate there. Neither blocks current work.

## Scaling notes (2026-10-08 baseline, kept for comparison)

- Index build and cold reconcile scale superlinearly in this range: 100 docs
  build in ~9.8 s (10.2 docs/s), 300 docs in ~42.9 s (7.0 docs/s). Do not
  extrapolate linearly — at this rate 1000 docs take several minutes, which is
  why the default `-PbenchDocs=1000` run exceeds the 5-minute budget and the
  recorded baseline uses 300.
- The cost driver is per-document indexing re-scanning the vault
  (`EdgeDeriver.repairTarget` re-reads every document for each indexed one),
  so a fresh `Reconciler` over an already-built index costs almost as much as
  the rebuild itself. The retained (daemon-style) reconciler is cheap: warm
  no-op ~0.15 s, 10-doc incremental ~1.8 s at 300 docs.
- Search latency is flat and independent of vault size in this range
  (p95 ~5 ms at both 100 and 300 docs).
