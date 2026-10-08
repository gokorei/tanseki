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

## Scaling notes

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
