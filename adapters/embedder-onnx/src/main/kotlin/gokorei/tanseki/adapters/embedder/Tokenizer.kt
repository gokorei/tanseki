package gokorei.tanseki.adapters.embedder

/**
 * Maps text to model token ids. Kept behind an interface because tokenization is
 * model-specific (WordPiece, BPE, SentencePiece) and must match the ONNX model.
 */
fun interface Tokenizer {
    /** Encode [text] into at most [maxLength] ids (including special tokens). */
    fun encode(text: String, maxLength: Int): LongArray
}

/**
 * Minimal whitespace tokenizer backed by a vocabulary map. Sufficient for
 * smoke-testing the pipeline; production should supply the model's real
 * tokenizer behind the same [Tokenizer] interface.
 */
class WhitespaceTokenizer(
    private val vocab: Map<String, Long>,
    private val unknownId: Long = 100,
    private val clsId: Long = 101,
    private val sepId: Long = 102
) : Tokenizer {
    override fun encode(text: String, maxLength: Int): LongArray {
        require(maxLength >= 2) { "maxLength must leave room for [CLS] and [SEP]" }
        val ids = ArrayList<Long>(maxLength)
        ids += clsId
        for (token in text.lowercase().split(WHITESPACE)) {
            if (token.isEmpty()) continue
            if (ids.size >= maxLength - 1) break
            ids += vocab[token] ?: unknownId
        }
        ids += sepId
        return ids.toLongArray()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
