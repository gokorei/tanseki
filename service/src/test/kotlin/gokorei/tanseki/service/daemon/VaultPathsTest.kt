package gokorei.tanseki.service.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class VaultPathsTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `absolute event paths map to document ids`() {
        assertEquals("notes/a", vaultDocId(tmp.toString(), tmp.resolve("notes/a.md").toString())!!.value)
    }

    @Test
    fun `relative event paths resolve against the vault root`() {
        assertEquals("notes/a", vaultDocId(tmp.toString(), "notes/a.md")!!.value)
    }

    @Test
    fun `a relative vault configuration still matches absolute event paths`() {
        val relativeRoot = "build/tmp/vault-paths-test"
        val absoluteEvent =
            Path
                .of(relativeRoot)
                .toAbsolutePath()
                .normalize()
                .resolve("notes/a.md")
                .toString()

        assertEquals("notes/a", vaultDocId(relativeRoot, absoluteEvent)!!.value)
        assertEquals("notes/a", vaultDocId(relativeRoot, "notes/a.md")!!.value)
    }

    @Test
    fun `a working directory relative event path maps to the document it addresses`() {
        val workspace = Files.createDirectories(Path.of("").toAbsolutePath().resolve("build/tmp"))
        val vaultDir = Files.createTempDirectory(workspace, "vault-relative-")
        try {
            val configuredVault =
                Path
                    .of("")
                    .toAbsolutePath()
                    .relativize(vaultDir)
                    .toString()
            val eventPath =
                Path
                    .of("")
                    .toAbsolutePath()
                    .relativize(vaultDir.resolve("notes/a.md"))
                    .toString()

            assertEquals("notes/a", vaultDocId(configuredVault, eventPath)!!.value)
            assertEquals("notes/a", vaultDocId(vaultDir.toString(), eventPath)!!.value)
        } finally {
            Files.walk(vaultDir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `paths outside the vault are not documents`() {
        assertNull(vaultDocId(tmp.toString(), tmp.resolve("../escape.md").toString()))
        assertNull(vaultDocId(tmp.toString(), tmp.parent.resolve("escape.md").toString()))
        assertNull(vaultDocId(tmp.toString(), tmp.toString()))
    }

    @Test
    fun `non markdown and nested escaping paths are rejected`() {
        assertNull(vaultDocId(tmp.toString(), tmp.resolve("notes/a.txt").toString()))
        assertNull(vaultDocId(tmp.toString(), tmp.resolve("notes/../../escape.md").toString()))
        Files.createDirectories(tmp.resolve("notes"))
        assertEquals("notes/b", vaultDocId(tmp.toString(), "notes/../notes/b.md")!!.value)
    }

    @Test
    fun `document path predicate matches the id mapping`() {
        assertEquals(true, isVaultDocumentPath(tmp.toString(), "notes/a.md"))
        assertEquals(false, isVaultDocumentPath(tmp.toString(), "notes/a.txt"))
    }
}
