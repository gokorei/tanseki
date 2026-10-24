# lookup-lucene: vector index tuning

`LuceneLookup` stores one kNN vector field (`vector`, cosine similarity) per
chunk and answers `searchVector` with Lucene HNSW approximate search.
All index-shape knobs live in `LuceneVectorConfig` and are passed through the
`LuceneLookup` constructor (or `LuceneLookup.open` for on-disk indexes):

```kotlin
val lookup = LuceneLookup(
    vectorConfig = LuceneVectorConfig(
        dimensions = 384, // expected vector length; null = accept what the embedder gives
        maxConn = 16, // HNSW M: graph fanout
        beamWidth = 100, // HNSW efConstruction: build-time candidate beam
        numCandidates = 50 // search-time overfetch; null = fetch exactly `limit`
    )
)
```

## Knobs and trade-offs

| Knob | Raises recall | Costs | Notes |
| --- | --- | --- | --- |
| `maxConn` (M), 2..512 | Higher M = denser graph, fewer missed neighbours | Index size (~M links per node) and build time | Index-time only; needs `rebuild` to apply to old segments |
| `beamWidth` (efConstruction), 2..3200 | Wider beam = better graph quality | Build time, roughly linear | Index-time only; needs `rebuild` to apply |
| `numCandidates` | More candidates = closer to exact top-`limit` | Query latency per search | Search-time only; results are still truncated to `limit` |
| `dimensions` | No direct effect; smaller dims are easier to search | Memory/bandwidth linear in dim | Validated fail-fast on write and query when set |
| `similarity` | — | — | Defaults to cosine; changing it needs `rebuild` |

Lucene 9 fixes the search-time ef internally, so there is no native efSearch
knob: `numCandidates` emulates it by overfetching
`max(limit, numCandidates)` candidates and keeping the top `limit`.

The index codec is `TansekiHnsw` (HNSW params over the default codec,
registered via `META-INF/services`), so segments written with any shape stay
readable wherever this module is on the classpath.

## Measured recall vs latency

`LuceneVectorTuningTest` pins this table. Methodology: 1000 docs of
unit-normalized random vectors (seed 42), 20 probes made of stored vectors
plus small noise, ground truth by brute-force cosine, recall@10, in-memory
index. Recall is deterministic for a fixed seed; latency is a single-run
snapshot on one machine — re-run to compare shapes, not to quote absolutes.

| shape | dim | maxConn | beam | numCandidates | recall@10 | indexMs | ms/query |
| --- | --- | --- | --- | --- | --- | --- | --- |
| lean | 64 | 8 | 20 | — | 0.875 | 1787 | 1.648 |
| default | 64 | 16 | 100 | — | 0.995 | 1349 | 1.149 |
| rich | 64 | 32 | 200 | 50 | 1.000 | 1956 | 2.561 |
| compact | 16 | 8 | 20 | — | 0.985 | 1221 | 0.590 |

Reading: the shipped default shape is near-exact at this scale; lean trades ~12
points of recall for a smaller graph; rich closes the gap with a denser
graph plus overfetch. Compact shows the dimension axis: at the same lean
shape, dim 16 searches ~3x faster than dim 64 and recalls better (0.985 vs
0.875) because lower-dimensional spaces are easier for the graph — at the
price of whatever quality the embedding model loses by emitting fewer dims.

The shipped default (`LuceneVectorConfig()` / `LuceneVectorConfig.DEFAULT`:
maxConn 16, beamWidth 100, no dimension pin, no overfetch) is the `default`
row above: 0.995 recall at 1.149 ms/query. Rich buys only +0.005 recall for
~2.2x query latency and ~45% more index time, so it is not the default; lean
loses ~12 points of recall for no latency win. The values are pinned as
literals so a Lucene upgrade cannot silently retune the index.

## Changing the default

Index-shape knobs (`maxConn`, `beamWidth`, `dimensions`) apply to newly
written segments only — retune an existing on-disk index with a `rebuild`
before the whole index uses the new shape. `numCandidates` is search-time
only and takes effect immediately.

Via the application config (vault, library, and directory profiles — the
server profile uses Meilisearch, so these have no effect there):

```bash
export TANSEKI_LUCENE_VECTOR_DIMENSIONS=384
export TANSEKI_LUCENE_VECTOR_MAX_CONN=32
export TANSEKI_LUCENE_VECTOR_BEAM_WIDTH=200
export TANSEKI_LUCENE_VECTOR_NUM_CANDIDATES=50
```

Unset means the shipped default above. Non-numeric or out-of-range values
fail fast at startup.

Reproduce:

```bash
./gradlew :adapters:lookup-lucene:test --no-daemon \
  --tests "gokorei.tanseki.adapters.lucene.LuceneVectorTuningTest"
```

The table is printed to stdout (captured in the test XML `system-out`).

Tip: Lucene logs `Java vector incubator module is not readable` without
`--add-modules jdk.incubator.vector`; enabling it speeds up vector scoring
and shifts every ms/query row down without changing recall.
