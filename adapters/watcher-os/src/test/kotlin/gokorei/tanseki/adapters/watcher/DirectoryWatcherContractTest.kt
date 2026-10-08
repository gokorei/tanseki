package gokorei.tanseki.adapters.watcher

import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Watcher
import gokorei.tanseki.testkit.WatcherContract
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/** Runs the shared [WatcherContract] against the OS directory watcher. */
class DirectoryWatcherContractTest : WatcherContract() {
    private lateinit var root: Path

    override fun newWatcher(): Watcher {
        root = Files.createTempDirectory("tanseki-watch-contract")
        return DirectoryWatcherAdapter(debounce = 100.milliseconds, dispatcher = Dispatchers.IO)
    }

    override fun vault(): VaultPath = VaultPath(root.toString())

    override fun createFile(relativePath: String) {
        File(root.toFile(), relativePath).writeText("hello")
    }

    override fun closeWatcher(watcher: Watcher) {
        (watcher as AutoCloseable).close()
        if (Files.exists(root)) {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
