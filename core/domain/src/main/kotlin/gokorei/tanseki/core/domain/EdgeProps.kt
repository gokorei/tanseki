package gokorei.tanseki.core.domain

/**
 * Canonical text codec for [Edge] properties.
 *
 * The stored form is `key=value` pairs joined by `&`. Left unescaped that is
 * ambiguous: a value containing `&` was split on the way back, so
 * `label = "a&b"` did not round-trip. This escapes the three structural
 * characters — `\`, `&`, `=` — which makes the encoding injective, and it
 * decodes records written before escaping existed unchanged, because those
 * contain no backslashes.
 *
 * Shared by the File, SQLite and Postgres adapters so the three cannot drift
 * apart on an on-disk format.
 */
object EdgeProps {
    fun encode(props: Map<String, String>): String =
        props.entries.joinToString("&") { (key, value) -> "${escape(key)}=${escape(value)}" }

    fun decode(encoded: String): Map<String, String> {
        if (encoded.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inValue = false
        var index = 0
        while (index < encoded.length) {
            val character = encoded[index]
            when {
                // A backslash escapes the next character, including a separator,
                // so separators are only recognised outside an escape.
                character == '\\' && index + 1 < encoded.length -> {
                    if (inValue) value.append(encoded[index + 1]) else key.append(encoded[index + 1])
                    index += 2
                }

                character == '=' && !inValue -> {
                    inValue = true
                    index++
                }

                character == '&' -> {
                    result[key.toString()] = value.toString()
                    key.clear()
                    value.clear()
                    inValue = false
                    index++
                }

                else -> {
                    if (inValue) value.append(character) else key.append(character)
                    index++
                }
            }
        }
        if (key.isNotEmpty() || inValue) result[key.toString()] = value.toString()
        return result
    }

    private fun escape(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '&' -> append("\\&")
                    '=' -> append("\\=")
                    else -> append(character)
                }
            }
        }
}
