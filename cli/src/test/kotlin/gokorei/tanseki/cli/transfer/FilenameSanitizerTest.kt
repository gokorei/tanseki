package gokorei.tanseki.cli.transfer

import gokorei.tanseki.core.domain.DocId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilenameSanitizerTest {
    @Test
    fun `surrounding whitespace is trimmed to a valid id`() {
        val sanitized = FilenameSanitizer.sanitizeOne("notes/trailing space .md")

        assertEquals(DocId("notes/trailing space"), sanitized.targetId)
        assertEquals("notes/trailing space.md", sanitized.targetPath)
        assertTrue(sanitized.wasRenamed)
        assertTrue(sanitized.reasons.contains(SanitizeReason.SURROUNDING_WHITESPACE))
    }

    @Test
    fun `embedded controls become underscores`() {
        val sanitized = FilenameSanitizer.sanitizeOne("notes/tab\there.md")

        assertEquals(DocId("notes/tab_here"), sanitized.targetId)
        assertTrue(sanitized.wasRenamed)
        assertTrue(sanitized.reasons.contains(SanitizeReason.CONTROL_CHARACTERS))
    }

    @Test
    fun `non-lowercase extension is normalised keeping stem case`() {
        val sanitized = FilenameSanitizer.sanitizeOne("notes/UPPER.MD")

        assertEquals(DocId("notes/UPPER"), sanitized.targetId)
        assertEquals("notes/UPPER.md", sanitized.targetPath)
        assertTrue(sanitized.reasons.contains(SanitizeReason.EXTENSION_CASE))
    }

    @Test
    fun `sanitization is deterministic`() {
        val names =
            listOf(
                "notes/trailing space .md",
                "notes/tab\there.md",
                "notes/UPPER.MD"
            )
        val first = names.associateWith { FilenameSanitizer.sanitizeOne(it).targetId }
        val second = names.associateWith { FilenameSanitizer.sanitizeOne(it).targetId }

        assertEquals(first, second)
        // Batch entry agrees with the single-file mapping.
        val plan = FilenameSanitizer.sanitizeAll(names)
        names.forEach { name ->
            assertEquals(first.getValue(name), plan.winners.getValue(name).targetId)
        }
    }

    @Test
    fun `clean names are untouched`() {
        listOf("notes/a.md", "projects/Project Alpha.md", "notes/Meeting #1.md").forEach { clean ->
            val sanitized = FilenameSanitizer.sanitizeOne(clean)
            assertEquals(clean, sanitized.targetPath)
            assertEquals(DocId(clean.removeSuffix(".md")), sanitized.targetId)
            assertEquals(emptyList<SanitizeReason>(), sanitized.reasons)
            assertEquals(false, sanitized.wasRenamed)
        }
    }

    @Test
    fun `post-sanitize collisions pick the sorted first winner`() {
        // `a .md` trims to `a.md`, colliding with the clean `a.md`.
        val plan = FilenameSanitizer.sanitizeAll(listOf("a.md", "a .md"))

        assertEquals(1, plan.collisions.size)
        val collision = plan.collisions.single()
        assertEquals(DocId("a"), collision.targetId)
        assertEquals(listOf("a .md", "a.md"), collision.sources)
        assertEquals("a .md", collision.winner)
        assertEquals(setOf("a .md"), plan.winners.keys)
    }

    @Test
    fun `report lists every rename as source to target`() {
        val plan =
            FilenameSanitizer.sanitizeAll(
                listOf("notes/trailing space .md", "notes/tab\there.md", "notes/UPPER.MD", "notes/a.md")
            )
        val bySource = plan.renames.associateBy { it.sourcePath }

        assertEquals(3, plan.renames.size)
        assertEquals(DocId("notes/trailing space"), bySource.getValue("notes/trailing space .md").targetId)
        assertEquals(DocId("notes/tab_here"), bySource.getValue("notes/tab\there.md").targetId)
        assertEquals(DocId("notes/UPPER"), bySource.getValue("notes/UPPER.MD").targetId)
    }

    @Test
    fun `a rename invalidates inbound wikilinks and the report says so`() {
        val plan = FilenameSanitizer.sanitizeAll(listOf("notes/trailing space .md", "notes/ref.md"))
        // The link spells the old filename including its `.md` suffix, so its
        // link form keeps the trailing space the sanitizer trimmed: it named
        // the old file, not the new id. (A bare `[[notes/trailing space ]]`
        // would trim to the new id and keep resolving — the parser trims link
        // edges — which is why the extension-spelled link is the breaking case.)
        val contents =
            mapOf(
                "notes/trailing space .md" to "the note itself",
                "notes/ref.md" to "see [[notes/trailing space .md]] for details"
            )

        val invalidations = FilenameSanitizer.findInvalidatedWikilinks(contents, plan)

        assertEquals(1, invalidations.size)
        val invalidation = invalidations.single()
        assertEquals("notes/ref.md", invalidation.referrerSource)
        assertEquals("notes/trailing space .md", invalidation.oldTargetPath)
        assertEquals(DocId("notes/trailing space"), invalidation.newTargetId)
    }

    @Test
    fun `a pure extension-case rename keeps its links resolvable`() {
        val plan = FilenameSanitizer.sanitizeAll(listOf("notes/UPPER.MD", "notes/ref.md"))
        val contents =
            mapOf(
                "notes/UPPER.MD" to "upper",
                "notes/ref.md" to "see [[notes/UPPER]] and [[notes/UPPER.MD]]"
            )

        assertTrue(FilenameSanitizer.findInvalidatedWikilinks(contents, plan).isEmpty())
    }

    @Test
    fun `bare-name links that still resolve are not invalidations`() {
        val plan = FilenameSanitizer.sanitizeAll(listOf("notes/trailing space .md", "notes/UPPER.MD", "notes/ref.md"))
        val contents =
            mapOf(
                "notes/trailing space .md" to "the note",
                "notes/UPPER.MD" to "upper",
                "notes/ref.md" to "see [[trailing space]] and [[UPPER]] and [[notes/trailing space .md]]"
            )

        val invalidations = FilenameSanitizer.findInvalidatedWikilinks(contents, plan)

        assertEquals(1, invalidations.size)
        assertEquals("notes/trailing space .md", invalidations.single().linkTarget)
    }
}
