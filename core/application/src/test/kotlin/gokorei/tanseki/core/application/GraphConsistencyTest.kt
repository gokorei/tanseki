package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.domain.TextValue
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.InMemoryContextStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class GraphConsistencyTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun document(
        id: String,
        content: String,
        frontmatter: Frontmatter = Frontmatter(),
        updatedAt: Instant = now,
        revision: String = "rev-$id"
    ) = Document(
        id = DocId(id),
        collection = Collection("vault"),
        path = "$id.md",
        content = content,
        contentHash = "hash-$id-$content",
        revision = RevisionId(revision),
        updatedAt = updatedAt,
        frontmatter = frontmatter
    )

    private class RecordingLookup : Lookup {
        val documents = linkedMapOf<DocId, Document>()
        val edges = linkedMapOf<DocId, List<Edge>>()

        override fun index(doc: Document, edges: List<Edge>) {
            documents[doc.id] = doc
            this.edges[doc.id] = edges
        }

        override fun remove(id: DocId) {
            documents.remove(id)
            edges.remove(id)
            edges.entries.toList().forEach { (source, outgoing) ->
                val remaining = outgoing.filterNot { it.dst == id }
                if (remaining.isEmpty()) edges.remove(source) else edges[source] = remaining
            }
        }

        override fun indexedIds(): Set<DocId> = documents.keys

        override fun clear() {
            documents.clear()
            edges.clear()
        }

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> =
            edges[id].orEmpty().filter { it.rel == rel }.map { it.dst }

        override fun rebuild(store: ContextStore) {
            documents.clear()
            edges.clear()
            store.list().forEach { ref ->
                store.read(ref.id)?.let { index(it, store.neighbors(ref.id)) }
            }
        }
    }

    private class ExternalStore : ContextStore by InMemoryContextStore() {
        val documents = linkedMapOf<DocId, Document>()

        override fun read(id: DocId): Document? = documents[id]

        override fun list(collection: Collection?): List<DocRef> =
            documents.values
                .filter { collection == null || it.collection == collection }
                .map { DocRef(it.id, it.collection, it.path, it.contentHash, it.revision, it.updatedAt) }

        fun externalWrite(document: Document) {
            documents[document.id] = document
        }
    }

    /**
     * A `repo`/`pr`/`jira` value resolves to a document the way a `files` value
     * does. A value that names no document derives no edge rather than an edge
     * to a synthetic id: dangling references are absent from the graph, and the
     * documents carrying them are found by filtering, not by traversing.
     * See `docs/document-schema.md`.
     */
    @Test
    fun `typed frontmatter derives author file and resolving reference edges`() {
        val store = InMemoryContextStore()
        val lookup = RecordingLookup()
        val indexer = Indexer(store, lookup)
        store.write(document("org/repo", "the repository note"), "m", "tester")
        store.write(document("assets/chart", "chart"), "m", "tester")
        store.write(
            document(
                "source",
                "typed references",
                Frontmatter(
                    author = "Ada",
                    values =
                        mapOf(
                            "repo" to TextValue("org/repo"),
                            "pr" to TextValue("42"),
                            "jira" to TextValue("PROJ-1"),
                            "files" to SequenceValue(listOf(TextValue("assets/chart")))
                        )
                )
            ),
            "m",
            "tester"
        )

        indexer.index(store.read(DocId("org/repo"))!!)
        indexer.index(store.read(DocId("assets/chart"))!!)
        indexer.index(store.read(DocId("source"))!!)

        assertEquals(
            setOf(
                RelTypes.Mentions to DocId("author/Ada"),
                RelTypes.References to DocId("org/repo"),
                RelTypes.Embeds to DocId("assets/chart")
            ),
            store.neighbors(DocId("source")).map { it.rel to it.dst }.toSet()
        )
    }

    @Test
    fun `a reference value that names no document derives no edge`() {
        val store = InMemoryContextStore()
        store.write(document("assets/chart", "chart"), "m", "tester")
        store.write(
            document(
                "source",
                "dangling references",
                Frontmatter(
                    values =
                        mapOf(
                            "repo" to TextValue("org/missing"),
                            "pr" to TextValue("42"),
                            "jira" to TextValue("PROJ-9")
                        )
                )
            ),
            "m",
            "tester"
        )

        val edges = EdgeDeriver(store).derive(store.read(DocId("source"))!!)

        assertTrue(edges.isEmpty(), "dangling references leave no edge behind: $edges")
    }

    @Test
    fun `a references edge appears when its target is written later`() {
        val store = InMemoryContextStore()
        val lookup = RecordingLookup()
        val indexer = Indexer(store, lookup)
        store.write(
            document(
                "source",
                "written first",
                Frontmatter(values = mapOf("repo" to TextValue("later/target")))
            ),
            "m",
            "tester"
        )
        indexer.index(store.read(DocId("source"))!!)
        // The referrer's own projection is delivered, so a later write may
        // repair it: while its operation is still queued the referrer is owned
        // by the outbox and repair leaves it alone.
        store.projectionOperations().pending(Int.MAX_VALUE).forEach { store.projectionOperations().complete(it) }
        assertTrue(store.neighbors(DocId("source")).isEmpty())

        store.write(document("later/target", "written second"), "m", "tester")
        indexer.index(store.read(DocId("later/target"))!!)

        assertEquals(
            listOf(RelTypes.References to DocId("later/target")),
            store.neighbors(DocId("source")).map { it.rel to it.dst }
        )
        assertEquals(
            listOf(RelTypes.References to DocId("later/target")),
            lookup.edges[DocId("source")]?.map { it.rel to it.dst }
        )
    }

    @Test
    fun `update and delete repair outgoing and incoming edges`() {
        val store = InMemoryContextStore()
        val lookup = RecordingLookup()
        val indexer = Indexer(store, lookup)
        store.write(document("target", "target"), "m", "tester")
        store.write(document("source", "See [[target]]"), "m", "tester")

        indexer.index(store.read(DocId("target"))!!)
        indexer.index(store.read(DocId("source"))!!)
        assertEquals(listOf(DocId("target")), lookup.traverse(DocId("source"), RelTypes.LinksTo, 1))

        store.write(document("source", "See [[missing]]", revision = "rev-source-2"), "m", "tester")
        indexer.index(store.read(DocId("source"))!!)
        assertTrue(store.neighbors(DocId("source")).isEmpty())
        assertTrue(lookup.traverse(DocId("source"), RelTypes.LinksTo, 1).isEmpty())

        store.write(document("source", "See [[target]]", revision = "rev-source-3"), "m", "tester")
        indexer.index(store.read(DocId("source"))!!)
        assertEquals(listOf(DocId("target")), store.neighbors(DocId("source")).map { it.dst })

        store.delete(DocId("target"), "m", "tester")
        indexer.remove(DocId("target"))
        assertTrue(store.neighbors(DocId("target")).isEmpty())
        assertTrue(
            lookup.edges.values
                .flatten()
                .none { it.src == DocId("target") || it.dst == DocId("target") }
        )
    }

    @Test
    fun `external edit repairs canonical edges through reconciliation`() {
        val store = ExternalStore()
        val lookup = RecordingLookup()
        val indexer = Indexer(store, lookup)
        val reconciler = Reconciler(store, lookup, indexer)
        store.externalWrite(document("target", "target"))
        store.externalWrite(document("source", "See [[target]]"))
        reconciler.reconcile()
        assertEquals(listOf(DocId("target")), store.neighbors(DocId("source")).map { it.dst })

        store.externalWrite(
            document(
                "source",
                "See [[missing]]",
                updatedAt = Instant.fromEpochSeconds(1_700_000_001)
            )
        )
        val report = reconciler.reconcile()

        assertEquals(1, report.updated)
        assertTrue(store.neighbors(DocId("source")).isEmpty())
        assertTrue(lookup.traverse(DocId("source"), RelTypes.LinksTo, 1).isEmpty())
    }

    @Test
    fun `full rebuild removes stale canonical edges and matches live lookup`() {
        val store = InMemoryContextStore()
        store.write(document("target", "target"), "m", "tester")
        store.write(document("source", "See [[target]]"), "m", "tester")
        store.upsertEdge(Edge(DocId("deleted-source"), DocId("target"), RelTypes.LinksTo))
        val lookup = RecordingLookup()

        val report = FullRebuilder(store, lookup, Indexer(store, lookup)).rebuild()

        assertTrue(report.successful, report.toString())
        assertTrue(store.neighbors(DocId("deleted-source")).isEmpty())
        assertEquals(
            store.list().flatMap { ref -> store.neighbors(ref.id) }.toSet(),
            lookup.edges.values
                .flatten()
                .toSet()
        )
    }
}
