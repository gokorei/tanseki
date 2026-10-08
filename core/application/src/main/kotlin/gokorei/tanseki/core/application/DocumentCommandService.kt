package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.documentPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.text.MarkdownParser
import gokorei.tanseki.core.text.MarkdownSerializer
import java.security.MessageDigest
import kotlin.time.Clock as SystemClock

data class UpsertDocumentCommand(
    val id: String,
    val content: String,
    val collection: String? = null,
    /**
     * Caller-supplied path. It must equal the id-derived path (`"<id>.md"`)
     * or be omitted to default to it; any other value is rejected with
     * [InvalidInputException]. The vault stores the file at the derived path
     * and cannot represent anything else, so accepting a divergent path here
     * would store different metadata per profile for the same request.
     */
    val path: String? = null,
    val frontmatter: Frontmatter? = null,
    val message: String = "upsert",
    val author: String = "api",
    val ifRevision: String? = null
)

data class UpsertDocumentResult(
    val document: Document,
    val revision: Revision,
    val created: Boolean,
    /**
     * Why the document's frontmatter could not be read, when it could not be.
     *
     * The block is kept verbatim and the document is written, so the write
     * succeeds — but its typed view is empty, which means it does not index, does
     * not filter, and derives no edges. That is a silent loss unless it is said
     * out loud, so the reason travels back with the result instead of dying in a
     * parser. `null` when the frontmatter read cleanly.
     */
    val frontmatterError: String? = null
)

class DocumentCommandService(
    private val facade: QueryFacade,
    private val clock: Clock =
        Clock {
            SystemClock.System
                .now()
        },
    /** Called once per write whose frontmatter degraded. The daemon counts these. */
    private val onFrontmatterDegraded: ((DocId, String) -> Unit)? = null,
    private val logger: TansekiLogger = TansekiLogger.Noop
) {
    suspend fun upsert(command: UpsertDocumentCommand): UpsertDocumentResult {
        val id = validateId(command.id)
        val existing = facade.get(id)
        val collection = validateCollection(command.collection ?: existing?.collection?.value ?: DEFAULT_COLLECTION)
        if (existing != null && command.collection != null && existing.collection != collection) {
            throw ConflictException(id, "document exists in another collection")
        }
        // The path is always derived from the id, never inherited: a stored
        // document predating the contract may carry a divergent path, and
        // keeping it would preserve per-profile metadata for the same request.
        // Deriving migrates such a document onto the contract on its next write.
        val path = validatePath(command.path ?: id.documentPath(), id)
        val parsed = MarkdownParser.parse(command.content)
        // A block we cannot model is preserved rather than refused, so the write
        // goes through. Naming the reason is what keeps that from being a silent
        // loss: without it, a document whose properties all failed to index is
        // indistinguishable from one that had none.
        parsed.frontmatterError?.let { reason ->
            logger.warn("frontmatter degraded", mapOf("doc" to id.value, "reason" to reason))
            onFrontmatterDegraded?.invoke(id, reason)
        }
        val frontmatter = command.frontmatter ?: parsed.frontmatter
        val content = MarkdownSerializer.render(frontmatter, parsed.body)
        val hash = sha256(content)
        val document =
            Document(
                id = id,
                collection = collection,
                path = path,
                content = content,
                contentHash = hash,
                revision = RevisionId(hash),
                updatedAt = clock.now(),
                frontmatter = frontmatter
            )
        val ifRevision = command.ifRevision?.let(::validateRevision)
        val revision = facade.write(document, command.message, command.author, ifRevision)
        val canonical = facade.get(id) ?: document
        return UpsertDocumentResult(
            document = canonical,
            revision = revision,
            created = existing == null,
            frontmatterError = parsed.frontmatterError
        )
    }

    private fun validateId(value: String): DocId =
        try {
            val id = DocId(value)
            if (':' in id.value.substringBefore('/')) invalid("document id must be relative")
            id
        } catch (error: IllegalArgumentException) {
            invalid(error, "invalid document id")
        }

    private fun validateCollection(value: String): Collection =
        try {
            Collection(value)
        } catch (error: IllegalArgumentException) {
            invalid(error, "invalid collection")
        }

    private fun validatePath(value: String, id: DocId): String {
        if (value.isBlank() || value != value.trim()) invalid("document path must be relative")
        if (value.startsWith('/') || '\\' in value) invalid("document path must be relative")
        if (value.any(Char::isISOControl)) invalid("document path must not contain control characters")
        if (':' in value.substringBefore('/')) invalid("document path must be relative")
        val segments = value.split('/')
        if (segments.any(String::isBlank)) invalid("document path must contain only non-empty relative path segments")
        if (segments.any { it == "." || it == ".." }) {
            invalid("document path must contain only non-empty relative path segments")
        }
        if (value != id.documentPath()) invalid("document path must be derived from its id")
        return value
    }

    private fun validateRevision(value: String): RevisionId =
        try {
            RevisionId(value)
        } catch (error: IllegalArgumentException) {
            invalid(error, "invalid revision")
        }

    private fun invalid(message: String): Nothing = throw InvalidInputException(message)

    private fun invalid(error: IllegalArgumentException, fallback: String): Nothing {
        val exception = InvalidInputException(error.message ?: fallback)
        exception.stackTrace = error.stackTrace
        throw exception
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFAULT_COLLECTION = "vault"
    }
}
