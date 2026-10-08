package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.testkit.ContextStoreListingContract
import gokorei.tanseki.testkit.FakePijulClient
import java.nio.file.Files
import java.nio.file.Path

/** Runs the shared listing/cursor contract against the vault-mode FileStore. */
class FileContextStoreListingTest : ContextStoreListingContract() {
    private lateinit var root: Path

    override fun newStore(): ContextStore {
        root = Files.createTempDirectory("tanseki-file-listing")
        return FileContextStore(
            vault = VaultPath(root.toString()),
            pijul = FakePijulClient(),
            clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) }
        )
    }

    override fun closeStore(store: ContextStore) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
