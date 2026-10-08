package gokorei.tanseki.cli.transfer

import gokorei.tanseki.cli.CliOperations
import gokorei.tanseki.composition.Compositions
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.testkit.InMemoryContextStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DirectoryImporterTest {
    @TempDir
    lateinit var tmp: Path

    private fun write(dir: Path, name: String, content: String): Path {
        val file = dir.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }

    @Test
    fun `each rejected shape is imported under a valid id`() {
        val source = Files.createDirectories(tmp.resolve("source"))
        write(source, "notes/trailing space .md", "trailing")
        write(source, "notes/tab\there.md", "tabbed")
        write(source, "notes/UPPER.MD", "upper")
        val target = InMemoryContextStore()

        val report = DirectoryImporter().copy(source, target)

        assertEquals(
            setOf(DocId("notes/trailing space"), DocId("notes/tab_here"), DocId("notes/UPPER")),
            target.list().map { it.id }.toSet()
        )
        assertEquals("trailing", target.read(DocId("notes/trailing space"))!!.content)
        assertEquals("tabbed", target.read(DocId("notes/tab_here"))!!.content)
        assertEquals("upper", target.read(DocId("notes/UPPER"))!!.content)
        assertEquals(3, report.documentsImported)
        assertEquals(3, report.renames.size)
        assertTrue(report.failures.isEmpty(), report.failures.toString())
    }

    @Test
    fun `collisions after sanitizing are reported and never overwrite`() {
        val source = Files.createDirectories(tmp.resolve("source"))
        write(source, "a.md", "first")
        write(source, "a .md", "second")
        val target = InMemoryContextStore()

        val report = DirectoryImporter().copy(source, target)

        assertEquals(1, report.sanitizeCollisions.size)
        val collision = report.sanitizeCollisions.single()
        assertEquals(DocId("a"), collision.targetId)
        assertEquals(listOf("a .md", "a.md"), collision.sources)
        assertEquals("second", target.read(DocId("a"))!!.content)
        assertEquals(1, report.documentsImported)
    }

    @Test
    fun `broken inbound wikilinks are reported with the rename mapping`() {
        val source = Files.createDirectories(tmp.resolve("source"))
        write(source, "notes/trailing space .md", "the note")
        write(source, "notes/ref.md", "see [[notes/trailing space .md]]")
        val target = InMemoryContextStore()

        val report = DirectoryImporter().copy(source, target)

        assertTrue(report.renames.any { it.sourcePath == "notes/trailing space .md" })
        assertEquals(1, report.wikilinkInvalidations.size)
        val invalidation = report.wikilinkInvalidations.single()
        assertEquals("notes/ref.md", invalidation.referrerSource)
        assertEquals("notes/trailing space .md", invalidation.oldTargetPath)
        assertEquals(DocId("notes/trailing space"), invalidation.newTargetId)
    }

    @Test
    fun `clean names are stored untouched`() {
        val source = Files.createDirectories(tmp.resolve("source"))
        write(source, "notes/a.md", "alpha")
        val target = InMemoryContextStore()

        val report = DirectoryImporter().copy(source, target)

        assertEquals(listOf(DocId("notes/a")), target.list().map { it.id })
        assertTrue(report.renames.isEmpty())
        assertTrue(report.sanitizeCollisions.isEmpty())
        assertTrue(report.wikilinkInvalidations.isEmpty())
    }

    @Test
    fun `cli operations imports a foreign directory into a library`() {
        val source = Files.createDirectories(tmp.resolve("source"))
        write(source, "notes/trailing space .md", "trailing")
        write(source, "notes/UPPER.MD", "upper")
        val config =
            TansekiConfig(
                profile = Profile.LIBRARY,
                path = tmp.resolve("lib.db"),
                indexDir = tmp.resolve("lib.index"),
                logLevel = LogLevel.INFO
            )

        val report = CliOperations.importDirectory(source, config)

        assertEquals(2, report.documentsImported)
        assertEquals(2, report.renames.size)
        Compositions.openLocal(config.path, config.profile, config.indexDir).use { composition ->
            assertEquals(
                setOf(DocId("notes/trailing space"), DocId("notes/UPPER")),
                composition.store
                    .list()
                    .map { it.id }
                    .toSet()
            )
        }
    }
}
