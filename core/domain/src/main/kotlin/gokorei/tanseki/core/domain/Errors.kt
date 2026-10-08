package gokorei.tanseki.core.domain

/**
 * Backend-neutral error taxonomy. Adapters map their native failures onto these
 * so retry/backoff and API error handling stay storage-agnostic.
 */
sealed class TansekiException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class NotFoundException(val id: DocId? = null) :
    TansekiException("Document not found: ${id?.value ?: "<unknown>"}")

class BlobCorruptionException(message: String) :
    TansekiException(message)

class ConflictException(val id: DocId?, message: String) :
    TansekiException(message)

class InvalidInputException(message: String) :
    TansekiException(message)

class UnavailableException(message: String, cause: Throwable? = null) :
    TansekiException(message, cause)

class RateLimitedException(val retryAfterMillis: Long? = null) :
    TansekiException("Rate limited")

/**
 * A store or lookup cannot perform an operation its port declares.
 *
 * Distinct from the JDK's [UnsupportedOperationException], which immutable
 * collections, iterators and other library code also throw: only *this* type
 * means "the backend does not support that operation", so an API can map it to
 * 501 without turning a latent bug elsewhere into a false capability claim.
 */
class UnsupportedStoreOperationException(message: String) :
    TansekiException(message)
