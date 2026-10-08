package gokorei.tanseki.adapters.pijul

import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.domain.VaultPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Drives a **real** `pijul` binary through [PijulCliClient].
 *
 * Every other test in this module replaces the binary with a shell stub, and a
 * stub cannot catch a flag the pinned release rejects — which is the defect this
 * suite exists for: `record` used to pass `--no-prompt`, a flag
 * `pijul 1.0.0-beta.24` does not have, so every real record failed with
 * `unexpected argument '--no-prompt' found` while every stubbed test passed.
 *
 * Opt-in, because it needs a real binary on `PATH` and a signing identity in the
 * pijul config directory (`pijul identity new`). The CI `pijul` job installs the
 * pinned release, creates an identity, and runs exactly this:
 *
 * ```
 * TANSEKI_RUN_PIJUL_INTEGRATION=true TANSEKI_PIJUL_BINARY="$(command -v pijul)" \
 *   ./gradlew :adapters:pijul-cli:test
 * ```
 */
@EnabledIfEnvironmentVariable(named = "TANSEKI_RUN_PIJUL_INTEGRATION", matches = "true")
class PijulCliClientRealBinaryTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var client: PijulCliClient

    /** [VaultPath] is a value class, so it is derived rather than held. */
    private val vault: VaultPath get() = VaultPath(tmp.toString())

    @BeforeEach
    fun setUpVault() {
        // Tanseki's daemon keeps store metadata next to the documents, and that
        // metadata must never end up inside a recorded change.
        File(tmp.toFile(), ".tanseki").mkdirs()
        File(tmp.toFile(), ".tanseki/ignored.json").writeText("{}")
        client = PijulCliClient(timeout = 120.seconds)
        client.init(vault)
    }

    @Test
    fun `init turns the vault into a real pijul repository`() {
        assertTrue(File(tmp.toFile(), ".pijul").isDirectory, "pijul init did not create .pijul")
    }

    @Test
    fun `record creates a real change that owns the document`() {
        write(DOCUMENT, "# First\n\nreal pijul write\n")

        val patch = client.record(vault, "tanseki: upsert first", AUTHOR, listOf(DOCUMENT))

        assertTrue(patch.hash.value.isNotBlank(), "record returned a blank hash")
        assertEquals("tanseki: upsert first", patch.message)
        assertEquals(AUTHOR, patch.author)

        // The change has to own the document. pijul only reports the change itself,
        // so its hunks are the one place the recorded path shows up.
        val change = pijul("change", patch.hash.value)
        assertTrue(change.contains("first.md"), "recorded change does not mention the document:\n$change")

        // And the working copy must be clean for it: an unrecorded change is
        // precisely the failure an earlier revision shipped.
        assertTrue(client.status(vault).clean, "working tree still has unrecorded changes")

        val logged = client.log(vault, limit = 10)
        assertTrue(
            logged.any { it.hash == patch.hash && it.message == "tanseki: upsert first" },
            "the recorded change is missing from the log: $logged"
        )
    }

    @Test
    fun `recording the same document again creates a second change`() {
        write(DOCUMENT, "# Second\n\nfirst body\n")
        val first = client.record(vault, "create", AUTHOR, listOf(DOCUMENT))

        write(DOCUMENT, "# Second\n\nsecond body\n")
        val second = client.record(vault, "edit", AUTHOR, listOf(DOCUMENT))

        assertNotEquals(first.hash, second.hash, "the second record did not create a new change")
        assertTrue(
            pijul("change", second.hash.value).contains(DOCUMENT),
            "the second change does not mention the document"
        )
        assertTrue(client.status(vault).clean, "working tree still has unrecorded changes")
        val logged = client.log(vault, limit = 10).map { it.hash }
        assertTrue(first.hash in logged && second.hash in logged, "recorded changes are missing from the log: $logged")
    }

    @Test
    fun `record does not drag store metadata into the change`() {
        File(tmp.toFile(), ".tanseki/ignored.json").writeText("{\"changed\":true}")
        write(METADATA_DOCUMENT, "# Metadata\n\nbody\n")

        val patch = client.record(vault, "create", AUTHOR, listOf(METADATA_DOCUMENT))

        val change = pijul("change", patch.hash.value)
        assertTrue(change.contains("metadata.md"), "recorded change does not mention the document:\n$change")
        assertTrue(!change.contains("ignored.json"), "store metadata leaked into the change:\n$change")
        assertTrue(!change.contains(".tanseki"), "store metadata directory leaked into the change:\n$change")
    }

    @Test
    fun `an unchanged re-record is reported as a failure rather than a success`() {
        write(DOCUMENT, "# Third\n\nbody\n")
        client.record(vault, "create", AUTHOR, listOf(DOCUMENT))

        // pijul exits 0, prints "Nothing to record" on stderr and no hash on
        // stdout, so the missing hash is the only failure signal there is.
        val error =
            runCatching {
                client.record(vault, "again", AUTHOR, listOf(DOCUMENT))
            }.exceptionOrNull()

        assertNotNull(error, "an unchanged re-record must not be reported as a success")
        assertTrue(error is UnavailableException, "expected UnavailableException, got $error")
    }

    @Test
    fun `a path outside the repository is reported as a failure`() {
        val error =
            runCatching {
                client.record(vault, "missing", AUTHOR, listOf("notes/does-not-exist.md"))
            }.exceptionOrNull()

        assertNotNull(error, "recording an unknown path must fail")
    }

    private fun write(relative: String, content: String) {
        val file = File(tmp.toFile(), relative)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    /** Runs the binary directly, for assertions the [PijulCliClient] port does not expose. */
    private fun pijul(vararg args: String): String {
        val process =
            ProcessBuilder(listOf(binary()) + args)
                .directory(File(tmp.toString()))
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "pijul ${args.joinToString(" ")} timed out" }
        return output
    }

    // Must mirror `PijulCliClient`'s own precedence. CI sets
    // `TANSEKI_PIJUL_BINARY` to the pinned install; ignoring it silently fell back
    // to whatever `pijul` was on PATH, which is the opposite of what this suite
    // exists to pin.
    private fun binary(): String =
        System.getenv("TANSEKI_PIJUL_BINARY") ?: "pijul"

    private companion object {
        const val AUTHOR = "Tanseki Agent <agent@gokorei.dev>"
        const val DOCUMENT = "notes/first.md"
        const val METADATA_DOCUMENT = "notes/metadata.md"
    }
}
