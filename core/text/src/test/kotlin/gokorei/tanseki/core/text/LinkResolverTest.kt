package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * Which document a wikilink names.
 *
 * A wikilink is prose, not a path expression, and Obsidian accepts several
 * spellings of the same target. This suite pins both directions: the spellings
 * that must resolve, and the malformed ones that must *not* resolve — an
 * unresolvable link is a missing edge, while a wrongly-resolved one is a lie
 * about the graph.
 */
class LinkResolverTest {
    private val at = Instant.fromEpochSeconds(0)

    private fun ref(id: String, path: String = "$id.md") =
        DocRef(DocId(id), Collection("vault"), path, null, null, at)

    private val refs =
        listOf(
            ref("notes/foo"),
            ref("a/b/deep"),
            ref("Note One"),
            // A document whose id *ends* in something another link ends in, so a
            // sloppy suffix match would resolve the wrong one of the two.
            ref("notes/foobar")
        )

    private fun resolve(target: String): DocId? =
        LinkResolver.resolve(target, exists = { it in refs.map(DocRef::id) }, refs = refs)

    @Test
    fun `an explicit markdown suffix resolves to the document`() {
        assertEquals(DocId("notes/foo"), resolve("notes/foo.md"))
    }

    @Test
    fun `an uppercase markdown suffix resolves too`() {
        // macOS and Windows filesystems are case-insensitive, and Obsidian opens
        // it, so refusing it would make a link that works in the editor fail here.
        assertEquals(DocId("notes/foo"), resolve("notes/foo.MD"))
    }

    @Test
    fun `a bare id and a full path both resolve`() {
        assertEquals(DocId("notes/foo"), resolve("notes/foo"))
        assertEquals(DocId("notes/foo"), resolve("notes/foo.md"))
    }

    @Test
    fun `a trailing path segment resolves a nested doc`() {
        // The link an author actually writes: `[[deep]]`, not `[[a/b/deep]]`.
        assertEquals(DocId("a/b/deep"), resolve("deep"))
        assertEquals(DocId("a/b/deep"), resolve("deep.md"))
    }

    @Test
    fun `a relative and a vault-absolute prefix resolve the same document`() {
        assertEquals(DocId("notes/foo"), resolve("./notes/foo.md"))
        assertEquals(DocId("notes/foo"), resolve("/notes/foo.md"))
    }

    @Test
    fun `a link with spaces resolves`() {
        assertEquals(DocId("Note One"), resolve("Note One"))
        assertEquals(DocId("Note One"), resolve("Note One.md"))
    }

    @Test
    fun `a suffix match does not resolve the wrong document`() {
        // `foo.md` must not match `notes/foobar` by prefix.
        assertEquals(DocId("notes/foo"), resolve("foo.md"))
    }

    @Test
    fun `an unknown target is unresolved`() {
        assertNull(resolve("absent"))
        assertNull(resolve("absent.md"))
        assertNull(resolve("notes/foo.txt"))
        assertNull(resolve("notes/foo.md.md"))
    }

    @Test
    fun `a traversal-shaped target is unresolved, not an escape`() {
        assertNull(resolve("../secret"))
        assertNull(resolve("../../etc/passwd"))
        assertNull(resolve("./../notes/foo"))
    }

    @Test
    fun `an empty or blank target is unresolved`() {
        assertNull(resolve(""))
        assertNull(resolve("   "))
    }

    @Test
    fun `an empty path segment is unresolved`() {
        // `notes//foo` normalises to nothing plausible, so it must not be
        // normalised into a document that exists.
        assertNull(resolve("notes//foo.md"))
        assertNull(resolve("notes/"))
    }

    @Test
    fun `a target naming an existing document by its id wins over a path match`() {
        assertEquals(DocId("notes/foo"), resolve("notes/foo"))
    }
}
