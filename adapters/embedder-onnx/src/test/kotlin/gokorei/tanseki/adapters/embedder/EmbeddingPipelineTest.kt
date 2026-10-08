package gokorei.tanseki.adapters.embedder

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Embedding
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Embedder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class EmbeddingPipelineTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private class FakeEmbedder(
        override val model: String = "fake-model",
        override val dimensions: Int = 3
    ) : Embedder {
        val calls = mutableListOf<List<String>>()

        override fun embed(texts: List<String>): List<FloatArray> {
            calls += texts
            return texts.map { text -> FloatArray(dimensions) { (text.length + it).toFloat() } }
        }
    }

    private class InMemoryEmbeddingRepository : EmbeddingRepository {
        val entries = mutableListOf<Pair<String, String>>()

        override fun hasEmbedding(contentHash: String, model: String): Boolean =
            entries.contains(contentHash to model)

        override fun put(embedding: Embedding, contentHash: String) {
            entries += contentHash to embedding.model
        }
    }

    private fun doc(id: String, hash: String, content: String = "hello") =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = hash,
            revision = RevisionId("rev-1"),
            updatedAt = now
        )

    @Test
    fun `changed documents are embedded only once per content hash`() {
        val embedder = FakeEmbedder()
        val repository = InMemoryEmbeddingRepository()
        val pipeline = EmbeddingPipeline(embedder, repository)
        val documents = listOf(doc("a", "h1"), doc("b", "h2"))

        val first = pipeline.embedChanged(documents)
        val second = pipeline.embedChanged(documents)

        assertEquals(2, first.size)
        assertTrue(second.isEmpty())
        assertEquals(1, embedder.calls.size)
    }

    @Test
    fun `embeddings carry the model and dimension`() {
        val embedder = FakeEmbedder(model = "all-minilm", dimensions = 4)
        val pipeline = EmbeddingPipeline(embedder, InMemoryEmbeddingRepository())

        val embedding = pipeline.embedChanged(listOf(doc("a", "h1"))).single()

        assertEquals("all-minilm", embedding.model)
        assertEquals(4, embedding.dim)
        assertEquals(4, embedding.vector.size)
    }

    @Test
    fun `a changed content hash re-embeds the document`() {
        val embedder = FakeEmbedder()
        val pipeline = EmbeddingPipeline(embedder, InMemoryEmbeddingRepository())

        pipeline.embedChanged(listOf(doc("a", "h1")))
        val produced = pipeline.embedChanged(listOf(doc("a", "h2")))

        assertEquals(1, produced.size)
        assertEquals(2, embedder.calls.size)
    }

    @Test
    fun `chunking produces one embedding per chunk`() {
        val embedder = FakeEmbedder()
        val pipeline =
            EmbeddingPipeline(
                embedder,
                InMemoryEmbeddingRepository(),
                chunker = { listOf("chunk-0", "chunk-1", "chunk-2") }
            )

        val embeddings = pipeline.embedChanged(listOf(doc("a", "h1")))

        assertEquals(3, embeddings.size)
        assertEquals(listOf(0, 1, 2), embeddings.map { it.chunk })
    }

    @Test
    fun `backfill covers the whole corpus`() {
        val store =
            FakeContextStore(
                listOf(doc("a", "h1"), doc("b", "h2"), doc("c", "h3"))
            )
        val embedder = FakeEmbedder()
        val pipeline = EmbeddingPipeline(embedder, InMemoryEmbeddingRepository())

        val written = pipeline.backfill(store)
        val again = pipeline.backfill(store)

        assertEquals(3, written)
        assertEquals(0, again)
    }

    @Test
    fun `mismatched embedder dimension is rejected`() {
        val embedder =
            object : Embedder {
                override val model = "bad"
                override val dimensions = 5

                override fun embed(texts: List<String>) = texts.map { FloatArray(2) }
            }
        val pipeline = EmbeddingPipeline(embedder, InMemoryEmbeddingRepository())
        assertThrows(IllegalArgumentException::class.java) {
            pipeline.embedChanged(listOf(doc("a", "h1")))
        }
    }

    private class FakeContextStore(private val documents: List<Document>) : ContextStore {
        override fun read(id: DocId): Document? = documents.firstOrNull { it.id == id }

        override fun write(doc: Document, message: String, author: String, ifRevision: RevisionId?) =
            Revision(doc.id, RevisionId("r"), author, message, doc.contentHash, doc.updatedAt)

        override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?) =
            throw UnsupportedOperationException()

        override fun list(collection: Collection?) =
            documents.map {
                DocRef(it.id, it.collection, it.path, it.contentHash, it.revision)
            }

        override fun upsertEdge(edge: Edge) = Unit

        override fun removeEdges(src: DocId, rel: RelType?) = Unit

        override fun neighbors(id: DocId, rel: RelType?) = emptyList<Edge>()

        override fun history(id: DocId) = emptyList<Revision>()

        override fun putBlob(bytes: ByteArray) = BlobRef("h", bytes.size.toLong())

        override fun getBlob(ref: BlobRef) = ByteArray(0)

        override fun capabilities() = StoreCapabilities(true, false, false, Consistency.STRONG)
    }
}
