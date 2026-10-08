package gokorei.tanseki.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BytesTest {
    @Test
    fun `hex encodes byte for byte as lowercase`() {
        assertEquals(
            "deadbeef",
            Bytes.hex(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()))
        )
        assertEquals("00ff10", Bytes.hex(byteArrayOf(0, 0xff.toByte(), 0x10)))
        assertEquals("", Bytes.hex(ByteArray(0)))
    }
}
