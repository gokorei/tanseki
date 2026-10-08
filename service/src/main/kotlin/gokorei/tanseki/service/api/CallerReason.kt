package gokorei.tanseki.service.api

import gokorei.tanseki.core.domain.TansekiException
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.SerializationException

/**
 * A caller-facing explanation of why a request was rejected, or `null` when the
 * failure has none worth passing on.
 *
 * The envelope already distinguishes the problem from the remedy — `message`
 * says what is wrong, `detail` says what to do — but the status handlers used to
 * answer every `400` with the literal string "invalid request" and threw away
 * the exception's own text. That text is written for this audience: it names the
 * offending key and the shape that was expected, which is the difference between
 * a caller fixing its request in one attempt and guessing.
 *
 * Only messages from failures Tanseki authors are passed through. Anything else
 * is reported without a reason rather than with one that might describe an
 * internal path or a dependency's internals, and the full cause is logged
 * separately for whoever is debugging the server.
 */
internal fun callerReason(cause: Throwable): String? =
    when (cause) {
        // Authored for callers: "frontmatter value for 'meta' must not be an
        // object", "document path must be relative", and the validation text a
        // `require` in the domain or the codec carries.
        is TansekiException, is IllegalArgumentException, is SerializationException -> sanitized(cause.message)

        // Ktor's own message is the status description, which says nothing the
        // status code did not already say.
        is BadRequestException -> null

        else -> null
    }

private const val MAX_REASON_LENGTH = 200

/**
 * Reduces a message to one bounded line.
 *
 * A validation message is a sentence; a stack trace or a multi-line engine
 * diagnostic is not something to hand to a caller, and control characters have
 * no business in a JSON field a log will print.
 */
private fun sanitized(message: String?): String? {
    val single = message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
    val printable = single.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
    if (printable.isEmpty()) return null
    return if (printable.length <= MAX_REASON_LENGTH) printable else printable.take(MAX_REASON_LENGTH) + "…"
}
