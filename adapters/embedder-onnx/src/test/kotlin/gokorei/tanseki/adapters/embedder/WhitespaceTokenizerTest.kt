package gokorei.tanseki.adapters.embedder

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WhitespaceTokenizerTest {
    private val tokenizer =
        WhitespaceTokenizer(
            vocab = mapOf("hello" to 1L, "world" to 2L),
            unknownId = 100,
            clsId = 101,
            sepId = 102
        )

    @Test
    fun `wraps tokens with cls and sep`() {
        assertArrayEquals(
            longArrayOf(101, 1, 2, 102),
            tokenizer.encode("hello world", maxLength = 16)
        )
    }

    @Test
    fun `unknown tokens map to the unknown id`() {
        assertArrayEquals(
            longArrayOf(101, 100, 102),
            tokenizer.encode("nope", maxLength = 16)
        )
    }

    @Test
    fun `lowercases and collapses whitespace`() {
        assertArrayEquals(
            longArrayOf(101, 1, 2, 102),
            tokenizer.encode("  Hello   WORLD ", maxLength = 16)
        )
    }

    @Test
    fun `truncates to max length keeping cls and sep`() {
        val encoded = tokenizer.encode("hello world hello world hello", maxLength = 4)
        assertEquals(4, encoded.size)
        assertEquals(101L, encoded.first())
        assertEquals(102L, encoded.last())
    }

    @Test
    fun `rejects a max length too small for special tokens`() {
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer.encode("hello", maxLength = 1)
        }
    }
}
