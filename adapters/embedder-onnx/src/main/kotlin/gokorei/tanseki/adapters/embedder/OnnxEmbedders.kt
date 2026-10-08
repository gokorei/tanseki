package gokorei.tanseki.adapters.embedder

import gokorei.tanseki.core.ports.Embedder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds a local ONNX [Embedder] for a model directory.
 *
 * Embeddings are an enhancement, not a hard requirement: when the ONNX Runtime
 * is not on the runtime classpath, or the model file (or a usable tokenizer) is
 * missing, [open] returns **null**. Callers then run lexical-only (hybrid search
 * degrades to text), which keeps the store functional on a machine without a
 * model. This is the documented fallback.
 *
 * Expected layout of `modelDir`:
 * ```
 * model.onnx        # EmbeddingModel.fileName
 * vocab.txt         # BERT WordPiece vocabulary
 * ```
 */
object OnnxEmbedders {
    /** Whether the ONNX Runtime classes are on the runtime classpath. */
    fun runtimeAvailable(): Boolean =
        runCatching { Class.forName("ai.onnxruntime.OrtEnvironment") }.isSuccess

    /**
     * Opens an embedder, or null when unavailable. [tokenizer] overrides the
     * `vocab.txt` loaded from [modelDir]; [model] defaults to
     * [EmbeddingModels.DEFAULT].
     */
    fun open(
        modelDir: Path,
        model: EmbeddingModel = EmbeddingModels.DEFAULT,
        tokenizer: Tokenizer? = null
    ): Embedder? {
        if (!runtimeAvailable()) return null
        val onnx = modelDir.resolve(model.fileName)
        if (!Files.isRegularFile(onnx)) return null
        val resolvedTokenizer = tokenizer ?: vocabTokenizer(modelDir) ?: return null
        return OnnxEmbedder(onnx, resolvedTokenizer, model.id, model.dimensions, model.maxLength)
    }

    private fun vocabTokenizer(modelDir: Path): Tokenizer? {
        val vocab = modelDir.resolve("vocab.txt")
        if (!Files.isRegularFile(vocab)) return null
        return runCatching { WordPieceTokenizer.fromVocabFile(vocab) }.getOrNull()
    }
}
