package gokorei.tanseki.service.daemon

import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RevisionId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class DaemonFactoryTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `vault profile starts file and lucene adapters with local resources`() {
        val vault = Files.createDirectories(tmp.resolve("vault"))
        val socket = Path.of("/tmp", "tanseki-${UUID.randomUUID().toString().take(8)}.sock")
        val config =
            TansekiConfig.fromEnv(
                mapOf(
                    TansekiConfig.ENV_PROFILE to "vault",
                    TansekiConfig.ENV_PATH to vault.toString(),
                    TansekiConfig.ENV_INDEX_DIR to tmp.resolve("vault-index").toString(),
                    TansekiConfig.ENV_SOCKET to socket.toString()
                )
            )

        DaemonFactory.open(config).use { handles ->
            handles.daemon.start()

            assertEquals(Profile.VAULT, handles.profile)
            assertTrue(handles.daemon is VaultDaemon)
            assertEquals("FileContextStore", handles.store.javaClass.simpleName)
            assertEquals("LuceneLookup", handles.lookup.javaClass.simpleName)
            assertTrue(Files.exists(vault.resolve(".tanseki/daemon.lock")))
            assertTrue(Files.exists(socket))
        }

        assertFalse(Files.exists(socket))
    }

    @Test
    fun `library profile starts sqlite and lucene without local daemon resources`() {
        val root = Files.createDirectories(tmp.resolve("library"))
        val socket = root.resolve("tanseki.sock")
        val config =
            TansekiConfig.fromEnv(
                mapOf(
                    TansekiConfig.ENV_PROFILE to "library",
                    TansekiConfig.ENV_PATH to root.resolve("library.db").toString(),
                    TansekiConfig.ENV_INDEX_DIR to root.resolve("index").toString(),
                    TansekiConfig.ENV_SOCKET to socket.toString()
                )
            )

        DaemonFactory.open(config).use { handles ->
            handles.daemon.start()

            assertEquals(Profile.LIBRARY, handles.profile)
            assertFalse(handles.daemon is VaultDaemon)
            assertEquals("SqliteContextStore", handles.store.javaClass.simpleName)
            assertEquals("LuceneLookup", handles.lookup.javaClass.simpleName)
            assertFalse(Files.exists(root.resolve(".tanseki/daemon.lock")))
            assertFalse(Files.exists(socket))
        }
    }

    @Test
    fun `server profile fails closed before opening incomplete dependencies`() {
        val config = TansekiConfig.fromEnv(mapOf(TansekiConfig.ENV_PROFILE to "server"))

        val error = assertThrows(IllegalArgumentException::class.java) { DaemonFactory.open(config) }

        assertTrue(error.message.orEmpty().contains(TansekiConfig.ENV_POSTGRES_URL))
        assertTrue(error.message.orEmpty().contains(TansekiConfig.ENV_MEILI_URL))
    }

    @Test
    fun `a second writer is refused before any store or index is opened`() {
        val vault = Files.createDirectories(tmp.resolve("single-writer"))
        val index = tmp.resolve("single-writer-index")
        val socket = Path.of("/tmp", "tanseki-${UUID.randomUUID().toString().take(8)}.sock")
        val config =
            TansekiConfig.fromEnv(
                mapOf(
                    TansekiConfig.ENV_PROFILE to "vault",
                    TansekiConfig.ENV_PATH to vault.toString(),
                    TansekiConfig.ENV_INDEX_DIR to index.toString(),
                    TansekiConfig.ENV_SOCKET to socket.toString()
                )
            )
        // An index directory that cannot be opened: a composition opened before
        // the lock would fail here, the lock taken first never gets that far.
        val blockedIndex = tmp.resolve("blocked-index")
        Files.writeString(blockedIndex, "not a directory")
        val blocked =
            TansekiConfig.fromEnv(
                mapOf(
                    TansekiConfig.ENV_PROFILE to "vault",
                    TansekiConfig.ENV_PATH to vault.toString(),
                    TansekiConfig.ENV_INDEX_DIR to blockedIndex.toString(),
                    TansekiConfig.ENV_SOCKET to
                        Path.of("/tmp", "tanseki-${UUID.randomUUID().toString().take(8)}.sock").toString()
                )
            )

        val owner = DaemonFactory.open(config)
        owner.daemon.start()
        try {
            val error = assertThrows(IllegalStateException::class.java) { DaemonFactory.open(blocked) }

            assertTrue("single writer" in error.message.orEmpty(), error.message.orEmpty())
            assertTrue(Files.exists(vault.resolve(".tanseki/daemon.lock")))
        } finally {
            owner.close()
        }

        // With the vault free, the same configuration fails on its unusable index
        // instead: the refusal above really was the single-writer lock.
        val released = assertThrows(Exception::class.java) { DaemonFactory.open(blocked) }
        assertFalse(
            released is IllegalStateException && released.message.orEmpty().contains("single writer"),
            released.toString()
        )
    }

    /**
     * Recovery must not depend on client traffic.
     *
     * Library and server keep documents in a database, so nothing tells them a
     * document changed. Reconciling only at startup meant a projection that failed
     * once stayed pending until the next write or a restart — precisely when
     * nobody is watching. This drives the periodic loop directly and asserts the
     * outbox drains with no write and no restart in between.
     */
    @Test
    fun `library profile drains the outbox on the periodic loop with no further write`() =
        runBlocking {
            val library = tmp.resolve("library.db")
            val socket = Path.of("/tmp", "tanseki-${UUID.randomUUID().toString().take(8)}.sock")
            val config =
                TansekiConfig.fromEnv(
                    mapOf(
                        TansekiConfig.ENV_PROFILE to "library",
                        TansekiConfig.ENV_PATH to library.toString(),
                        TansekiConfig.ENV_INDEX_DIR to tmp.resolve("library-index").toString(),
                        TansekiConfig.ENV_SOCKET to socket.toString()
                    )
                )

            DaemonFactory.open(config, reconcileInterval = 50.milliseconds).use { handles ->
                handles.daemon.start()
                run {
                    assertFalse(handles.daemon is VaultDaemon, "library has no watcher to drive it")

                    // Enqueued directly, NOT through facade.write. A write drains the
                    // outbox itself, so using one would leave nothing for the loop to
                    // do and the test would pass with the loop switched off.
                    val document =
                        Document(
                            id = DocId("recover/me"),
                            collection = Collection("vault"),
                            path = "recover/me.md",
                            content = "body\n",
                            contentHash = "hash-recover",
                            revision = RevisionId("rev-recover"),
                            updatedAt = Instant.fromEpochSeconds(1_700_000_000)
                        )
                    val operations = requireNotNull(handles.store.projectionOperations())
                    handles.store.write(document, "tester", "tester")
                    operations.enqueue(
                        ProjectionOperation.upsert(
                            document,
                            document.revision,
                            Instant.fromEpochSeconds(1_700_000_000)
                        )
                    )
                    assertTrue(operations.backlog().pending > 0, "work is durable and pending")

                    withTimeout(5_000) {
                        while (operations.backlog().pending > 0) {
                            delay(25)
                        }
                    }

                    assertEquals(0, operations.backlog().pending, "the loop drained it with no write")
                }
            }
        }

    @Test
    fun `a non-positive reconcile interval disables the recovery loop`() =
        runBlocking {
            val library = tmp.resolve("library-noloop.db")
            val socket = Path.of("/tmp", "tanseki-${UUID.randomUUID().toString().take(8)}.sock")
            val config =
                TansekiConfig.fromEnv(
                    mapOf(
                        TansekiConfig.ENV_PROFILE to "library",
                        TansekiConfig.ENV_PATH to library.toString(),
                        TansekiConfig.ENV_INDEX_DIR to tmp.resolve("library-noloop-index").toString(),
                        TansekiConfig.ENV_SOCKET to socket.toString()
                    )
                )

            DaemonFactory.open(config, reconcileInterval = Duration.ZERO).use { handles ->
                handles.daemon.start()
                assertEquals(0, requireNotNull(handles.store.projectionOperations()).backlog().pending)
            }
        }
}
