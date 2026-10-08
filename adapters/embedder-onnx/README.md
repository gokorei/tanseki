# ONNX Embedder (local profile)

Local, self-hosted embeddings for the derived Lookup's kNN field. The ONNX
Runtime is a `runtimeOnly` dependency (accessed reflectively) so the Kotlin
compile classpath stays fast; the adapter degrades gracefully when it is absent.

## Default model

**`all-MiniLM-L6-v2`** — a 384-dimensional sentence-transformer, ~90 MB.

| Trade-off | Why this model |
|-----------|----------------|
| Quality | Strong semantic retrieval for its size; the de-facto small sentence-embedding standard. |
| Size | ~90 MB — small enough to self-host or ship, unlike 1 GB+ models. |
| Latency | Runs on CPU with low per-query latency; good for a local-first store. |
| Dimensions | 384 (matches `LuceneLookup`'s kNN field and `Embedder.dimensions`). |

The registry lives in `EmbeddingModels.kt`; add larger models there and select
them with `TANSEKI_EMBEDDING_MODEL`.

## Enabling it

Place the model artifacts in a directory (`TANSEKI_EMBEDDING_DIR`):

```
embedding/
  model.onnx     # EmbeddingModel.fileName
  vocab.txt      # BERT WordPiece vocabulary (one token per line; line = id)
```

Configuration:

| Env | Meaning |
|-----|---------|
| `TANSEKI_EMBEDDING_DIR` | Directory containing `model.onnx` + `vocab.txt`. |
| `TANSEKI_EMBEDDING_MODEL` | Model id from `EmbeddingModels`; defaults to `all-MiniLM-L6-v2`. |

Build the embedder with `OnnxEmbedders.open(modelDir, model)`; the WordPiece
tokenizer is loaded from `vocab.txt` automatically (or pass a `Tokenizer`).

## Fallback

`OnnxEmbedders.open(...)` returns **null** when:

- the ONNX Runtime is not on the runtime classpath, or
- `model.onnx` is missing, or
- no tokenizer is available (`vocab.txt` missing and none supplied).

Callers treat null as "embeddings disabled": indexing skips vectors and hybrid
search (`/v1/search?mode=hybrid`) degrades to lexical. The store never fails to
start because a model is absent. `OnnxEmbedders.runtimeAvailable()` reports the
runtime condition for diagnostics.
