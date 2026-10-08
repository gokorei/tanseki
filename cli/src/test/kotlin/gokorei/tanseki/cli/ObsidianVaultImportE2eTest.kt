package gokorei.tanseki.cli

import gokorei.tanseki.cli.transfer.SanitizeReason
import gokorei.tanseki.composition.Compositions
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.core.application.EdgeDeriver
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.MappingValue
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.text.MarkdownParser
import gokorei.tanseki.testkit.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Validates the vault import end-to-end against a high-fidelity Obsidian-shaped
 * vault (5XGFTBNP): nested property lists and maps, folded scalars, inline
 * `aliases`/`cssclasses`, dataview constructs, callouts, heading and block
 * anchors, embeds, unicode and spaced names, sanitize-shaped names, and an
 * `.obsidian/` directory that must never import.
 *
 * Answers the questions synthetics could not: parser survival on real-world
 * property blocks, the unresolved-wikilink rate and its causes, sanitize
 * frequency, and whether anything is silently dropped. The critical invariant
 * is byte-identity: `documents.content` must equal the source file exactly.
 */
class ObsidianVaultImportE2eTest {
    @TempDir
    lateinit var tmp: Path

    /** Source path -> id the import must store it under. */
    private val expectedIds =
        mapOf(
            "notes/Index.md" to "notes/Index",
            "notes/Project Alpha.md" to "notes/Project Alpha",
            "notes/daily/2024-01-15.md" to "notes/daily/2024-01-15",
            "notes/Glossário — café.md" to "notes/Glossário — café",
            "notes/Reference.md" to "notes/Reference",
            "notes/Plain.md" to "notes/Plain",
            "notes/Empty.md" to "notes/Empty",
            "notes/Duplicate Keys.md" to "notes/Duplicate Keys",
            "notes/UPPER.MD" to "notes/UPPER",
            "notes/trailing space .md" to "notes/trailing space"
        )

    private fun importVault(): Path {
        val source = Files.createDirectories(tmp.resolve("vault"))
        Fixtures.copyObsidianTo(source)
        val library = tmp.resolve("library.db")
        val report = CliOperations.importVault(source, library)

        assertEquals(10, report.documentsImported, "every note must import, got $report")
        assertEquals(0, report.documentsSkipped)
        assertTrue(report.unreadable.isEmpty(), "unreadable must be zero, got ${report.unreadable}")
        assertTrue(report.conflictingIds.isEmpty(), "no conflicting ids expected, got ${report.conflictingIds}")
        assertTrue(
            report.sanitizeCollisions.isEmpty(),
            "no post-sanitize collisions expected, got ${report.sanitizeCollisions}"
        )
        return library
    }

