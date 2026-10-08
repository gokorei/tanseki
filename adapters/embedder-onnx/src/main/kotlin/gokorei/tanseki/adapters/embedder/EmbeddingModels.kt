package gokorei.tanseki.adapters.embedder

/**
 * A supported local ONNX embedding model. [dimensions] must match the model's
 * output so the Lucene kNN field and the stored vectors stay consistent.
 *
 * @param id stable config name (`TANSEKI_EMBEDDING_MODEL`)
 * @param fileName ONNX file inside the model directory
 * @param dimensions output vector length
 * @param maxLength tokenizer truncation length
 * @param approxBytes rough on-disk size, for choosing quality vs footprint
 */
data class EmbeddingModel(
    val id: String,
    val fileName: String,
    val dimensions: Int,
    val maxLength: Int,
    val approxBytes: Long
)

/**
 * Registry of local ONNX models, with a documented default.
 *
 * The default is **all-MiniLM-L6-v2**: a 384-dim sentence-transformer (~90 MB)
 * that is the de-facto standard small embedding model. It runs on CPU with low
 * latency, is small enough to ship/self-host, and is strong enough for the
 * semantic half of hybrid search — a good quality/size/latency balance for a
 * local-first store. Larger models can be added here and selected via
 * `TANSEKI_EMBEDDING_MODEL`.
 */
object EmbeddingModels {
    val ALL_MINILM_L6_V2 =
        EmbeddingModel(
            id = "all-MiniLM-L6-v2",
            fileName = "model.onnx",
            dimensions = 384,
            maxLength = 256,
            approxBytes = 90L * 1024 * 1024
        )

    val DEFAULT: EmbeddingModel = ALL_MINILM_L6_V2

    private val REGISTRY: Map<String, EmbeddingModel> = listOf(ALL_MINILM_L6_V2).associateBy { it.id }

    /** Resolves a configured id, or null when unknown (caller decides the fallback). */
    fun resolve(id: String?): EmbeddingModel? = id?.trim()?.takeIf { it.isNotEmpty() }?.let(REGISTRY::get)

    /** Resolves a configured id, falling back to [DEFAULT] when absent/unknown. */
    fun resolveOrDefault(id: String?): EmbeddingModel = resolve(id) ?: DEFAULT

    fun ids(): Set<String> = REGISTRY.keys
}
