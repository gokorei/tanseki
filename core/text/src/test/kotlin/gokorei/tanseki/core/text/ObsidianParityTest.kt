package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.Link
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The Obsidian parity surface: aliases, embeds, attachments, canvases, daily
 * notes, and templates.
 *
 * Each of these is a convention Tanseki used to ignore: `aliases` fell into the
 * untyped value bag, `![[embed]]` was indistinguishable from a link, a `.canvas`
 * file was an opaque blob, and a daily note was whatever string the caller
 * built. These pin the recognition rules.
 */
class ObsidianParityTest {
    @Test
    fun `aliases parse from a block sequence`() {
        val parsed = MarkdownParser.parse("---\naliases:\n  - Foo\n  - Bar\n---\nbody")

        assertEquals(listOf("Foo", "Bar"), parsed.frontmatter.aliases)
        assertNull(parsed.frontmatter.values["aliases"])
    }

    @Test
    fun `aliases parse from an inline list and from a single scalar`() {
        assertEquals(
            listOf("Foo", "Bar"),
            MarkdownParser.parse("---\naliases: [Foo, Bar]\n---\nbody").frontmatter.aliases
        )
        assertEquals(
            listOf("Only"),
            MarkdownParser.parse("---\naliases: Only\n---\nbody").frontmatter.aliases
        )
    }

    @Test
    fun `aliases are absent by default and survive a round trip`() {
        assertEquals(emptyList<String>(), MarkdownParser.parse("body").frontmatter.aliases)

        val serialized =
            FrontmatterCodec.serialize(
                MarkdownParser.parse("---\naliases:\n  - Foo\n---\nbody").frontmatter
            )
        assertEquals(
            listOf("Foo"),
            FrontmatterCodec.parse(serialized).aliases
        )
        assertTrue(serialized.contains("aliases: [Foo]"), serialized)
    }

    @Test
    fun `an embed is a link with the marker recorded`() {
        val parsed = MarkdownParser.parse("See ![[My Note#Section|label]] and [[Plain]].")

        assertEquals(
            listOf(Link("My Note", "label", "Section", embed = true), Link("Plain")),
            parsed.links
        )
        assertEquals(listOf(Link("My Note", "label", "Section", embed = true)), parsed.embeds)
    }

    @Test
    fun `an embed without the marker is a plain link`() {
        val parsed = MarkdownParser.parse("[[Note]]")

        assertFalse(parsed.links.single().embed)
        assertTrue(parsed.embeds.isEmpty())
    }

    @Test
    fun `an embed in a fence is ignored like any link`() {
        val parsed = MarkdownParser.parse("```\n![[Hidden]]\n```\n![[Shown]]")

        assertEquals(listOf(Link("Shown", embed = true)), parsed.embeds)
    }

    @Test
    fun `renaming an embed target keeps the marker`() {
        val renamed =
            MarkdownParser.rewriteWikilinkTargets("![[Old]] and [[Old]]", rename = { "New" })

        assertEquals("![[New]] and [[New]]", renamed)
    }

    @Test
    fun `non-markdown targets are attachments and notes are not`() {
        assertTrue(ObsidianParity.isAttachmentTarget("assets/photo.png"))
        assertTrue(ObsidianParity.isAttachmentTarget("clip.mp3"))
        assertTrue(ObsidianParity.isAttachmentTarget("archive.tar.gz"))
        assertTrue(ObsidianParity.isAttachmentTarget("assets/photo.PNG"))
        assertFalse(ObsidianParity.isAttachmentTarget("notes/foo"))
        assertFalse(ObsidianParity.isAttachmentTarget("notes/foo.md"))
        assertFalse(ObsidianParity.isAttachmentTarget("notes/foo.MD"))
        assertFalse(ObsidianParity.isAttachmentTarget(".gitignore"))
        assertFalse(ObsidianParity.isAttachmentTarget("draft."))
        assertFalse(ObsidianParity.isAttachmentTarget(""))
        assertFalse(ObsidianParity.isAttachmentTarget("   "))
    }

    @Test
    fun `paths classify as markdown canvas or attachment`() {
        assertEquals(DocumentKind.MARKDOWN, ObsidianParity.kindForPath("notes/foo.md"))
        assertEquals(DocumentKind.MARKDOWN, ObsidianParity.kindForPath("NOTE.MD"))
        assertEquals(DocumentKind.CANVAS, ObsidianParity.kindForPath("board.canvas"))
        assertEquals(DocumentKind.CANVAS, ObsidianParity.kindForPath("Board.CANVAS"))
        assertEquals(DocumentKind.ATTACHMENT, ObsidianParity.kindForPath("assets/photo.png"))
        assertEquals(DocumentKind.ATTACHMENT, ObsidianParity.kindForPath("clip.mp3"))
    }

    @Test
    fun `a canvas path is recognised whatever the case`() {
        assertTrue(ObsidianParity.isCanvasPath("board.canvas"))
        assertTrue(ObsidianParity.isCanvasPath("Board.CANVAS"))
        assertFalse(ObsidianParity.isCanvasPath("notes/foo.md"))
        assertFalse(ObsidianParity.isCanvasPath("canvas.png"))
    }

    @Test
    fun `known attachment extensions hint a media type and the rest do not`() {
        assertEquals("image/png", ObsidianParity.mediaTypeForExtension("png"))
        assertEquals("image/png", ObsidianParity.mediaTypeForExtension("PNG"))
        assertEquals("image/jpeg", ObsidianParity.mediaTypeForExtension("jpg"))
        assertEquals("application/pdf", ObsidianParity.mediaTypeForExtension("pdf"))
        assertNull(ObsidianParity.mediaTypeForExtension("xyz"))
        assertNull(ObsidianParity.mediaTypeForExtension(""))
    }

    @Test
    fun `a daily note path is the ISO day with markdown suffix`() {
        val day = LocalDate.of(2026, 10, 8)

        assertEquals("2026-10-08.md", ObsidianParity.dailyNotePath(day))
        assertEquals("daily/2026-10-08.md", ObsidianParity.dailyNotePath(day, "daily"))
        assertEquals("daily/2026-10-08.md", ObsidianParity.dailyNotePath(day, "/daily/"))
    }

    @Test
    fun `a daily note is recognised by filename and by real date`() {
        assertTrue(ObsidianParity.isDailyNotePath("2026-10-08.md"))
        assertTrue(ObsidianParity.isDailyNotePath("daily/2026-10-08.md"))
        assertFalse(ObsidianParity.isDailyNotePath("2026-13-99.md"))
        assertFalse(ObsidianParity.isDailyNotePath("2026-10-8.md"))
        assertFalse(ObsidianParity.isDailyNotePath("notes/foo.md"))
        assertFalse(ObsidianParity.isDailyNotePath("2026-10-08.canvas"))
    }

    @Test
    fun `a template expands the variables it knows and keeps the rest`() {
        val rendered =
            ObsidianParity.applyTemplate(
                "# {{title}}\nCreated {{ date }}.\nKeep {{plugin:field}}.",
                mapOf("title" to "Standup", "date" to "2026-10-08")
            )

        assertEquals("# Standup\nCreated 2026-10-08.\nKeep {{plugin:field}}.", rendered)
    }

    @Test
    fun `a template without tokens is untouched`() {
        assertEquals("plain", ObsidianParity.applyTemplate("plain", mapOf("a" to "b")))
        assertEquals("", ObsidianParity.applyTemplate("", emptyMap()))
    }
}
