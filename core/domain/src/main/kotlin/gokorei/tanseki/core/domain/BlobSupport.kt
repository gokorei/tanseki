package gokorei.tanseki.core.domain

import java.security.MessageDigest

/**
 * The blob and digest rules every ContextStore adapter must agree on.
 *
 * These were hand-copied into the File, SQLite and Postgres stores and drifted
 * exactly where it mattered: Postgres once rejected a negative [BlobRef.size] that
 * the other two treat as "the digest is asserted, the size is not". One
 * implementation removes that class of divergence — the shared contract suite now
 * exercises the same code on every adapter.
 */
object BlobSupport {
    const val ALGORITHM = "sha256"

    private val SHA256_HASH = Regex("[0-9a-f]{64}")

    /** The lowercase hex SHA-256 of [bytes]. */
    fun sha256(bytes: ByteArray): String =
        Bytes.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** The lowercase hex SHA-256 of [text] encoded as UTF-8. */
    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    /**
     * Validates a reference addressing a stored blob.
     *
     * A negative [BlobRef.size] is accepted: it is how a caller says "I am
     * asserting a digest but not a size", which is all a fetch by hash can do.
     * [verify] still checks the digest, so nothing is weakened.
     */
    fun validateRef(ref: BlobRef) {
        if (ref.algorithm != ALGORITHM) {
            throw InvalidInputException("unsupported blob algorithm: ${ref.algorithm}")
        }
        if (!SHA256_HASH.matches(ref.hash)) {
            throw InvalidInputException("blob hash must be a lowercase SHA-256 digest")
        }
    }

    /**
     * True when [ref] asserts a size that disagrees with the stored [recordedSize].
     *
     * A fetch-by-hash asserts no size, so its negative [BlobRef.size] never
     * disagrees; a caller that did assert one is told when the stored size differs.
     */
    fun sizeDisagrees(ref: BlobRef, recordedSize: Long): Boolean =
        ref.size >= 0 && recordedSize != ref.size

    /**
     * Verifies [bytes] against [ref].
     *
     * The digest is always checked, because it is the identity the caller
     * supplied. The size is checked only when the caller asserted one.
     */
    fun verify(ref: BlobRef, bytes: ByteArray) {
        val sizeMatches = ref.size < 0 || bytes.size.toLong() == ref.size
        if (!sizeMatches || sha256(bytes) != ref.hash) {
            throw BlobCorruptionException("blob ${ref.hash} failed size or digest verification")
        }
    }
}
