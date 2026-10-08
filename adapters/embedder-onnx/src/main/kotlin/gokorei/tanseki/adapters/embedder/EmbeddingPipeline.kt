package gokorei.tanseki.adapters.embedder

import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Embedding

/**
 * Records which `(content_hash, model)` pairs have already been embedded. Keeping
 * this behind a small interface lets the Lucene Lookup (or a dedicated table)
 * provide it without the pipeline depending on the index.
 */
interface EmbeddingRepository {
    fun hasEmbedding(contentHash: String, model: String): Boolean

    fun put(embedding: Embedding, contentHash: String)
}

/**
 * Embeds documents exactly once per `(content_hash, model)` and produces
 * [Embedding] domain values carrying the model and dimension. [backfill] runs the
 * same logic over the whole corpus, which is also what a model change replays.
 */
class EmbeddingPipeline(
    private val embedder: gokorei.tanseki.core.ports.Embedder,
    private val repository: EmbeddingRepository,
    private val chunker: (Document) -> List<String> = { listOf(it.content) }
) {
    /** Embed documents that changed since the last run; skips unchanged ones. */
    fun embedChanged(documents: List<Document>): List<Embedding> {
        val chunked =
            documents
                .filter { !repository.hasEmbedding(it.contentHash, embedder.model) }
                .map { it to chunker(it) }
                .filter { it.second.isNotEmpty() }
        if (chunked.isEmpty()) return emptyList()

        val chunks = chunked.flatMap { it.second }
        val vectors = embedder.embed(chunks)
        check(vectors.size == chunks.size) {
            "embedder returned ${vectors.size} vectors for ${chunks.size} chunks"
        }

        val produced = mutableListOf<Embedding>()
        var cursor = 0
        for ((document, documentChunks) in chunked) {
            for (index in documentChunks.indices) {
                val vector = vectors[cursor++]
                require(vector.size == embedder.dimensions) {
                    "embedder returned dim ${vector.size}, expected ${embedder.dimensions}"
                }
                val embedding =
                    Embedding(
                        docId = document.id,
                        chunk = index,
                        model = embedder.model,
                        dim = vector.size,
                        vector = vector
                    )
                repository.put(embedding, document.contentHash)
                produced += embedding
            }
        }
        return produced
    }

    /** Backfill the whole corpus; returns the number of new embeddings written. */
    fun backfill(store: gokorei.tanseki.core.ports.ContextStore): Int {
        val documents = store.list().mapNotNull { store.read(it.id) }
        return embedChanged(documents).size
    }
}
