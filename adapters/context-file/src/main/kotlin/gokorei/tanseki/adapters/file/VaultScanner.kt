package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.DocId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension

/**
 * Scans the vault for documents.
 *
 * Owned by [FileContextStore] but kept out of the store so the walking rules
 * (what counts as a document, one pass per collection, what happens to a file
 * that cannot become a [DocId]) live once and the store's listings just ask.
 */
internal class VaultScanner(private val layout: StoreLayout) {
    /**
     * Vault files that look like documents but cannot become a [DocId].
     *
     * A vault is foreign input. Obsidian will happily create `trailing .md`, or a
     * name with an embedded tab, and both are legal on macOS and Linux. One such
     * file used to throw out of [documentIds] during `init`, so the store refused
     * to open at all and the whole vault read as empty rather than partially
     * readable. The scanner now skips the file and records its path here.
     *
     * Not on the [gokorei.tanseki.core.ports.ContextStore] port: which filenames a
     * store can admit is adapter policy, and the port has no vocabulary for it.
     */
    private val rejectedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun rejectedDocumentPaths(): List<String> = rejectedPaths.toList()

    fun documentFiles(): List<Path> =
        if (!Files.isDirectory(layout.root)) {
            emptyList()
        } else {
            Files.walk(layout.root).use { stream ->
                stream
                    .filter { it.isMarkdown() && !layout.isBookkeeping(it) }
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                    .sorted(compareBy { layout.relative(it) })
                    .toList()
            }
        }

    fun documentIds(): List<String> {
        if (!Files.isDirectory(layout.root)) return emptyList()
        return buildList {
            Files.walk(layout.root).use { stream ->
                val documents =
                    stream.filter {
                        Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
                            !Files.isSymbolicLink(it) &&
                            it.isMarkdown() &&
                            !layout.isBookkeeping(it)
                    }
                documents.forEach { file -> idForOrNull(file)?.let { add(it.value) } }
            }
        }
    }

    fun idForOrNull(file: Path): DocId? =
        try {
            layout.idFor(file)
        } catch (_: IllegalArgumentException) {
            // Deduplicated: list, listPage and listAll each rescan, so the same
            // bad name would otherwise be reported once per scan. A concurrent set
            // keeps the check-and-add O(1) instead of scanning a queue each time.
            rejectedPaths.add(layout.relative(file))
            null
        }

    /**
     * Whether [file] is a Markdown candidate by name.
     *
     * Case-insensitive, because Obsidian opens `NOTES.MD` and macOS and Windows
     * are case-insensitive filesystems anyway: refusing it would make a note
     * visible in the user's editor and absent here. It stays a *candidate* only.
     * The id it derives is `NOTES.MD` with no `.md` strippable in the sense
     * `documentIdFromPath` expects, so it is rejected and reported by
     * [idForOrNull] rather than silently omitted. `read` enforces the same
     * strictness ([StoreLayout.isExactVaultPath]), so a rejected name reads as
     * absent on every filesystem instead of folding open where the OS allows.
     */
    private fun Path.isMarkdown(): Boolean = extension.equals("md", ignoreCase = true)

    /**
     * Cheap content-derived fingerprint for listing. Reading every byte of the
     * vault on each reconcile would be worse than the mtime it replaces, so the
     * digest covers the file size plus its first and last [CHANGE_TOKEN_WINDOW]
     * bytes: an external edit that keeps the mtime still moves the token.
     */
    fun changeToken(file: Path): String {
        val size = Files.size(file)
        if (size <= 2 * CHANGE_TOKEN_WINDOW) {
            return "$size:${Hashes.sha256(Files.readAllBytes(file))}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(size.toString().toByteArray(StandardCharsets.UTF_8))
        Files.newInputStream(file).use { stream ->
            val head = ByteArray(CHANGE_TOKEN_WINDOW)
            val read = stream.readNBytes(head, 0, head.size)
            digest.update(head.take(read).toByteArray())
        }
        Files.newInputStream(file).use { stream ->
            stream.skipNBytes(maxOf(0, size - CHANGE_TOKEN_WINDOW))
            val tail = ByteArray(CHANGE_TOKEN_WINDOW)
            val tailRead = stream.readNBytes(tail, 0, tail.size)
            digest.update(tail.take(tailRead).toByteArray())
        }
        return "$size:${digest.digest().joinToString("") { "%02x".format(it) }}"
    }
}
