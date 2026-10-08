package gokorei.tanseki.core.domain

/**
 * A derived vector for a document chunk. Lives in the Lookup, is recomputable
 * from the Context Store, and is keyed by `(docId, chunk, model)`.
 */
class Embedding(
    val docId: DocId,
    val chunk: Int,
    val model: String,
    val dim: Int,
    val vector: FloatArray
) {
    init {
        require(chunk >= 0) { "chunk must be >= 0" }
        require(dim >= 0) { "dim must be >= 0" }
        require(vector.size == dim) { "vector size ${vector.size} != declared dim $dim" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Embedding) return false
        return docId == other.docId &&
            chunk == other.chunk &&
            model == other.model &&
            dim == other.dim &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = docId.hashCode()
        result = 31 * result + chunk
        result = 31 * result + model.hashCode()
        result = 31 * result + dim
        result = 31 * result + vector.contentHashCode()
        return result
    }

    override fun toString(): String = "Embedding(doc=$docId, chunk=$chunk, model=$model, dim=$dim)"
}
