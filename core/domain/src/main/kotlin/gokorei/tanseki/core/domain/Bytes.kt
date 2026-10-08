package gokorei.tanseki.core.domain

/** Hexadecimal encoding shared by the digest helpers (fingerprints, hashes). */
object Bytes {
    /** The lowercase hex of [bytes], two characters per byte. */
    fun hex(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            bytes.forEach { byte -> append("%02x".format(byte)) }
        }
}
