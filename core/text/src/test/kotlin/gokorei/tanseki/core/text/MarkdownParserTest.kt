package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.Link
import gokorei.tanseki.core.domain.TextValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class MarkdownParserTest {
    @Test
    fun `parses frontmatter body headings and wikilinks`() {
        val text =
            """
            ---
            title: API Design
            author: Agent1
            tags: [api, design]
            updated_at: 2026-07-12T10:00:00Z
            content_hash: sha256:abc
            ---

            # API Design

            Describes the API. See [[API Reference|reference]] and [[Glossary]].

            ## Endpoints
            """.trimIndent()

        val parsed = MarkdownParser.parse(text)

        assertEquals("API Design", parsed.frontmatter.title)
        assertEquals("Agent1", parsed.frontmatter.author)
        assertEquals(listOf("api", "design"), parsed.frontmatter.tags)
        assertEquals(Instant.parse("2026-07-12T10:00:00Z"), parsed.frontmatter.updatedAt)
        assertEquals("sha256:abc", parsed.frontmatter.contentHash)
        assertEquals(
            listOf(Heading(1, "API Design"), Heading(2, "Endpoints")),
            parsed.headings
        )
        assertEquals(
            listOf(
                gokorei.tanseki.core.domain
                    .Link("API Reference", "reference"),
                gokorei.tanseki.core.domain
                    .Link("Glossary")
            ),
            parsed.links
        )
        assertTrue(parsed.body.contains("Describes the API"))
        assertTrue(parsed.body.contains("## Endpoints"))
    }

    @Test
    fun `parses block list tags and extra keys`() {
        val text = "---\ntags:\n  - work\n  - inbox\ncustom: kept\n---\nbody"
        val parsed = MarkdownParser.parse(text)

        assertEquals(listOf("work", "inbox"), parsed.frontmatter.tags)
        assertEquals("kept", parsed.frontmatter.text("custom"))
    }

    @Test
    fun `ignores headings and links inside fenced code blocks`() {
        val text =
            """
            ---
            title: X
            ---

            ```
            # not a heading
            [[not a link]]
            ```

            # real heading
            """.trimIndent()

        val parsed = MarkdownParser.parse(text)

        assertEquals(listOf(Heading(1, "real heading")), parsed.headings)
        assertTrue(parsed.links.isEmpty())
    }

    /**
     * Obsidian puts a document and a place inside it in one bracket pair, and
     * Tanseki has to keep them apart: `[[Note#Section]]` names a document, so it
     * must resolve to that document. Gluing the two together made it resolve to
     * nothing, and the graph under-reported the most precisely-linked notes.
     */
    @Test
    fun `splits a wikilink anchor from its document target`() {
        val parsed =
            MarkdownParser.parse(
                "See [[My Note#Section]] and [[Third#^blk]] and [[Deep#A#B]] and [[Plain]]"
            )

        assertEquals(
            listOf(
                Link("My Note", null, "Section"),
                Link("Third", null, "blk"),
                // Only the FIRST `#` separates document from place: the rest is
                // the heading's own text and may contain one.
                Link("Deep", null, "A#B"),
                Link("Plain", null, null)
            ),
            parsed.links
        )
    }

    @Test
    fun `an aliased anchored link keeps its label and splits its target`() {
        val parsed = MarkdownParser.parse("[[My Note#Section|see the section]]")

        assertEquals(listOf(Link("My Note", "see the section", "Section")), parsed.links)
    }

    @Test
    fun `a same-document anchor produces no link`() {
        // `[[#Section]]` points inside the note holding it. There is no other
        // document, and a self-edge would put every intra-note reference into the
        // graph as a cycle.
        val parsed = MarkdownParser.parse("[[#Section]] and [[#^blk]] and [[Real Note]]")

        assertEquals(listOf(Link("Real Note")), parsed.links)
    }

    @Test
    fun `an anchor on a bracketed or malformed link is still ignored`() {
        val parsed = MarkdownParser.parse("[[[nested#Section]] [[unterminated#Section\n[[valid#ok|label]]")

        assertEquals(listOf(Link("valid", "label", "ok")), parsed.links)
    }

    @Test
    fun `an anchored link inside a fence is ignored`() {
        val parsed = MarkdownParser.parse("\n```\n[[Not A Link#Section]]\n```\n[[Real#Section]]\n")

        assertEquals(listOf(Link("Real", null, "Section")), parsed.links)
    }

    @Test
    fun `an empty anchor is dropped rather than kept as an empty string`() {
        val parsed = MarkdownParser.parse("[[Note#]] and [[Note#   ]]")

        assertEquals(listOf(Link("Note"), Link("Note")), parsed.links)
    }

    /**
     * A rename must follow the document and leave the anchor alone.
     *
     * This is the half that fails silently. If the substituted span covered the
     * anchor, `[[Note#Section]]` would become `[[Renamed#Stale]]` — the link still
     * renders, the rename still reports success, and the heading reference is gone.
     */
    @Test
    fun `renaming a link target leaves its anchor untouched`() {
        val renamed =
            MarkdownParser.rewriteWikilinkTargets(
                "See [[My Note#Section]] and [[Other#^blk|alias]] and [[Plain]]",
                rename = { if (it == "My Note" || it == "Other") "Renamed" else null }
            )

        assertEquals(
            "See [[Renamed#Section]] and [[Renamed#^blk|alias]] and [[Plain]]",
            renamed
        )
    }

    @Test
    fun `renaming preserves surrounding whitespace in a link target`() {
        val renamed = MarkdownParser.rewriteWikilinkTargets("[[ Note #Section ]]", rename = { "Renamed" })

        assertEquals("[[ Renamed #Section ]]", renamed)
    }

    @Test
    fun `a rename never rewrites a same-document anchor`() {
        val renamed = MarkdownParser.rewriteWikilinkTargets("[[#Section]] [[Note#Section]]", rename = { "Renamed" })

        assertEquals("[[#Section]] [[Renamed#Section]]", renamed)
    }

    @Test
    fun `renaming does not touch an anchored link inside a fence`() {
        val renamed =
            MarkdownParser.rewriteWikilinkTargets(
                "\n```\n[[My Note#Section]]\n```\n[[My Note#Section]]\n",
                rename = { "Renamed" }
            )

        assertEquals("\n```\n[[My Note#Section]]\n```\n[[Renamed#Section]]\n", renamed)
    }

    @Test
    fun `ignores malformed wikilinks without partial matches`() {
        val parsed = MarkdownParser.parse("[[[nested]] [[unterminated\n[[valid|label]]")

        assertEquals(
            listOf(
                gokorei.tanseki.core.domain
                    .Link("valid", "label")
            ),
            parsed.links
        )
    }

    @Test
    fun `handles a document with no frontmatter`() {
        val parsed = MarkdownParser.parse("# Title\n\n[[a]]")

        assertNull(parsed.frontmatter.title)
        assertEquals(listOf(Heading(1, "Title")), parsed.headings)
        assertEquals(
            listOf(
                gokorei.tanseki.core.domain
                    .Link("a")
            ),
            parsed.links
        )
    }

    @Test
    fun `renders and reparses losslessly`() {
        val frontmatter =
            Frontmatter(
                title = "With: colon",
                author = "dev",
                tags = listOf("a", "b"),
                updatedAt = Instant.parse("2026-01-02T03:04:05Z"),
                contentHash = "sha256:deadbeef",
                values = mapOf("status" to TextValue("draft"))
            )
        val body = "# Heading\n\nSee [[Other|label]].\n"

        val rendered = MarkdownSerializer.render(frontmatter, body)
        val reparsed = MarkdownParser.parse(rendered)

        // Compare the typed view. `rawFrontmatter` is provenance the parser
        // attaches so the block can be re-emitted verbatim; it is not part of
        // what "renders and reparses losslessly" claims, and asserting on it
        // would make this test depend on preservation rather than on meaning.
        assertEquals(frontmatter, reparsed.frontmatter.copy(rawFrontmatter = null))
        assertEquals(body, reparsed.body)
        // The block is still captured, which is what makes a second save safe.
        assertTrue(reparsed.rawFrontmatter != null, "a rendered block should be captured verbatim")
    }

    @Test
    fun `frontmatter scalar edge cases round-trip through canonical serialization`() {
        val frontmatter =
            Frontmatter(
                title = "  quote: \"quoted\" \\\\ slash\nline\tλ🚀",
                author = "comma, bracket], colon: # hash",
                tags = listOf("plain", "a,b", "a:b", "a]b", "a[b", "\"quoted\"", "back\\slash", "\n", "\t", "😀", ""),
                updatedAt = Instant.parse("2026-01-02T03:04:05Z"),
                contentHash = "sha256:deadbeef",
                values =
                    mapOf(
                        "repo" to TextValue("org/repo"),
                        "quote\"key" to TextValue("value\nauthor: injected"),
                        "key: [x], y" to TextValue("unicode \\ value")
                    )
            )

        val serialized = FrontmatterCodec.serialize(frontmatter)
        val reparsed = FrontmatterCodec.parse(serialized)

        assertEquals(frontmatter, reparsed)
        assertTrue(serialized.contains("\\nauthor: injected"), serialized)
        assertTrue(serialized.contains("\\t"), serialized)
        assertFalse(serialized.lines().any { it.startsWith("author: injected") }, serialized)
        assertFalse(serialized.lines().any { it.startsWith("y:") }, serialized)
    }

    @Test
    fun `frontmatter rejects unsupported control characters in keys and values`() {
        val controls = listOf("\u0000", "\u0001", "\u007f", "\u0085")
        controls.forEach { control ->
            assertThrows(IllegalArgumentException::class.java) {
                FrontmatterCodec.serialize(Frontmatter(title = control))
            }
            assertThrows(IllegalArgumentException::class.java) {
                FrontmatterCodec.serialize(Frontmatter(values = mapOf("key" to TextValue(control))))
            }
            assertThrows(IllegalArgumentException::class.java) {
                FrontmatterCodec.serialize(Frontmatter(values = mapOf(control to TextValue("value"))))
            }
            assertThrows(IllegalArgumentException::class.java) {
                FrontmatterCodec.parse("title: \"${control}\"")
            }
        }
    }

    @Test
    fun `quoted key and value escapes cannot create additional frontmatter entries`() {
        val frontmatter =
            Frontmatter(
                title = "safe\nauthor: injected",
                values =
                    mapOf(
                        "key\nnext" to TextValue("value\ntitle: injected"),
                        "" to TextValue("empty key")
                    )
            )

        val serialized = FrontmatterCodec.serialize(frontmatter)
        val reparsed = FrontmatterCodec.parse(serialized)

        assertEquals(frontmatter, reparsed)
        assertEquals("safe\nauthor: injected", reparsed.title)
        assertNull(reparsed.author)
        assertEquals(2, reparsed.values.size)
    }
}
