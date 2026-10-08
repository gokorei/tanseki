package gokorei.tanseki.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlobSupportTest {
    private val bytes = "attachment".toByteArray()
    private val hash = BlobSupport.sha256(bytes)

    @Test
    fun `sha256 is lowercase hex of the bytes and their utf8 text agree`() {
        assertEquals(hash, BlobSupport.sha256(bytes))
        assertEquals(BlobSupport.sha256("abc"), BlobSupport.sha256("abc".toByteArray()))
        assertTrue(Regex("[0-9a-f]{64}").matches(hash))
    }

    @Test
    fun `a negative size is a digest-only assertion and is accepted`() {
        BlobSupport.validateRef(BlobRef(hash, -1L))
        BlobSupport.verify(BlobRef(hash, -1L), bytes)
        assertFalse(BlobSupport.sizeDisagrees(BlobRef(hash, -1L), 999L))
    }

    @Test
    fun `an asserted size that disagrees is reported`() {
        assertTrue(BlobSupport.sizeDisagrees(BlobRef(hash, bytes.size.toLong()), bytes.size + 1L))
        assertFalse(BlobSupport.sizeDisagrees(BlobRef(hash, bytes.size.toLong()), bytes.size.toLong()))
        assertThrows(BlobCorruptionException::class.java) {
            BlobSupport.verify(BlobRef(hash, bytes.size + 1L), bytes)
        }
    }

    @Test
    fun `an unsupported algorithm or malformed hash is invalid input`() {
        assertThrows(InvalidInputException::class.java) { BlobSupport.validateRef(BlobRef(hash, -1L, "md5")) }
        assertThrows(InvalidInputException::class.java) { BlobSupport.validateRef(BlobRef("not-a-digest", -1L)) }
    }

    @Test
    fun `a digest that does not match the bytes is corruption`() {
        assertThrows(BlobCorruptionException::class.java) {
            BlobSupport.verify(BlobRef("0".repeat(64), -1L), bytes)
        }
    }
}
