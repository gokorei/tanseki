package gokorei.tanseki.service.daemon

import gokorei.tanseki.core.domain.DocId
import java.nio.file.Path

/**
 * Maps a watcher event path to the document id it addresses.
 *
 * Watchers may report an event absolute, relative to the vault root, or relative
 * to the process working directory, while the configured vault path may itself
 * be relative. A relative event path is therefore resolved both ways, and when
 * the event path is already prefixed with the vault's own name (the shape a
 * working-directory-relative report takes for a relative configuration) that
 * reading wins — otherwise every event would be addressed by a path that
 * contains the vault twice. Paths outside the vault, paths that traverse out of
 * it, and non-Markdown paths are not documents.
 */
fun vaultDocId(vault: String, eventPath: String): DocId? {
    val root = Path.of(vault).toAbsolutePath().normalize()
    val raw = Path.of(eventPath)
    if (raw.isAbsolute) return documentIdOf(root, raw.normalize())
    val workingDirectory = Path.of("").toAbsolutePath().normalize()
    val fromWorkingDirectory = workingDirectory.resolve(raw).normalize()
    val candidates =
        if (isPrefixedByVaultName(root, raw, workingDirectory)) {
            listOf(fromWorkingDirectory)
        } else {
            listOf(root.resolve(raw).normalize(), fromWorkingDirectory)
        }
    return candidates.asSequence().mapNotNull { candidate -> documentIdOf(root, candidate) }.firstOrNull()
}

private fun isPrefixedByVaultName(root: Path, event: Path, workingDirectory: Path): Boolean {
    val vaultName = workingDirectory.relativize(root)
    if (vaultName.toString().isEmpty() || vaultName.toString() == ".") return false
    return event.startsWith(vaultName)
}

private fun documentIdOf(root: Path, candidate: Path): DocId? {
    if (!candidate.startsWith(root) || candidate == root) return null
    val relative = root.relativize(candidate).toString().replace('\\', '/')
    if (!relative.endsWith(MARKDOWN_SUFFIX)) return null
    val id = relative.removeSuffix(MARKDOWN_SUFFIX)
    if (id.isEmpty() || id.split('/').any(::isUnsafeSegment)) return null
    return runCatching { DocId(id) }.getOrNull()
}

private fun isUnsafeSegment(segment: String): Boolean =
    segment.isEmpty() || segment == "." || segment == ".."

/** True when [eventPath] addresses Markdown inside [vault]. */
fun isVaultDocumentPath(vault: String, eventPath: String): Boolean = vaultDocId(vault, eventPath) != null

private const val MARKDOWN_SUFFIX = ".md"
