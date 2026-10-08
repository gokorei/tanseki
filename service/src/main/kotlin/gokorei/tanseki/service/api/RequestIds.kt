package gokorei.tanseki.service.api

import java.util.UUID

/** Bound for caller-provided correlation ids, in characters. */
const val MAX_REQUEST_ID_LENGTH = 128

/**
 * Normalizes an untrusted correlation id. Anything blank, over-long, or
 * containing control/whitespace characters is discarded (`null`) so the caller
 * mints a fresh bounded id instead of echoing caller input into logs, response
 * headers, or IPC frames.
 */
internal fun boundedRequestId(value: String?): String? {
    val candidate = value?.takeIf { it.isNotBlank() } ?: return null
    if (candidate.length > MAX_REQUEST_ID_LENGTH) return null
    if (candidate.any { it.isISOControl() || it.isWhitespace() }) return null
    return candidate
}

/** A bounded correlation id: the caller's when safe, otherwise a fresh UUID. */
internal fun requestIdOrGenerated(value: String?): String = boundedRequestId(value) ?: UUID.randomUUID().toString()