    private fun libraryIds(library: Path): Set<String> =
        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            composition.store
                .list()
                .map { it.id.value }
                .toSet()
        }

    @Test
    fun `every note is accounted for and tool state never imports`() {
        val library = importVault()

        assertEquals(expectedIds.values.toSet(), libraryIds(library))
        assertTrue(
            libraryIds(library).none { it.startsWith(".obsidian") || it.startsWith("attachments") },
            ".obsidian and attachments must never become documents"
        )
    }

    @Test
    fun `imported content is byte-identical to the source files`() {
        val library = importVault()

        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            expectedIds.forEach { (source, id) ->
                val expected = Fixtures.readObsidian(source)
                val actual = composition.store.read(DocId(id))?.content
                assertEquals(expected, actual, "content drift for $source")
            }
        }
    }

    @Test
    fun `obsidian property shapes survive in the typed frontmatter view`() {
        val library = importVault()

        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            val index = composition.store.read(DocId("notes/Index"))!!
            assertEquals(listOf("Home", "Start here"), index.frontmatter.aliases)
            assertEquals(listOf("wide", "dashboard"), index.frontmatter.texts("cssclasses"))

            val alpha = composition.store.read(DocId("notes/Project Alpha"))!!
            val project = alpha.frontmatter.value("project") as? MappingValue
            assertEquals("org/alpha", project?.entries?.get("repo")?.asText())
            assertEquals(2, (alpha.frontmatter.value("tasks") as? SequenceValue)?.items?.size)
            assertTrue(
                alpha.frontmatter.text("description")?.startsWith("A folded scalar spanning two lines") == true,
                "folded scalar must join lines, got '${alpha.frontmatter.text("description")}'"
            )
            assertNotNull(alpha.frontmatter.text("due"), "unquoted date must be carried as text")

            val daily = composition.store.read(DocId("notes/daily/2024-01-15"))!!
            assertEquals("2024-01-15", daily.frontmatter.title, "quoted date-like title must stay text")

            val glossary = composition.store.read(DocId("notes/Glossário — café"))!!
            assertEquals("Glossário — café", glossary.frontmatter.title)
        }
    }

    @Test
    fun `a duplicate-key block degrades verbatim instead of dropping the note`() {
        val library = importVault()

        val parsed = MarkdownParser.parse(Fixtures.readObsidian("notes/Duplicate Keys.md"))
        assertNotNull(parsed.frontmatterError, "duplicate keys must be reported, not silently resolved")
        assertTrue(parsed.frontmatter.title == null, "typed view must be empty when the block is refused")

        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            val doc = composition.store.read(DocId("notes/Duplicate Keys"))!!
            assertTrue(
                doc.content.contains("title: First Title") && doc.content.contains("title: Second Title"),
                "both raw lines must survive byte-identical in content"
            )
            assertNull(doc.frontmatter.title)
            assertEquals(
                parsed.frontmatter.rawFrontmatter,
                doc.frontmatter.rawFrontmatter,
                "the verbatim block must survive the sqlite write path, not just the content column"
            )
        }
    }

    @Test
    fun `unresolved wikilinks are counted and their causes classified`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        Fixtures.copyObsidianTo(source)
        val library = tmp.resolve("library.db")
        val report = CliOperations.importVault(source, library)

        assertEquals(4, report.unresolvedLinks, "expected exactly the four known-dangling links")

        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            val deriver = EdgeDeriver(composition.store)
            val ids = composition.store.list().map { it.id.value }
            val invalidated = report.wikilinkInvalidations.map { it.linkTarget }.toSet()
            val unresolved = mutableMapOf<String, String>()
            expectedIds.keys.forEach { relative ->
                val links = MarkdownParser.parse(Fixtures.readObsidian(relative)).links
                links.forEach { link ->
                    if (deriver.resolve(link.target) == null) {
                        unresolved[link.target] = classify(link.target, ids, invalidated)
                    }
                }
            }
            assertEquals(
                mapOf(
                    "Note That Does Not Exist" to "genuinely-absent",
                    "logo.svg" to "embed-to-non-markdown",
                    "project alpha" to "case-mismatch",
                    "notes/trailing space .md" to "renamed-away"
                ),
                unresolved
            )
        }
    }

    /**
     * Cause of one unresolved link, derived from vault state rather than
     * hard-coded: the classification must stay true if the fixture changes.
     */
    private fun classify(target: String, ids: List<String>, invalidated: Set<String>): String {
        // Only an unresolved link reaches here, so naming a renamed file's old
        // identity means the rename is what broke it: the bare-name links to
        // the same renames still resolve and never arrive in this branch.
        if (target in invalidated) return "renamed-away"
        if (target.trim().endsWith(".svg", ignoreCase = true)) return "embed-to-non-markdown"
        val stem = target.trim().removeSuffix(".md")
        val matchesCaseInsensitively =
            ids.any {
                it.equals(stem, ignoreCase = true) || it.substringAfterLast('/').equals(stem, ignoreCase = true)
            }
        if (matchesCaseInsensitively) return "case-mismatch"
        return "genuinely-absent"
    }

    @Test
    fun `sanitize frequency is the two known shapes with every mapping reported`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        Fixtures.copyObsidianTo(source)
        val library = tmp.resolve("library.db")
        val report = CliOperations.importVault(source, library)

        assertEquals(
            listOf(
                Triple("notes/UPPER.MD", "notes/UPPER", listOf(SanitizeReason.EXTENSION_CASE)),
                Triple(
                    "notes/trailing space .md",
                    "notes/trailing space",
                    listOf(SanitizeReason.SURROUNDING_WHITESPACE)
                )
            ),
            report.renames.map { Triple(it.sourcePath, it.targetId.value, it.reasons) }
        )

        // Three inbound links name a renamed file's old identity, but only the
        // exact old path genuinely dangles. The bare-name two still resolve
        // through trailing-segment match, so only that one is reported as
        // invalidated.
        assertEquals(
            setOf("notes/trailing space .md"),
            report.wikilinkInvalidations.map { it.linkTarget }.toSet()
        )
        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            val deriver = EdgeDeriver(composition.store)
            assertNotNull(deriver.resolve("UPPER"), "bare link to an extension-case rename still resolves")
            assertNotNull(deriver.resolve("trailing space"), "bare link to a whitespace rename still resolves")
            assertNull(deriver.resolve("notes/trailing space .md"), "exact old path genuinely dangles")
        }
    }

    @Test
    fun `edges mirror the resolved graph with anchors embeds and aliases collapsed`() {
        val library = importVault()

        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            val total = expectedIds.values.sumOf { composition.store.neighbors(DocId(it)).size }
            assertEquals(16, total, "one edge per distinct resolved target per document")
            assertEquals(
                setOf(
                    "notes/Project Alpha",
                    "notes/Reference",
                    "notes/daily/2024-01-15",
                    "notes/Glossário — café",
                    "notes/Plain",
                    "notes/UPPER",
                    "notes/trailing space"
                ),
                composition.store
                    .neighbors(DocId("notes/Index"))
                    .map { it.dst.value }
                    .toSet()
            )
        }
    }

    @Test
    fun `the index note parses fifteen links with self-anchors and fences excluded`() {
        val parsed = MarkdownParser.parse(Fixtures.readObsidian("notes/Index.md"))

        assertEquals(15, parsed.links.size, "unexpected link set: ${parsed.links}")
        assertTrue(parsed.links.none { it.target == "#Local Section" || it.target.isEmpty() })
        assertTrue(parsed.links.none { it.target == "not-a-real-link" })
        val anchored = parsed.links.single { it.target == "Reference" && it.anchor == "intro-block" }
        assertNull(anchored.label)
        val aliased = parsed.links.single { it.target == "Project Alpha" && it.anchor == "Goals" }
        assertEquals("goals", aliased.label)
    }
}
