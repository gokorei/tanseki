package gokorei.tanseki.adapters.embedder

import java.nio.file.Files
import java.nio.file.Path

/**
 * BERT-style WordPiece tokenizer backed by a `vocab.txt` (one token per line,
 * line number = id). This is the tokenizer the default model
 * ([EmbeddingModels.ALL_MINILM_L6_V2]) expects, so its `input_ids` line up with
 * the ONNX model.
 *
 * Pipeline: lowercase → split on whitespace → split punctuation → greedy
 * longest-match WordPiece with `##` continuation → `[CLS] ... [SEP]`, truncated
 * to `maxLength`. Unknown words become `[UNK]`.
 */
class WordPieceTokenizer private constructor(
    private val vocab: Map<String, Long>,
    private val unkId: Long,
    private val clsId: Long,
    private val sepId: Long,
    private val maxInputCharsPerWord: Int = 100
) : Tokenizer {
    override fun encode(text: String, maxLength: Int): LongArray {
        require(maxLength >= 2) { "maxLength must leave room for [CLS] and [SEP]" }
        val ids = ArrayList<Long>(maxLength)
        ids += clsId
        for (token in basicTokenize(text)) {
            if (ids.size >= maxLength - 1) break
            for (piece in wordPiece(token)) {
                if (ids.size >= maxLength - 1) break
                ids += piece
            }
        }
        ids += sepId
        return ids.toLongArray()
    }

    private fun basicTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text.lowercase()) {
            when {
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }

                isPunctuation(ch) -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                    tokens += ch.toString()
                }

                !ch.isISOControl() -> {
                    current.append(ch)
                }
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun wordPiece(token: String): List<Long> {
        if (token.length > maxInputCharsPerWord) return listOf(unkId)
        val pieces = mutableListOf<Long>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found: Long? = null
            while (start < end && found == null) {
                val piece = token.substring(start, end)
                found = vocab[if (start == 0) piece else "##$piece"]
                if (found == null) end--
            }
            if (found == null) return listOf(unkId)
            pieces += found
            start = end
        }
        return pieces
    }

    companion object {
        /** Loads a BERT `vocab.txt` (one token per line; line number is the id). */
        fun fromVocabFile(path: Path): WordPieceTokenizer {
            val vocab = LinkedHashMap<String, Long>()
            Files.readAllLines(path).forEachIndexed { index, raw ->
                val token = raw.trimEnd('\n', '\r')
                if (token.isNotEmpty()) vocab[token] = index.toLong()
            }
            return WordPieceTokenizer(
                vocab = vocab,
                unkId = vocab["[UNK]"] ?: 100,
                clsId = vocab["[CLS]"] ?: 101,
                sepId = vocab["[SEP]"] ?: 102
            )
        }

        private fun isPunctuation(ch: Char): Boolean {
            val code = ch.code
            return (code in 33..47) || (code in 58..64) || (code in 91..96) || (code in 123..126)
        }
    }
}
