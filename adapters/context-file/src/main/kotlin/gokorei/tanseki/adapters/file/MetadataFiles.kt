package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.DocId
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * The sidecar metadata primitives: v2 atomic writes, existence checks and the
 * legacy (pre-v2) name fallback.
 *
 * Kept out of [FileContextStore] so every derived write — edges, revisions,
 * collections, outbox records, leases, tombstones — shares one codec for how a
 * document-id becomes a nested path and one atomic-move implementation for how a
 * record lands on disk.
 */
internal class MetadataFiles(private val layout: StoreLayout) {
    fun metadataFile(directory: Path, value: String): Path {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encoded = METADATA_V2_PREFIX + encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        val parts = encoded.chunked(METADATA_COMPONENT_LENGTH)
        val normalizedDirectory = directory.normalize()
        val parent =
            parts.dropLast(1).fold(normalizedDirectory) { current, part ->
                current.resolve(part)
            }
        val path = parent.resolve(parts.last()).normalize()
        if (!path.startsWith(normalizedDirectory)) throw layout.invalidPath("metadata path escapes its store")
        return path
    }

    fun metadataFileOrNull(directory: Path, id: DocId): Path? {
        val target = metadataFile(directory, id.value)
        if (metadataFileExists(target)) return target
        migrateLegacyMetadataFile(directory, id)
        return target.takeIf(::metadataFileExists)
    }

    fun migrateLegacyMetadataFile(directory: Path, id: DocId) {
        val target = metadataFile(directory, id.value)
        if (metadataFileExists(target)) return
        val legacy = legacyMetadataFile(directory, id.value) ?: return
        if (metadataFileExists(legacy)) copyMetadataFile(legacy, target)
    }

    fun legacyMetadataFile(directory: Path, value: String): Path? {
        val legacy = directory.resolve(value.replace("/", "__").replace("\\", "__")).normalize()
        return legacy.takeIf { it.startsWith(directory.normalize()) }
    }

    fun deleteMetadataFile(directory: Path, value: String) {
        Files.deleteIfExists(metadataFile(directory, value))
        legacyMetadataFile(directory, value)?.let { Files.deleteIfExists(it) }
    }

    fun copyMetadataFile(source: Path, target: Path) {
        if (!metadataFileExists(source)) return
        atomicWrite(target, Files.readAllBytes(source))
    }

    fun metadataFilesEqual(first: Path, second: Path): Boolean =
        metadataFileExists(first) &&
            metadataFileExists(second) &&
            Files.readAllBytes(first).contentEquals(Files.readAllBytes(second))

    fun atomicWriteString(file: Path, value: String) {
        atomicWrite(file, value.toByteArray(StandardCharsets.UTF_8))
    }

    fun atomicWrite(file: Path, bytes: ByteArray) {
        Files.createDirectories(file.parent)
        layout.requireContainedRealPath(file.parent)
        val temporary = Files.createTempFile(file.parent, ".tanseki-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun metadataFileExists(file: Path): Boolean {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(file)) throw layout.invalidPath("symbolic links are not valid metadata files")
        layout.requireContainedRealPath(file)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw layout.invalidPath("metadata path is not a regular file")
        }
        return true
    }
}
