package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.testkit.ContextStoreRetentionContract
import gokorei.tanseki.testkit.FakePijulClient
import java.nio.file.Files
import java.nio.file.Path

/** Runs the shared trash/restore contract against the vault-mode FileStore. */
class FileContextStoreRetentionTest : ContextStoreRetentionContract() {
    private lateinit var root: Path

    override fun newStore(): ContextStore {
        root = Files.createTempDirectory("tanseki-file-trash")
        return FileContextStore(
            vault = VaultPath(root.toString()),
            pijul = FakePijulClient(),
            clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) }
        )
    }

    override fun corruptStoredBlob(hash: String) {
        Files.write(root.resolve(".tanseki/blobs/sha256").resolve(hash), "tampered".toByteArray())
    }

    override fun closeStore(store: ContextStore) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
