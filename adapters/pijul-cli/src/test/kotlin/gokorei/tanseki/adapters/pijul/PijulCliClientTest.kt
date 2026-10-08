package gokorei.tanseki.adapters.pijul

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.RetryPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PijulCliClientTest {
    @TempDir
    lateinit var tmp: Path

    private fun vault(): VaultPath = VaultPath(tmp.toString())

    private fun fakePijul(script: String): String {
        val file = File(tmp.toFile(), "pijul")
        file.writeText(script.trimIndent())
        file.setExecutable(true)
        return file.absolutePath
    }

    @Test
    fun `a patch without a usable timestamp fails closed instead of defaulting to epoch zero`() {
        // History is ordered by createdAt, so a silent epoch-0 fallback would
        // reorder a document's timeline rather than report the unparseable record.
        val missing =
            assertThrows(UnavailableException::class.java) {
                PijulOutput.parseLog("""[{"hash":"p1","authors":["dev"],"message":"no time"}]""")
            }
        assertTrue(missing.message.orEmpty().contains("timestamp"), missing.message.orEmpty())

        assertThrows(UnavailableException::class.java) {
            PijulOutput.parseLog("""[{"hash":"p1","timestamp":"not-a-time"}]""")
        }
    }

    @Test
    fun `pijul failures are classified by a single pinned phrase table`() {
        assertEquals(PijulFailures.Failure.NOT_FOUND, PijulFailures.classify("error: file not found"))
        assertEquals(PijulFailures.Failure.CONFLICT, PijulFailures.classify("there is an unresolved conflict"))
        assertEquals(PijulFailures.Failure.RATE_LIMITED, PijulFailures.classify("rate limit exceeded"))
        // Wording the table does not know must not be guessed at: it stays a
        // generic failure rather than being silently reclassified.
        assertNull(PijulFailures.classify("some entirely new wording"))
    }

    @Test
    fun `record batches the whole path set in a single invocation`() {
        val argsLog = File(tmp.toFile(), "args.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo "${'$'}*" >> "$argsLog"
            if [ "${'$'}1" = "record" ]; then
              echo 'Hash: patch-1'
              exit 0
            fi
            exit 0
            """
            )

        val client = PijulCliClient(binary = binary)
        val patch = client.record(vault(), "batch", "agent", listOf("a.md", "b.md", "c.md"))

        assertEquals("patch-1", patch.hash.value)
        assertEquals("agent", patch.author)

        val lines = File(argsLog).readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size, "expected exactly one pijul invocation")
        assertEquals(1, client.subprocessInvocations())
        assertTrue(lines[0].contains("a.md") && lines[0].contains("b.md") && lines[0].contains("c.md"))
        assertTrue(lines[0].contains("record"))
    }

    @Test
    fun `record passes only flags the pinned pijul accepts`() {
        val argsLog = File(tmp.toFile(), "args.log").absolutePath
        val binary = fakePijul("#!/bin/sh\necho \"${'$'}*\" >> \"$argsLog\"\necho 'Hash: patch-1'")

        PijulCliClient(binary = binary).record(vault(), "batch", "agent", listOf("a.md"))

        val invocation = File(argsLog).readLines().single()
        // `--no-prompt` does not exist in pijul 1.0.0-beta.24, and the CLI fails the
        // whole invocation on an unknown flag, so it must not be sent.
        assertFalse(invocation.contains("--no-prompt"), invocation)
        assertFalse(invocation.contains("--from-change"), invocation)
        assertTrue(invocation.contains("--message batch"), invocation)
        assertTrue(invocation.contains("--author agent"), invocation)
        // Paths stay after a `--` separator so a path can never be read as a flag.
        assertTrue(invocation.endsWith("-- a.md"), invocation)
    }

    @Test
    fun `record pins both editor variables so no editor can be launched`() {
        val envLog = File(tmp.toFile(), "env.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            printf 'EDITOR=%s\n' "${'$'}EDITOR" >> "$envLog"
            printf 'VISUAL=%s\n' "${'$'}VISUAL" >> "$envLog"
            echo 'Hash: patch-1'
            """
            )

        PijulCliClient(binary = binary).record(vault(), "batch", "agent", listOf("a.md"))

        val env = File(envLog).readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        // pijul resolves VISUAL before EDITED, so both have to be neutralised.
        assertEquals("true", env["EDITOR"])
        assertEquals("true", env["VISUAL"])
    }

    @Test
    fun `record does not append the operation id to the commit message body`() {
        val argsLog = File(tmp.toFile(), "args.log").absolutePath
        val binary = fakePijul("#!/bin/sh\necho \"${'$'}*\" >> \"$argsLog\"\necho 'Hash: patch-1'")

        PijulCliClient(binary = binary)
            .record(vault(), "upsert doc", "agent", listOf("a.md"), operationId = "op-1")

        val invocation = File(argsLog).readLines().single()
        // pijul splits a multi-line --message and drops everything after the first
        // line into the description, so the marker travels as its own field.
        assertTrue(invocation.contains("--message upsert doc"), invocation)
        assertTrue(invocation.contains("--description tanseki-operation-id:op-1"), invocation)
    }

    @Test
    fun `log uses json output format and parses patches with dependencies`() {
        val argsLog = File(tmp.toFile(), "args.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo "${'$'}*" >> "$argsLog"
            echo '[{"hash":"p2","authors":["dev"],"message":"second","timestamp":"2026-02-03T04:05:06Z","dependencies":["p1"]}]'
            """
            )

        val client = PijulCliClient(binary = binary)
        val patches = client.log(vault(), docId = DocId("notes/a"), limit = 5)

        assertEquals(1, patches.size)
        assertEquals("p2", patches[0].hash.value)
        assertEquals("second", patches[0].message)
        assertEquals("dev", patches[0].author)
        assertEquals("p1", patches[0].dependencies.single().value)
        val invocation = File(argsLog).readLines().first()
        assertTrue(invocation.contains("--output-format json"))
        assertTrue(invocation.contains("--limit"))
        assertTrue(invocation.contains("notes/a"))
    }

    @Test
    fun `status derives cleanliness from diff json`() {
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo '{"/":[{"operation":"root"}],"a.md":[{"operation":"file add"}],"b.md":[{"operation":"modify"}]}'
            """
            )
        val status = PijulCliClient(binary = binary).status(vault())
        assertFalse(status.clean)
        assertEquals(listOf("a.md", "b.md"), status.modified)
        assertTrue(status.added.isEmpty())
        assertTrue(status.removed.isEmpty())
    }

    @Test
    fun `status defaults to clean on empty output`() {
        val binary =
            fakePijul(
                """
            #!/bin/sh
            exit 0
            """
            )
        assertTrue(PijulCliClient(binary = binary).status(vault()).clean)
    }

    @Test
    fun `diff passes json and path`() {
        val argsLog = File(tmp.toFile(), "args.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo "${'$'}*" >> "$argsLog"
            echo '{"changes":[]}'
            """
            )
        val out = PijulCliClient(binary = binary).diff(vault(), path = "notes/a.md")
        assertTrue(out.contains("changes"))
        val invocation = File(argsLog).readLines().first()
        assertTrue(invocation.contains("--json"))
        assertTrue(invocation.contains("notes/a.md"))
    }

    @Test
    fun `init runs pijul init only when the vault is not already a repository`() {
        val argsLog = File(tmp.toFile(), "args.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo "${'$'}*" >> "$argsLog"
            exit 0
            """
            )
        val client = PijulCliClient(binary = binary)

        client.init(vault())
        assertTrue(File(argsLog).readLines().any { it.contains("init") })

        File(argsLog).delete()
        File(tmp.toFile(), ".pijul").mkdirs()
        client.init(vault())
        assertFalse(File(argsLog).exists(), "init should be a no-op when .pijul exists")
    }

    @Test
    fun `safe reads recover from transient subprocess failures`() {
        val attemptsLog = File(tmp.toFile(), "attempts.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo attempt >> "$attemptsLog"
            attempts=${'$'}(wc -l < "$attemptsLog")
            if [ "${'$'}attempts" -lt 3 ]; then
              echo "temporarily unavailable" 1>&2
              exit 75
            fi
            echo '{"/":[]}'
            """
            )

        val status = PijulCliClient(binary = binary, retryPolicy = deterministicRetryPolicy()).status(vault())

        assertTrue(status.clean)
        assertEquals(3, File(attemptsLog).readLines().size)
    }

    @Test
    fun `non-zero exit with not found maps to NotFoundException`() {
        val attemptsLog = File(tmp.toFile(), "attempts.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo attempt >> "$attemptsLog"
            echo "file not found" 1>&2
            exit 1
            """
            )
        assertThrows(NotFoundException::class.java) {
            PijulCliClient(binary = binary, retryPolicy = deterministicRetryPolicy()).status(vault())
        }
        assertEquals(1, File(attemptsLog).readLines().size)
    }

    @Test
    fun `record is not retried after a transient subprocess failure`() {
        val attemptsLog = File(tmp.toFile(), "attempts.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo attempt >> "$attemptsLog"
            echo "temporarily unavailable" 1>&2
            exit 75
            """
            )

        assertThrows(UnavailableException::class.java) {
            PijulCliClient(binary = binary, retryPolicy = deterministicRetryPolicy())
                .record(vault(), "message", "author", listOf("a.md"))
        }
        assertEquals(1, File(attemptsLog).readLines().size)
    }

    @Test
    fun `malformed status output is retried and can recover`() {
        val attemptsLog = File(tmp.toFile(), "attempts.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo attempt >> "$attemptsLog"
            attempts=${'$'}(wc -l < "$attemptsLog")
            if [ "${'$'}attempts" -eq 1 ]; then
              echo '{not-json'
              exit 0
            fi
            echo '{"/":[]}'
            """
            )

        val status = PijulCliClient(binary = binary, retryPolicy = deterministicRetryPolicy()).status(vault())

        assertTrue(status.clean)
        assertEquals(2, File(attemptsLog).readLines().size)
    }

    @Test
    fun `malformed log output fails closed after bounded retries`() {
        val attemptsLog = File(tmp.toFile(), "attempts.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo attempt >> "$attemptsLog"
            echo '[{"message":"missing hash"}]'
            """
            )

        assertThrows(UnavailableException::class.java) {
            PijulCliClient(binary = binary, retryPolicy = deterministicRetryPolicy()).log(vault())
        }
        assertEquals(3, File(attemptsLog).readLines().size)
    }

    @Test
    fun `record propagates the operation id into output and patch metadata`() {
        val argsLog = File(tmp.toFile(), "args.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo "${'$'}*" >> "$argsLog"
            echo 'Hash: patch-1'
            """
            )

        val patch =
            PijulCliClient(binary = binary).record(
                vault(),
                "message",
                "author",
                listOf("a.md"),
                operationId = "operation-1"
            )

        assertEquals("operation-1", patch.operationId)
        assertTrue(File(argsLog).readText().contains("tanseki-operation-id:operation-1"))
    }

    @Test
    fun `log reads the operation id back from the change description`() {
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo '[{"hash":"p1","authors":["dev"],"timestamp":"2026-02-03T04:05:06Z","message":"upsert doc","description":"tanseki-operation-id:operation-1"}]'
            """
            )

        val patch = PijulCliClient(binary = binary).log(vault()).single()

        assertEquals("operation-1", patch.operationId)
        assertEquals("upsert doc", patch.message)
    }

    @Test
    fun `log still reads an operation id appended to the message body`() {
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo '[{"hash":"p1","timestamp":"2026-02-03T04:05:06Z","message":"upsert doc\n\ntanseki-operation-id:operation-1"}]'
            """
            )

        val patch = PijulCliClient(binary = binary).log(vault()).single()

        assertEquals("operation-1", patch.operationId)
        assertEquals("upsert doc", patch.message)
    }

    @Test
    fun `timeout terminates subprocess descendants`() {
        val pidFile = File(tmp.toFile(), "child.pid").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            sleep 30 &
            echo ${'$'}! > "$pidFile"
            wait
            """
            )
        val client =
            PijulCliClient(
                binary = binary,
                timeout = 500.milliseconds,
                retryPolicy = RetryPolicy(maxAttempts = 1)
            )

        assertThrows(UnavailableException::class.java) {
            client.status(vault())
        }

        val childPid = File(pidFile).readText().trim().toLong()
        val deadline = System.nanoTime() + 2.seconds.inWholeNanoseconds
        while (ProcessHandle.of(childPid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `missing vault is reported as unavailable`() {
        val binary = fakePijul("#!/bin/sh\nexit 0")
        val missing = VaultPath(File(tmp.toFile(), "does-not-exist").absolutePath)
        assertThrows(gokorei.tanseki.core.domain.UnavailableException::class.java) {
            PijulCliClient(binary = binary).status(missing)
        }
    }

    @Test
    fun `logUnion reads several documents in a single invocation`() {
        val argsLog = File(tmp.toFile(), "args.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo "${'$'}*" >> "$argsLog"
            echo '[{"hash":"p1","authors":["dev"],"message":"first","timestamp":"2026-02-03T04:05:06Z"},{"hash":"p2","authors":["dev"],"message":"second","timestamp":"2026-02-03T04:05:07Z"}]'
            """
            )

        val client = PijulCliClient(binary = binary)
        val patches = client.logUnion(vault(), listOf(DocId("notes/a"), DocId("notes/b")), limit = 5)

        assertEquals(listOf("p1", "p2"), patches.map { it.hash.value })
        val lines = File(argsLog).readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size, "expected exactly one pijul invocation, got: $lines")
        assertTrue(lines[0].contains("notes/a.md") && lines[0].contains("notes/b.md"), lines[0])
        assertEquals(1, client.subprocessInvocations())
    }

    @Test
    fun `logUnion dedupes a change matching several filters`() {
        // A rename records both ends in one patch, so that patch matches every
        // filter of the union: it belongs to the timeline once, not once per path.
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo '[{"hash":"p1","authors":["dev"],"message":"move","timestamp":"2026-02-03T04:05:06Z"},{"hash":"p1","authors":["dev"],"message":"move","timestamp":"2026-02-03T04:05:06Z"}]'
            """
            )

        val patches =
            PijulCliClient(binary = binary)
                .logUnion(vault(), listOf(DocId("notes/old"), DocId("notes/new")), limit = 10)

        assertEquals(listOf("p1"), patches.map { it.hash.value })
    }

    @Test
    fun `logUnion with no documents spawns no subprocess`() {
        val client = PijulCliClient(binary = fakePijul("#!/bin/sh\nexit 0"))

        assertTrue(client.logUnion(vault(), emptyList()).isEmpty())
        assertEquals(0, client.subprocessInvocations())
    }

    @Test
    fun `diffMany reads several paths in a single invocation`() {
        val argsLog = File(tmp.toFile(), "args.log").absolutePath
        val binary =
            fakePijul(
                """
            #!/bin/sh
            echo "${'$'}*" >> "$argsLog"
            echo '{"notes/a.md":[{"operation":"file add"}],"notes/b.md":[{"operation":"modify"}]}'
            """
            )

        val client = PijulCliClient(binary = binary)
        // notes/clean.md changed nothing, so it is absent rather than empty.
        val diffs = client.diffMany(vault(), listOf("notes/a.md", "notes/b.md", "notes/clean.md"))

        assertEquals(setOf("notes/a.md", "notes/b.md"), diffs.keys)
        assertTrue(diffs["notes/a.md"]!!.contains("file add"), diffs["notes/a.md"])
        assertTrue(diffs["notes/b.md"]!!.contains("modify"), diffs["notes/b.md"])
        val lines = File(argsLog).readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size, "expected exactly one pijul invocation, got: $lines")
        assertTrue(lines[0].contains("notes/a.md") && lines[0].contains("notes/b.md"), lines[0])
        assertEquals(1, client.subprocessInvocations())
    }

    @Test
    fun `diffMany with no paths spawns no subprocess`() {
        val client = PijulCliClient(binary = fakePijul("#!/bin/sh\nexit 0"))

        assertTrue(client.diffMany(vault(), emptyList()).isEmpty())
        assertEquals(0, client.subprocessInvocations())
    }

    @Test
    fun `batched reads cost one invocation where per-path reads cost one each`() {
        val binary = fakePijul("#!/bin/sh\necho '[]'")
        val client = PijulCliClient(binary = binary)

        client.log(vault(), docId = DocId("notes/a"), limit = 5)
        client.log(vault(), docId = DocId("notes/b"), limit = 5)
        assertEquals(2, client.subprocessInvocations())

        client.logUnion(vault(), listOf(DocId("notes/a"), DocId("notes/b")), limit = 5)
        assertEquals(3, client.subprocessInvocations())
    }

    private fun deterministicRetryPolicy(): RetryPolicy =
        RetryPolicy(
            maxAttempts = 3,
            baseDelay = 1.milliseconds,
            maxDelay = 1.milliseconds,
            jitter = 0.0
        )
}
