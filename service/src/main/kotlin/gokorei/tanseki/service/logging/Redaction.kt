package gokorei.tanseki.service.logging

import gokorei.tanseki.core.domain.Bytes
import java.security.MessageDigest

/**
 * Redaction helpers for values that reach a log sink or an error string.
 *
 * Tanseki runs subprocesses (pijul), talks to HTTP dependencies, and holds user
 * paths. Those three routinely end up inside exception messages, so nothing
 * derived from a throwable is logged verbatim and endpoints are reduced to
 * scheme + authority before they are recorded.
 */
object Redaction {
    private const val REDACTED = "<redacted>"

    /** The exception class name — never the message, which can carry user data. */
    fun errorType(error: Throwable?): String =
        error?.let { it::class.simpleName ?: "error" } ?: "error"

    /**
     * `scheme://host:port` with credentials, path, query, and fragment dropped.
     * Non-URL input collapses to `<redacted>`.
     */
    fun endpoint(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return REDACTED
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return REDACTED
        val scheme = raw.substring(0, schemeEnd)
        val rest = raw.substring(schemeEnd + 3)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty()) return REDACTED
        // Drop any userinfo first: it is the credential, not the host.
        val hostAndPort = authority.substringAfterLast('@')
        if (hostAndPort.isEmpty()) return REDACTED
        val host =
            if (hostAndPort.startsWith("[")) {
                if (!hostAndPort.contains(']')) return REDACTED
                hostAndPort.substringBefore(']').plus("]")
            } else {
                val name = hostAndPort.substringBefore(':')
                // A hostname must not smuggle path, query or credential characters
                // past the authority cut.
                if (!validHostname(name)) return REDACTED
                name
            }
        val tail = hostAndPort.substringAfter(']', "").ifEmpty { hostAndPort.substringAfter(':', "") }
        val port =
            tail.removePrefix(":").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return "$scheme://$host"
        return "$scheme://$host:$port"
    }

    /** A bare authority hostname: non-empty and free of URL structure characters. */
    private fun validHostname(name: String): Boolean =
        name.isNotEmpty() &&
            name.none { it.isWhitespace() || it.isISOControl() || it in "/?#&=@,[]" }

    /** A path reduced to its final segment: enough to identify a file, no directory data. */
    fun fileName(value: String?): String =
        value
            ?.trim()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotEmpty() }
            ?: REDACTED

    /**
     * A stable, non-reversible digest of user data (a document id, a vault path).
     * Correlation survives in a log record without the content itself: two
     * records of the same document share a fingerprint, and nothing in it can
     * be read back into the id.
     */
    fun fingerprint(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return REDACTED
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return Bytes.hex(digest).take(FINGERPRINT_BYTES * 2)
    }

    private const val FINGERPRINT_BYTES = 6
}
