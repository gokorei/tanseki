package gokorei.tanseki.core.domain

/**
 * A content-addressed attachment. Identity is the [ref], never a path, so
 * blobs can never diverge between adapters. Compare payloads with
 * [hasSameContent].
 */
class Blob(
    val ref: BlobRef,
    val bytes: ByteArray,
    val mediaType: String? = null
) {
    val size: Long get() = bytes.size.toLong()

    fun hasSameContent(other: Blob): Boolean = bytes.contentEquals(other.bytes)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Blob) return false
        return ref == other.ref && mediaType == other.mediaType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = ref.hashCode()
        result = 31 * result + (mediaType?.hashCode() ?: 0)
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String = "Blob(ref=$ref, size=$size, mediaType=$mediaType)"
}
