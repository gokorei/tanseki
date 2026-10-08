package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.BlobSupport
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.VaultDirs
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.domain.documentIdFromPath
import gokorei.tanseki.core.domain.documentPath
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The vault's on-disk geometry and the invariants that guard file access.
 *
 * Owned by [FileContextStore] but kept out of the store so the path rules live
 * once: every derived directory is resolved here, every "is this a document /
 * is this path safe" check lives here, and the store works with paths it can
 * trust without re-deriving them.
 */
internal class StoreLayout(vault: VaultPath, val realRoot: Path) {
    val root: Path = Path.of(vault.value).toAbsolutePath().normalize()
    val tansekiDir: Path = root.resolve(VaultDirs.DERIVED)
    val blobsDir: Path = tansekiDir.resolve("blobs").resolve("sha256")
    val revisionsDir: Path = tansekiDir.resolve("revisions")
    val edgesDir: Path = tansekiDir.resolve("edges")
    val collectionsDir: Path = tansekiDir.resolve("collections")
    val projectionsDir: Path = tansekiDir.resolve("projections")

    /**
     * Sidecar leases, one file per outbox operation.
     *
     * The lease lives beside the operation rather than inside it so the operation
     * encoding — and every record already on disk — is untouched. The file is
     * created with `CREATE_NEW`, which the filesystem makes atomic, so two workers
     * racing for the same operation cannot both create it. That is the property the
     * SQL backends get from a conditional UPDATE, and the reason this is a sidecar
     * and not a field appended to the record.
     */
    val projectionLeasesDir: Path = tansekiDir.resolve("projection-leases")
    val corruptProjectionsDir: Path = tansekiDir.resolve("corrupt-projections")
    val corruptHistoryDir: Path = tansekiDir.resolve("corrupt-history")
    val historyDir: Path = tansekiDir.resolve("history")
    val tombstonesDir: Path = tansekiDir.resolve("tombstones")
    val corruptTombstonesDir: Path = tansekiDir.resolve("corrupt-tombstones")
    val renamesDir: Path = tansekiDir.resolve("renames")

    fun relative(file: Path): String = root.relativize(file).toString().replace('\\', '/')

    fun idFor(file: Path): DocId = documentIdFromPath(relative(file))

    fun fileFor(id: DocId): Path {
        val file = root.resolve(id.documentPath()).normalize()
        if (!file.startsWith(root) || file.startsWith(tansekiDir)) throw invalidPath("document path escapes the vault")
        return file
    }

    fun isBookkeeping(file: Path): Boolean = BOOKKEEPING_DIRS.any { file.startsWith(root.resolve(it)) }

    fun requireSafeRegularFile(file: Path): Boolean {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(file)) throw invalidPath("symbolic links are not valid document files")
        requireContainedRealPath(file)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidPath("document path is not a regular file")
        }
        return true
    }

    fun requireContainedRealPath(path: Path) {
        val real = path.toRealPath()
        if (!real.startsWith(realRoot)) throw invalidPath("path escapes the vault")
    }

    /**
     * Whether [file] names its vault entry byte-for-byte.
     *
     * [fileFor] derives the canonical `"<id>.md"` spelling, but the OS may
     * resolve that spelling to a differently-cased entry on a case-insensitive
     * filesystem (`UPPER.md` opens `UPPER.MD` on macOS APFS). The scanner only
     * admits the exact spelling — `UPPER.MD` is rejected and reported — so a
     * folding read would serve a document `list()` never reports: 200 on one
     * filesystem, 404 on another. Comparing the real path against the canonical
     * one keeps the two in agreement on any filesystem; only the exact entry
     * reads back.
     */
    fun isExactVaultPath(file: Path): Boolean {
        val expected = relative(file)
        val actual =
            runCatching { realRoot.relativize(file.toRealPath()).toString().replace('\\', '/') }
                .getOrNull() ?: return false
        return actual == expected
    }

    fun invalidPath(message: String): InvalidInputException = InvalidInputException(message)
}

/** Content and metadata digests. */
internal object Hashes {
    fun sha256(bytes: ByteArray): String = BlobSupport.sha256(bytes)

    fun sha256(text: String): String = BlobSupport.sha256(text)
}
