package gokorei.tanseki.service.api

/** Thrown when an `If-Match` precondition fails. */
class PreconditionFailedException(message: String) : RuntimeException(message)
