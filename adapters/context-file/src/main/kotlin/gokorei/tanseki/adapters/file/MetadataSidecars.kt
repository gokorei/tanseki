package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The per-document sidecars: revision ledger, collection tag, and rename origin.
 *
 * Kept out of [FileContextStore] so the document store reads and writes one
 * small metadata namespace per concern instead of carrying the paths and the
 * read-modify-write rules inline.
 */
internal class MetadataSidecars(
    private val layout: StoreLayout,
    private val files: MetadataFiles
) {
    fun revisionFile(id: DocId): Path = files.metadataFile(layout.revisionsDir, id.value)

    fun readRevision(id: DocId): String? =
        files.metadataFileOrNull(layout.revisionsDir, id)?.let { Files.readString(it).trim().ifBlank { null } }

    fun writeRevision(id: DocId, value: String) {
        files.migrateLegacyMetadataFile(layout.revisionsDir, id)
        files.atomicWriteString(revisionFile(id), value)
    }

    fun collectionFile(id: DocId): Path = files.metadataFile(layout.collectionsDir, id.value)

    fun readCollection(id: DocId): Collection {
        val value =
            files.metadataFileOrNull(layout.collectionsDir, id)?.let { Files.readString(it).trim() }.orEmpty()
        return Collection(value.ifBlank { DEFAULT_COLLECTION })
    }

    fun writeCollection(id: DocId, collection: Collection) {
        files.migrateLegacyMetadataFile(layout.collectionsDir, id)
        files.atomicWriteString(collectionFile(id), collection.value)
    }

    /** Moves a sidecar from one id's file to another's, if the source exists. */
    fun moveMetadataFile(directory: Path, from: DocId, to: DocId) {
        val source = files.metadataFileOrNull(directory, from) ?: return
        val target = files.metadataFile(directory, to.value)
        if (files.metadataFileExists(target)) {
            files.deleteMetadataFile(directory, from.value)
            return
        }
        Files.createDirectories(target.parent)
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Remembers which id a document started under, so its history stays reachable
     * across a move.
     *
     * The Pijul DAG is keyed by path, so moving a file ends one chain and starts
     * another; the DAG cannot merge them. This is what lets [history] read the two
     * as one timeline instead of losing the past. The first origin is kept, so a
     * document renamed several times still reaches its earliest chain.
     */
    fun recordRenameOrigin(id: DocId, originalId: String) {
        val file = files.metadataFile(layout.renamesDir, id.value)
        if (files.metadataFileExists(file)) return
        files.atomicWriteString(file, originalId)
    }

    fun renameOrigin(id: DocId): String? =
        files.metadataFileOrNull(layout.renamesDir, id)?.let { Files.readString(it).trim().ifBlank { null } }
}
