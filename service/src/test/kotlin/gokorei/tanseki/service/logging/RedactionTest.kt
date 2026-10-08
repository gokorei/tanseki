package gokorei.tanseki.service.logging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RedactionTest {
    @Test
    fun `endpoint drops credentials path query and fragment`() {
        assertEquals(
            "https://api.example.com:8443",
            Redaction.endpoint("https://user:secret@api.example.com:8443/v1/x?k=v#f")
        )
        assertEquals("http://127.0.0.1:8088", Redaction.endpoint("http://127.0.0.1:8088"))
        assertEquals("http://127.0.0.1:8088", Redaction.endpoint("  http://127.0.0.1:8088/  "))
        assertEquals("https://[::1]:9000", Redaction.endpoint("https://[::1]:9000/v1"))
        assertEquals("https://[::1]", Redaction.endpoint("https://[::1]"))
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "postgres://tanseki:hunter2@db.internal:5432/tanseki",
                "https://token@meili.internal:7700/indexes/documents",
                "https://db.internal:5432/tanseki?password=hunter2"
            ]
    )
    fun `endpoint never echoes userinfo or a query value`(value: String) {
        val redacted = Redaction.endpoint(value)
        assertFalse("hunter2" in redacted, redacted)
        assertFalse("token" in redacted, redacted)
        assertFalse("password" in redacted, redacted)
        assertFalse("/tanseki" in redacted, redacted)
        assertFalse("/indexes" in redacted, redacted)
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "",
                "   ",
                "not-a-url",
                "://host",
                "file:///etc/passwd",
                "https://",
                "http://bad host:8080"
            ]
    )
    fun `non endpoints collapse to redacted`(value: String) {
        assertEquals("<redacted>", Redaction.endpoint(value))
        assertEquals("<redacted>", Redaction.endpoint(null))
    }

    @Test
    fun `file name keeps only the last segment`() {
        assertEquals("daemon.lock", Redaction.fileName("/Users/someone/vault/.tanseki/daemon.lock"))
        assertEquals("daemon.lock", Redaction.fileName("C:\\Users\\someone\\daemon.lock"))
        assertEquals("<redacted>", Redaction.fileName("/"))
        assertEquals("<redacted>", Redaction.fileName(null))
    }

    @Test
    fun `fingerprint is stable per value and hides the value`() {
        val first = Redaction.fingerprint("org/repo/pr-42/e1")
        val second = Redaction.fingerprint("org/repo/pr-42/e1")

        assertEquals(first, second)
        assertNotEquals(first, Redaction.fingerprint("org/repo/pr-43/e1"))
        assertFalse("org" in first, first)
        assertFalse("pr-42" in first, first)
        assertTrue(first.all { it.isDigit() || it in 'a'..'f' })
        assertEquals("<redacted>", Redaction.fingerprint("  "))
        assertEquals("<redacted>", Redaction.fingerprint(null))
    }

    @Test
    fun `error type never carries the throwable message`() {
        val failure =
            runCatching {
                throw IllegalStateException("pijul failed at /Users/someone/vault: exit 128")
            }.exceptionOrNull()

        assertEquals("IllegalStateException", Redaction.errorType(failure))
        assertEquals("error", Redaction.errorType(null))
    }
}
