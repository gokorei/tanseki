package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.testkit.ContextStoreEdgeReplacementContract
import gokorei.tanseki.testkit.FakePijulClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

/** Runs the shared edge-replacement contract against the vault-mode file store. */
class FileContextStoreEdgeReplacementTest : ContextStoreEdgeReplacementContract() {
    private lateinit var root: Path

    override fun newStore(): ContextStore {
        root = Files.createTempDirectory("tanseki-edge-replace")
        return FileContextStore(
            vault = VaultPath(root.toString()),
            pijul = FakePijulClient(),
            clock = Clock { Instant.fromEpochSeconds(0) }
        )
    }

    override fun closeStore(store: ContextStore) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
