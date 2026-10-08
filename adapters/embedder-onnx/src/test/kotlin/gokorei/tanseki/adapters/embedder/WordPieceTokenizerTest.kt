package gokorei.tanseki.adapters.embedder

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WordPieceTokenizerTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var tokenizer: WordPieceTokenizer

    @BeforeEach
    fun setUp() {
        val vocab = tmp.resolve("vocab.txt")
        // Line number is the id: [PAD]=0 [UNK]=1 [CLS]=2 [SEP]=3 hello=4 world=5 ##s=6 a=7
        Files.write(vocab, listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", "##s", "a"))
        tokenizer = WordPieceTokenizer.fromVocabFile(vocab)
    }

    @Test
    fun `wraps tokens in CLS and SEP`() {
        assertArrayEquals(longArrayOf(2, 4, 5, 3), tokenizer.encode("hello world", 10))
    }

    @Test
    fun `lowercases and splits punctuation`() {
        assertArrayEquals(longArrayOf(2, 4, 1, 5, 1, 3), tokenizer.encode("Hello, world!", 10))
    }

    @Test
    fun `greedy wordpiece splits into subwords`() {
        // "worlds" -> "world" + "##s"
        assertArrayEquals(longArrayOf(2, 4, 5, 6, 3), tokenizer.encode("hello worlds", 10))
    }

    @Test
    fun `truncates to maxLength`() {
        assertArrayEquals(longArrayOf(2, 4, 3), tokenizer.encode("hello world", 3))
    }

    @Test
    fun `unknown words become UNK`() {
        assertArrayEquals(longArrayOf(2, 1, 3), tokenizer.encode("zzzz", 10))
    }
}
