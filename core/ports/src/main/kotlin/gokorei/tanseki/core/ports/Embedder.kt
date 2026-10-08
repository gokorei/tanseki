package gokorei.tanseki.core.ports

/**
 * Turns text into vectors for the Lookup's kNN field. Local ONNX by default;
 * a remote server is a drop-in alternative. Embeddings are derived and
 * recomputable, so an adapter only needs to be deterministic per [model].
 */
interface Embedder {
    val model: String
    val dimensions: Int

    fun embed(texts: List<String>): List<FloatArray>
}
