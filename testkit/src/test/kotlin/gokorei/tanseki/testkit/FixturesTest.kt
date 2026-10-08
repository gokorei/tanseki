package gokorei.tanseki.testkit

import gokorei.tanseki.core.text.MarkdownParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FixturesTest {
    @TempDir
    lateinit var target: Path

    @Test
    fun `example docs follow the schema`() {
        for (doc in listOf("notes/api.md", "notes/design.md", "glossary.md")) {
            val parsed = MarkdownParser.parse(Fixtures.read(doc))
            assertTrue(parsed.frontmatter.title != null, "$doc should have a title")
            assertTrue(parsed.frontmatter.tags.isNotEmpty(), "$doc should have tags")
            assertTrue(parsed.headings.isNotEmpty(), "$doc should have headings")
        }
    }

    @Test
    fun `fixture vault includes wikilinks and attachments`() {
        Fixtures.copyTo(target)

        val api = MarkdownParser.parse(Files.readString(target.resolve("notes/api.md")))
        assertFalse(api.links.isEmpty())
        assertTrue(api.links.any { it.target == "Design Notes" && it.label == "design" })
        assertTrue(Files.exists(target.resolve("attachments/logo.svg")))

        // code fences are not treated as links
        val design = MarkdownParser.parse(Files.readString(target.resolve("notes/design.md")))
        assertFalse(design.links.any { it.target == "not-a-link" })
    }

    @Test
    fun `listed files are all copied`() {
        Fixtures.copyTo(target)
        assertEquals(Fixtures.files.size, Files.walk(target).use { it.filter(Files::isRegularFile).count().toInt() })
    }

    @Test
    fun `obsidian files are all copied including dot-dirs and odd names`() {
        Fixtures.copyObsidianTo(target)

        val copied =
            Files.walk(target).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .map { target.relativize(it).toString().replace('\\', '/') }
                    .toList()
                    .sorted()
            }
        assertEquals(Fixtures.obsidianFiles.sorted(), copied)
    }

    @Test
    fun `every obsidian note parses without throwing`() {
        for (relative in Fixtures.obsidianFiles.filter { it.endsWith(".md", ignoreCase = true) }) {
            val text = Fixtures.readObsidian(relative)
            val parsed = MarkdownParser.parse(text)
            assertTrue(parsed.body.isNotEmpty() || parsed.frontmatterError != null || text.isEmpty())
        }
    }
}
