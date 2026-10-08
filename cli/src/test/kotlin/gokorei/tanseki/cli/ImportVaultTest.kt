package gokorei.tanseki.cli

import com.github.ajalt.clikt.core.parse
import gokorei.tanseki.composition.Compositions
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.core.domain.DocId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

class ImportVaultTest {
    @TempDir
    lateinit var tmp: Path

    private fun write(dir: Path, name: String, content: String): Path {
        val file = dir.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }

    private fun snapshot(root: Path): Map<String, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        return Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .map { file ->
                    val relative = root.relativize(file).toString().replace('\\', '/')
                    val hash = digest.digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
                    relative to hash
                }.toList()
                .toMap()
        }
    }

    private fun historySizes(library: Path, index: Path): Map<String, Int> =
        Compositions.openLocal(library, Profile.LIBRARY, index).use { composition ->
            composition.store.list(null).associate { ref ->
                ref.id.value to composition.store.history(ref.id).size
            }
        }

    @Test
    fun `real run imports a fixture vault and reports counts`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        write(source, "notes/a.md", "alpha [[notes/b]]")
        write(source, "notes/b.md", "beta")
        write(source, "notes/c.md", "gamma [[notes/b#Section]]")
        val library = tmp.resolve("library.db")
        val before = snapshot(source)

        val report = CliOperations.importVault(source, library)

        assertEquals(3, report.documentsImported)
        assertEquals(0, report.documentsSkipped)
        assertTrue(report.edgesImported >= 2, "expected edges for a->b and c->b, got ${report.edgesImported}")
        assertTrue(report.unreadable.isEmpty())
        assertTrue(report.renames.isEmpty())
        assertTrue(report.sanitizeCollisions.isEmpty())
        assertFalse(report.isLossy)
        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            assertEquals(
                setOf(DocId("notes/a"), DocId("notes/b"), DocId("notes/c")),
                composition.store
                    .list()
                    .map { it.id }
                    .toSet()
            )
            assertEquals("alpha [[notes/b]]", composition.store.read(DocId("notes/a"))!!.content)
        }
        assertEquals(before, snapshot(source), "source directory must be byte-identical after a run")
    }

    @Test
    fun `dry-run reports the same counts and writes nothing`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        write(source, "notes/a.md", "alpha [[notes/b]]")
        write(source, "notes/b.md", "beta")
        val library = tmp.resolve("library.db")

        val dry = CliOperations.importVault(source, library, dryRun = true)

        assertFalse(Files.exists(library), "dry-run against a fresh target must leave no database behind")
        assertFalse(
            Files.exists(tmp.resolve("library.db.index")),
            "dry-run must not create derived state beside the target"
        )

        val real = CliOperations.importVault(source, library)

        assertEquals(real.documentsImported, dry.documentsImported)
        assertEquals(real.documentsSkipped, dry.documentsSkipped)
        assertEquals(real.edgesImported, dry.edgesImported)
        assertEquals(real.unreadable, dry.unreadable)
        assertEquals(
            real.renames.map { it.sourcePath to it.targetId },
            dry.renames.map { it.sourcePath to it.targetId }
        )
        assertEquals(real.unresolvedLinks, dry.unresolvedLinks)
    }

    @Test
    fun `dry-run against an existing library changes nothing`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        write(source, "notes/a.md", "alpha")
        val library = tmp.resolve("library.db")
        CliOperations.importVault(source, library)
        val index = tmp.resolve("probe.index")
        val docsBefore =
            Compositions.openLocal(library, Profile.LIBRARY, index).use { composition ->
                composition.store
                    .list(null)
                    .map { it.id to it.contentHash }
                    .toSet()
            }
        val historyBefore = historySizes(library, index)

        val dry = CliOperations.importVault(source, library, dryRun = true)

        assertEquals(0, dry.documentsImported)
        assertEquals(1, dry.documentsSkipped)
        val docsAfter =
            Compositions.openLocal(library, Profile.LIBRARY, index).use { composition ->
                composition.store
                    .list(null)
                    .map { it.id to it.contentHash }
                    .toSet()
            }
        assertEquals(docsBefore, docsAfter)
        assertEquals(historyBefore, historySizes(library, index))
    }

    @Test
    fun `re-running is idempotent and creates no new revisions`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        write(source, "notes/a.md", "alpha [[notes/b]]")
        write(source, "notes/b.md", "beta")
        val library = tmp.resolve("library.db")
        val index = tmp.resolve("probe.index")

        CliOperations.importVault(source, library)
        val historyBefore = historySizes(library, index)

        val second = CliOperations.importVault(source, library)

        assertEquals(0, second.documentsImported)
        assertEquals(2, second.documentsSkipped)
        assertEquals(historyBefore, historySizes(library, index))
        assertFalse(second.isLossy)
    }

    @Test
    fun `each rejected filename shape imports with renames reported`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        write(source, "notes/trailing space .md", "trailing")
        write(source, "notes/tab\there.md", "tabbed")
        write(source, "notes/UPPER.MD", "upper")
        val library = tmp.resolve("library.db")

        val report = CliOperations.importVault(source, library)

        assertEquals(3, report.documentsImported)
        assertEquals(3, report.renames.size)
        assertEquals(
            mapOf(
                "notes/trailing space .md" to "notes/trailing space",
                "notes/tab\there.md" to "notes/tab_here",
                "notes/UPPER.MD" to "notes/UPPER"
            ),
            report.renames.associate { it.sourcePath to it.targetId.value }
        )
        assertTrue(report.isLossy, "a run with renames must be flagged lossy")
        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            assertEquals("trailing", composition.store.read(DocId("notes/trailing space"))!!.content)
            assertEquals("tabbed", composition.store.read(DocId("notes/tab_here"))!!.content)
            assertEquals("upper", composition.store.read(DocId("notes/UPPER"))!!.content)
        }
    }

    @Test
    fun `post-sanitize collisions are reported and never overwrite`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        write(source, "a.md", "first")
        write(source, "a .md", "second")
        val library = tmp.resolve("library.db")

        val report = CliOperations.importVault(source, library)

        assertEquals(1, report.sanitizeCollisions.size)
        assertEquals(DocId("a"), report.sanitizeCollisions.single().targetId)
        assertTrue(report.isLossy)
        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            // Lexicographically first source wins and is never overwritten.
            assertEquals("second", composition.store.read(DocId("a"))!!.content)
        }
    }

    @Test
    fun `an anchored wikilink resolves its edge`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        write(source, "notes/a.md", "see [[notes/b#Getting started]]")
        write(source, "notes/b.md", "# Getting started")
        val library = tmp.resolve("library.db")

        val report = CliOperations.importVault(source, library)

        assertEquals(0, report.unresolvedLinks)
        Compositions.openLocal(library, Profile.LIBRARY, tmp.resolve("check.index")).use { composition ->
            assertEquals(listOf(DocId("notes/b")), composition.store.neighbors(DocId("notes/a")).map { it.dst })
        }
    }

    @Test
    fun `dangling wikilinks are counted apart from lost notes`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        write(source, "notes/a.md", "see [[notes/missing]]")
        val library = tmp.resolve("library.db")

        val report = CliOperations.importVault(source, library)

        assertEquals(1, report.documentsImported)
        assertEquals(1, report.unresolvedLinks)
        assertTrue(report.unreadable.isEmpty())
        assertFalse(report.isLossy, "a dangling link describes the vault, not the import")
    }

    @Test
    fun `an unreadable note is reported and flags the run lossy`() {
        val source = Files.createDirectories(tmp.resolve("vault"))
        write(source, "notes/a.md", "alpha")
        val locked = write(source, "notes/locked.md", "secret")
        locked.toFile().setReadable(false)
        assumeFalse(Files.isReadable(locked), "test needs an unreadable file; running as root keeps it readable")
        try {
            val library = tmp.resolve("library.db")

            val report = CliOperations.importVault(source, library)

            assertEquals(1, report.documentsImported)
            assertEquals(listOf(DocId("notes/locked")), report.unreadable)
            assertTrue(report.isLossy)
        } finally {
            locked.toFile().setReadable(true)
        }
    }

    @Test
    fun `imports a directory that was never a pijul checkout`() {
        val source = Files.createDirectories(tmp.resolve("obsidian-vault"))
        write(source, "notes/a.md", "alpha")
        write(source, ".obsidian/app.json", "{}")
        val library = tmp.resolve("library.db")

        // No `.pijul`, no `.tanseki`, no daemon: a foreign directory must
        // import without any VCS binary on PATH.
        val report = CliOperations.importVault(source, library)

        assertEquals(1, report.documentsImported)
        assertFalse(Files.exists(source.resolve(".tanseki")))
        assertFalse(Files.exists(source.resolve(".pijul")))
    }

    @Test
    fun `a lossy import-vault fails the command while a clean one succeeds`() {
        val lossySource = Files.createDirectories(tmp.resolve("lossy-vault"))
        write(lossySource, "notes/a.md", "alpha")
        write(lossySource, "notes/trailing space .md", "trailing")
        val lossyLibrary = tmp.resolve("lossy.db")

        // A rename makes the run lossy: the command prints the terse summary,
        // then fails via kotlin.error, which the real entry point leaves
        // uncaught so the process exits non-zero for scripting.
        assertThrows(IllegalStateException::class.java) {
            ImportVaultCommand().parse(
                listOf("--from", lossySource.toString(), "--to", lossyLibrary.toString())
            )
        }

        val cleanSource = Files.createDirectories(tmp.resolve("clean-vault"))
        write(cleanSource, "notes/a.md", "alpha")
        val cleanLibrary = tmp.resolve("clean.db")
        ImportVaultCommand().parse(
            listOf("--from", cleanSource.toString(), "--to", cleanLibrary.toString())
        )
        assertTrue(Files.exists(cleanLibrary))
    }

    @Test
    fun `import-vault dry-run flag reports without writing`() {
        val source = Files.createDirectories(tmp.resolve("flag-vault"))
        write(source, "notes/a.md", "alpha")
        val library = tmp.resolve("flag.db")

        ImportVaultCommand().parse(
            listOf("--from", source.toString(), "--to", library.toString(), "--dry-run")
        )

        assertFalse(Files.exists(library), "the --dry-run flag must thread into the transfer")
    }
}
