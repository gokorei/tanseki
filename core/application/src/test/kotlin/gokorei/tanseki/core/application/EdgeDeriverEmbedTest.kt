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
 * A body embed is a link with a rendering instruction, not a different
 * reference: `![[Note]]` names the same document `[[Note]]` would. It keeps
 * its `links-to` edge and additionally derives an `embeds` edge through the
 * same resolution, so traversing `embeds` answers transclusions the way
 * traversing `links-to` answers links.
 */
class EdgeDeriverEmbedTest {
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

    private fun derive(vararg docs: Document) =
        EdgeDeriver(
            InMemoryContextStore().also { store ->
                docs.forEach { store.write(it, "tester", "tester") }
            }
        ).derive(docs.first())

    @Test
    fun `an embed derives both a links-to and an embeds edge`() {
        val edges =
            derive(
                document("note", "See ![[Other]]."),
                document("Other", "body")
            )

        assertEquals(
            setOf(RelTypes.LinksTo to DocId("Other"), RelTypes.Embeds to DocId("Other")),
            edges.map { it.rel to it.dst }.toSet()
        )
    }

    @Test
    fun `an embed resolves through the same path as a link`() {
        val store = InMemoryContextStore()
        val source = document("note", "Embed ![[Other#Section|label]] and link [[Other]].")
        store.write(source, "tester", "tester")
        store.write(document("Other", "body"), "tester", "tester")

        val edges = EdgeDeriver(store).derive(source)

        assertEquals(
            setOf(RelTypes.LinksTo to DocId("Other"), RelTypes.Embeds to DocId("Other")),
            edges.map { it.rel to it.dst }.toSet()
        )
    }

    @Test
    fun `a dangling embed derives nothing like a dangling link`() {
        val edges = derive(document("note", "See ![[Absent]] and [[Also Absent]]."))

        assertTrue(edges.isEmpty(), "a dangling embed leaves no edge behind: $edges")
    }

    @Test
    fun `an embed to an attachment derives nothing without a document`() {
        val edges = derive(document("note", "See ![[logo.svg]]."))

        assertTrue(edges.isEmpty(), "an embed with no document target is not a graph edge: $edges")
    }

    @Test
    fun `a plain link derives no embeds edge`() {
        val edges =
            derive(
                document("note", "See [[Other]]."),
                document("Other", "body")
            )

        assertEquals(listOf(RelTypes.LinksTo to DocId("Other")), edges.map { it.rel to it.dst })
    }
}
