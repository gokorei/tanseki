package gokorei.tanseki.testkit

import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Watcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** Shared [Watcher] conformance suite (recursive change detection). */
abstract class WatcherContract {
    protected abstract fun newWatcher(): Watcher

    protected abstract fun vault(): VaultPath

    /** Mutate the vault so the watcher observes a change. */
    protected abstract fun createFile(relativePath: String)

    protected open fun closeWatcher(watcher: Watcher) = Unit

    @Test
    @Suppress("FunctionNaming")
    fun `emits an event for a newly created file`() =
        runBlocking {
            val watcher = newWatcher()
            try {
                val event =
                    withTimeoutOrNull(10_000) {
                        coroutineScope {
                            val deferred = async { watcher.events(vault()).firstOrNull() }
                            delay(500)
                            createFile("watched.md")
                            deferred.await()
                        }
                    }
                assertNotNull(event, "expected a filesystem event for the new file")
            } finally {
                closeWatcher(watcher)
            }
        }
}
