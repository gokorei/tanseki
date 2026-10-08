package gokorei.tanseki.adapters.embedder

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OnnxEmbeddersTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `returns null (lexical-only fallback) when there is no model file`() {
        // An empty model dir must degrade to lexical-only, not fail the store.
        assertNull(OnnxEmbedders.open(tmp, EmbeddingModels.DEFAULT))
    }

    @Test
    fun `returns null when a tokenizer is unavailable`() {
        Files.write(tmp.resolve("model.onnx"), byteArrayOf(0))
        assertNull(OnnxEmbedders.open(tmp, EmbeddingModels.DEFAULT))
    }

    @Test
    fun `validates model input signatures without loading a model`() {
        assertEquals("input_ids", idsInputName(setOf("input_ids", "attention_mask", "token_type_ids")))
        assertEquals("input", idsInputName(setOf("input")))
        assertThrows(IllegalArgumentException::class.java) {
            idsInputName(setOf("input_ids", "position_ids"))
        }
    }

    @Test
    fun `mean pools output and validates dimensions and finite values`() {
        val value = arrayOf(arrayOf(floatArrayOf(1f, 3f), floatArrayOf(3f, 5f)))

        assertArrayEquals(floatArrayOf(2f, 4f), poolEmbedding(longArrayOf(1, 2, 2), value, 2))
        assertThrows(IllegalArgumentException::class.java) {
            poolEmbedding(longArrayOf(1, 2, 3), value, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            poolEmbedding(longArrayOf(2), floatArrayOf(Float.NaN), 1)
        }
    }
}
