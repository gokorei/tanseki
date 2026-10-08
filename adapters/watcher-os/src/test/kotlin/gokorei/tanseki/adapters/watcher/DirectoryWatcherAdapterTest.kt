package gokorei.tanseki.adapters.watcher

import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.FsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class DirectoryWatcherAdapterTest {
    @TempDir
    lateinit var tmp: Path

    private class FakeRawWatcherFactory : RawWatcherFactory {
        var listener: ((RawFsEvent) -> Unit)? = null
        var closed = false

        override fun watch(root: VaultPath, onEvent: (RawFsEvent) -> Unit): AutoCloseable {
            listener = onEvent
            return AutoCloseable { closed = true }
        }

        fun emit(event: RawFsEvent) {
            listener!!.invoke(event)
        }
    }

    private fun vault(): VaultPath = VaultPath(tmp.toString())

    /** Raw watcher paths are absolute in production, so the fixtures stay inside the vault. */
    private fun inVault(name: String): String = tmp.resolve(name).toString()

    @Test
    fun `rapid writes to the same path coalesce to one`() =
        runBlocking {
            val factory = FakeRawWatcherFactory()
            val adapter = DirectoryWatcherAdapter(factory = factory, debounce = 50.milliseconds)
            val collected = mutableListOf<FsEvent>()

            val job = launch { adapter.events(vault()).collect { collected += it } }
            delay(50)
            repeat(5) { factory.emit(RawFsEvent(inVault("a.md"), RawChangeKind.MODIFY)) }
            delay(200)
            job.cancel()

            assertEquals(1, collected.size)
            assertTrue(collected[0] is FsEvent.Modified)
        }

    @Test
    fun `distinct paths produce distinct events`() =
        runBlocking {
            val factory = FakeRawWatcherFactory()
            val adapter = DirectoryWatcherAdapter(factory = factory, debounce = 50.milliseconds)
            val collected = mutableListOf<FsEvent>()

            val job = launch { adapter.events(vault()).collect { collected += it } }
            delay(50)
            factory.emit(RawFsEvent(inVault("a.md"), RawChangeKind.CREATE))
            factory.emit(RawFsEvent(inVault("b.md"), RawChangeKind.DELETE))
            delay(200)
            job.cancel()

            assertEquals(2, collected.size)
            assertTrue(collected.any { it is FsEvent.Created && it.path.endsWith("a.md") })
            assertTrue(collected.any { it is FsEvent.Deleted && it.path.endsWith("b.md") })
        }

    @Test
    fun `self writes are suppressed once`() =
        runBlocking {
            val factory = FakeRawWatcherFactory()
            val adapter = DirectoryWatcherAdapter(factory = factory, debounce = 50.milliseconds)
            val collected = mutableListOf<FsEvent>()

            val job = launch { adapter.events(vault()).collect { collected += it } }
            delay(50)
            adapter.suppress(inVault("self.md"))
            factory.emit(RawFsEvent(inVault("self.md"), RawChangeKind.MODIFY))
            factory.emit(RawFsEvent(inVault("other.md"), RawChangeKind.MODIFY))
            delay(200)
            job.cancel()

            assertEquals(1, collected.size)
            assertTrue(collected[0].path.endsWith("other.md"))
        }

    @Test
    fun `self write suppression consumes exactly one event`() =
        runBlocking {
            val factory = FakeRawWatcherFactory()
            val adapter = DirectoryWatcherAdapter(factory = factory, debounce = 50.milliseconds)
            val collected = mutableListOf<FsEvent>()

            val job = launch { adapter.events(vault()).collect { collected += it } }
            delay(50)
            adapter.suppress(inVault("self.md"))
            factory.emit(RawFsEvent(inVault("self.md"), RawChangeKind.MODIFY))
            factory.emit(RawFsEvent(inVault("self.md"), RawChangeKind.MODIFY))
            delay(200)
            job.cancel()

            assertEquals(1, collected.size)
            assertTrue(collected[0].path.endsWith("self.md"))
        }

    @Test
    fun `a vault relative self write is suppressed for an absolute event`() =
        runBlocking {
            val factory = FakeRawWatcherFactory()
            val adapter = DirectoryWatcherAdapter(factory = factory, debounce = 50.milliseconds)
            val collected = mutableListOf<FsEvent>()

            val job = launch { adapter.events(vault()).collect { collected += it } }
            delay(50)
            // The daemon hands over the document path, not the absolute file path.
            adapter.suppress("notes/relative.md")
            factory.emit(RawFsEvent(inVault("notes/relative.md"), RawChangeKind.MODIFY))
            factory.emit(RawFsEvent(inVault("notes/other.md"), RawChangeKind.MODIFY))
            delay(200)
            job.cancel()

            assertEquals(1, collected.size)
            assertTrue(collected.single().path.endsWith("notes/other.md"))
        }

    @Test
    fun `overflow emits an explicit reconciliation event`() =
        runBlocking {
            val factory = FakeRawWatcherFactory()
            val adapter = DirectoryWatcherAdapter(factory = factory, debounce = 50.milliseconds)
            val collected = mutableListOf<FsEvent>()

            val job = launch { adapter.events(vault()).collect { collected += it } }
            delay(50)
            factory.emit(RawFsEvent("/ignored.md", RawChangeKind.OVERFLOW))
            delay(200)
            job.cancel()

            assertEquals(1, collected.size)
            assertTrue(collected.single() is FsEvent.Overflow)
        }

    @Test
    fun `relative raw paths resolve against the vault root`() =
        runBlocking {
            val factory = FakeRawWatcherFactory()
            val adapter = DirectoryWatcherAdapter(factory = factory, debounce = 50.milliseconds)
            val collected = mutableListOf<FsEvent>()

            val job = launch { adapter.events(vault()).collect { collected += it } }
            delay(50)
            factory.emit(RawFsEvent("notes/a.md", RawChangeKind.CREATE))
            delay(200)
            job.cancel()

            assertEquals(1, collected.size)
            assertEquals(tmp.resolve("notes/a.md").toString(), collected.single().path)
        }

    @Test
    fun `raw paths outside the vault are dropped`() =
        runBlocking {
            val factory = FakeRawWatcherFactory()
            val adapter = DirectoryWatcherAdapter(factory = factory, debounce = 50.milliseconds)
            val collected = mutableListOf<FsEvent>()

            val job = launch { adapter.events(vault()).collect { collected += it } }
            delay(50)
            factory.emit(RawFsEvent("../escape.md", RawChangeKind.CREATE))
            delay(200)
            job.cancel()

            assertTrue(collected.isEmpty())
        }

    @Test
    fun `close closes the underlying watcher`() =
        runBlocking {
            val factory = FakeRawWatcherFactory()
            val adapter = DirectoryWatcherAdapter(factory = factory, debounce = 50.milliseconds)

            val job = launch { adapter.events(vault()).collect { } }
            withTimeout(5_000) {
                while (factory.listener == null) delay(10)
            }
            adapter.close()
            job.cancel()
            assertTrue(factory.closed)
        }

    @Test
    fun `real directory watcher emits a create for a new file`() =
        runBlocking {
            val adapter =
                DirectoryWatcherAdapter(
                    debounce = 100.milliseconds,
                    dispatcher = Dispatchers.IO
                )
            val seen =
                withTimeoutOrNull(10_000) {
                    coroutineScope {
                        val deferred =
                            async {
                                adapter.events(vault()).firstOrNull { it.path.endsWith("new.md") }
                            }
                        delay(500)
                        File(tmp.toFile(), "new.md").writeText("hello")
                        deferred.await()
                    }
                }
            adapter.close()
            assertTrue(seen != null, "expected an event for new.md")
            assertTrue(seen!!.path.endsWith("new.md"))
            assertFalse(File(tmp.toFile(), "new.md").readText().isEmpty())
        }
}
