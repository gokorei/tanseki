package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.testkit.InMemoryContextStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * An anchor is a place inside a document, not another document.
 *
 * `[[Note#Section]]` names a document and a heading. Tanseki kept the whole string
 * as the target, so it matched no id and no edge was derived — the graph
 * under-reported exactly the notes that were linked most precisely, and looked
 * broken on import for notes that were perfectly fine.
 */
class EdgeDeriverAnchorTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun document(
        id: String,
        content: String
    ) = Document(
        id = DocId(id),
        collection = Collection("vault"),
        path = "$id.md",
        content = content,
        contentHash = "hash-$id",
        revision = RevisionId("rev-$id"),
        updatedAt = now,
        frontmatter = Frontmatter()
    )

    private fun derive(vararg docs: Document): List<Pair<DocId, DocId>> {
        val store = InMemoryContextStore()
        docs.forEach { store.write(it, "tester", "tester") }
        return EdgeDeriver(store)
            .derive(docs.first())
            .filter { it.rel == RelTypes.LinksTo }
            .map { it.src to it.dst }
    }

    @Test
    fun `an anchored link derives an edge to the document`() {
        val edges =
            derive(
                document("note", "See [[Other Note#Section]]."),
                document("Other Note", "body")
            )

        assertEquals(listOf(DocId("note") to DocId("Other Note")), edges)
    }

    @Test
    fun `a block reference derives an edge to the document`() {
        val edges =
            derive(
                document("note", "See [[Other Note#^blk]]."),
                document("Other Note", "body")
            )

        assertEquals(listOf(DocId("note") to DocId("Other Note")), edges)
    }

    @Test
    fun `an aliased anchored link derives an edge and keeps the label`() {
        val store = InMemoryContextStore()
        val source = document("note", "See [[Other Note#Section|the section]].")
        store.write(source, "tester", "tester")
        store.write(document("Other Note", "body"), "tester", "tester")

        val edges = EdgeDeriver(store).derive(source)

        assertEquals(listOf(DocId("Other Note")), edges.map { it.dst })
        assertEquals("the section", edges.single().props["label"])
    }

    @Test
    fun `a same-document anchor creates no self-edge`() {
        val edges = derive(document("note", "Jump to [[#Section]] and [[#^blk]]."))

        assertTrue(edges.isEmpty(), "an intra-note reference is not a graph edge: $edges")
    }

    @Test
    fun `an anchored link to a missing document still derives nothing`() {
        val edges = derive(document("note", "See [[Absent#Section]]."))

        assertTrue(edges.isEmpty())
    }

    @Test
    fun `a bare link behaves exactly as before`() {
        val edges =
            derive(
                document("note", "See [[Other]] and [[Third#Section]]."),
                document("Other", "body"),
                document("Third", "body")
            )

        assertEquals(
            setOf(DocId("Other"), DocId("Third")),
            edges.map { it.second }.toSet(),
            "an anchored link must resolve the same way a bare one does"
        )
    }
}
