package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * The rewriter and the resolver have to agree with the parser, because the
 * indexer decides whether a link resolves and the rename decides whether to
 * rewrite it. These pin the cases where the three could drift apart.
 */
class WikilinkRewriterTest {
    private val from = DocId("notes/old")
    private val to = DocId("folder/new")

    private fun ref(id: String, path: String = "$id.md") =
        DocRef(
            id = DocId(id),
            collection =
                gokorei.tanseki.core.domain
                    .Collection("vault"),
            path = path,
            contentHash = null,
            revision = null,
            updatedAt = Instant.fromEpochSeconds(0)
        )

    private fun retarget(
        content: String,
        refs: List<DocRef> = listOf(ref("notes/old"), ref("folder/new"))
    ): String =
        WikilinkRewriter.retarget(
            content,
            { target ->
                LinkResolver.resolve(target, exists = { candidate ->
                    refs.any { it.id == candidate }
                }, refs = refs)
            },
            from,
            to
        )

    @Test
    fun `a link to the renamed document is re-pointed at its full id`() {
        assertEquals("see [[folder/new]] now", retarget("see [[notes/old]] now"))
    }

    @Test
    fun `an aliased link keeps its label and only the target changes`() {
        // The label is prose the author wrote for display; the target is the
        // reference that has to follow the document.
        assertEquals("[[folder/new|the old note]]", retarget("[[notes/old|the old note]]"))
    }

    @Test
    fun `an unrelated link is left exactly as written`() {
        val refs = listOf(ref("notes/old"), ref("notes/other"))
        val content = "[[notes/other]] and [[notes/old]]"
        assertEquals("[[notes/other]] and [[folder/new]]", retarget(content, refs))
    }

    @Test
    fun `whitespace inside a link is preserved`() {
        // The target is trimmed for matching, so the substituted span has to be
        // located rather than assumed: the spaces belong to the source text.
        assertEquals("[[ folder/new ]]", retarget("[[ notes/old ]]"))
    }

    @Test
    fun `a link nested in another bracket is not a link`() {
        val content = "[[outer [[notes/old]] inner]]"
        assertEquals(
            content,
            retarget(content),
            "a nested [[ is not a wikilink to the parser, so it must not be rewritten"
        )
    }

    @Test
    fun `fenced code is not rewritten`() {
        // The parser skips fenced blocks, so a rewriter that did not would corrupt
        // an example that happens to mention the old name.
        val content = "```\n[[notes/old]]\n```\n[[notes/old]]"
        assertEquals("```\n[[notes/old]]\n```\n[[folder/new]]", retarget(content))
    }

    @Test
    fun `a trailing newline survives the rewrite`() {
        assertEquals("[[folder/new]]\n", retarget("[[notes/old]]\n"))
        assertEquals("[[folder/new]]", retarget("[[notes/old]]"))
    }

    @Test
    fun `text with no links is returned unchanged`() {
        val content = "plain text, no links at all\nsecond line"
        assertTrue(retarget(content) == content)
    }

    @Test
    fun `the resolver accepts the forms a person actually writes`() {
        val refs = listOf(ref("daily/notes", "daily/notes.md"))
        val exists = { candidate: DocId -> refs.any { it.id == candidate } }
        listOf("daily/notes", "daily/notes.md", "notes").forEach { form ->
            assertEquals(DocId("daily/notes"), LinkResolver.resolve(form, exists, refs), "should resolve '$form'")
        }
        assertEquals(null, LinkResolver.resolve("", exists, refs))
    }

    @Test
    fun `the resolver prefers an exact id over a trailing-segment match`() {
        // "notes" matches `daily/notes` by its last segment, but if a document is
        // literally called "notes" that is the one a bare [[notes]] means.
        val exact = ref("notes")
        val nested = ref("daily/notes", "daily/notes.md")
        val refs = listOf(nested, exact)
        assertEquals(DocId("notes"), LinkResolver.resolve("notes", { refs.any { r -> r.id == it } }, refs))
    }
}
