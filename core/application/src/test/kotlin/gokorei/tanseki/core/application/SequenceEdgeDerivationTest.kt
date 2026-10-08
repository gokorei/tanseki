package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
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

/**
 * A `files` sequence derives one edge per path.
 *
 * This used to be a string that the deriver took apart: `removePrefix("[")`,
 * `split(",")`, strip the quotes. That worked until a path contained a comma,
 * at which point one path became two and a third was garbage. Reading the
 * sequence directly is what makes the count mean something.
 */
class SequenceEdgeDerivationTest {
    private val now = Instant.fromEpochSeconds(1_700_000_000)

    private fun document(id: String, frontmatter: Frontmatter): Document =
        Document(
            id = DocId(id),
            collection = Collection("c"),
            path = "$id.md",
            content = "body",
            contentHash = "hash-$id",
            revision = RevisionId("rev"),
            updatedAt = now,
            frontmatter = frontmatter
        )

    private fun embeddable(vararg paths: String): Frontmatter =
        Frontmatter(values = mapOf("files" to SequenceValue(paths.map(::TextValue))))

    private fun derive(id: String, frontmatter: Frontmatter, targets: List<String>): List<Edge> {
        val store = InMemoryContextStore()
        // Derivation reads the ContextStore, not the Lookup, so a Lookup that
        // records and does nothing else is enough to exercise it.
        val indexer = Indexer(store, RecordingLookup())
        for (target in targets) store.write(document(target, Frontmatter()), "m", "tester")
        val source = document(id, frontmatter)
        store.write(source, "m", "tester")
        indexer.index(source)
        return store.neighbors(DocId(id))
    }

    private class RecordingLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    @Test
    fun `a sequence of three paths derives three distinct embeds edges`() {
        val edges = derive("src", embeddable("a.md", "b.md", "c.md"), listOf("a", "b", "c"))

        assertEquals(
            setOf(DocId("a"), DocId("b"), DocId("c")),
            edges.filter { it.rel == RelTypes.Embeds }.map { it.dst }.toSet()
        )
        assertEquals(3, edges.count { it.rel == RelTypes.Embeds })
    }

    @Test
    fun `a path containing a comma derives exactly one edge`() {
        // The case the string-splitting deriver got wrong: this split into two
        // paths plus an empty one, so the edge either pointed nowhere or at a
        // document that did not exist.
        val edges = derive("src", embeddable("we,ird.md"), listOf("we,ird"))

        assertEquals(listOf(DocId("we,ird")), edges.filter { it.rel == RelTypes.Embeds }.map { it.dst })
    }

    @Test
    fun `a scalar still derives one edge`() {
        val frontmatter = Frontmatter(values = mapOf("files" to TextValue("only.md")))
        val edges = derive("src", frontmatter, listOf("only"))

        assertEquals(listOf(DocId("only")), edges.filter { it.rel == RelTypes.Embeds }.map { it.dst })
    }

    @Test
    fun `an empty sequence derives nothing`() {
        val edges = derive("src", embeddable(), listOf("a"))

        assertTrue(edges.none { it.rel == RelTypes.Embeds })
    }

    @Test
    fun `a sequence mixed with other reference keys keeps deriving those`() {
        // `files` still resolves; `repo` names no document here, so under
        // resolving `references` semantics it derives nothing rather than an
        // edge to a synthetic id.
        val frontmatter =
            Frontmatter(
                values =
                    mapOf(
                        "repo" to TextValue("org/repo"),
                        "files" to SequenceValue(listOf(TextValue("a.md"), TextValue("b.md")))
                    )
            )
        val edges = derive("src", frontmatter, listOf("a", "b"))

        assertTrue(edges.none { it.rel == RelTypes.References }, "a dangling repo value leaves no edge: $edges")
        assertEquals(2, edges.count { it.rel == RelTypes.Embeds })
    }

    @Test
    fun `a repo value naming a document derives a references edge`() {
        val frontmatter =
            Frontmatter(
                values =
                    mapOf(
                        "repo" to TextValue("org/repo"),
                        "files" to SequenceValue(listOf(TextValue("a.md")))
                    )
            )
        val edges = derive("src", frontmatter, listOf("a", "org/repo"))

        assertEquals(listOf(DocId("org/repo")), edges.filter { it.rel == RelTypes.References }.map { it.dst })
    }
}
