package com.engreader.app.translate

/**
 * A small JSON reader, enough for the translation endpoints' responses.
 *
 * The app's own storage uses `org.json`, which is an Android class and therefore
 * throws in JVM unit tests. The translation parsing is the one piece of this
 * feature that has real edge cases worth testing (escapes, surrogate pairs,
 * nesting), so it gets a reader that runs anywhere instead of a stub.
 */
internal object MiniJson {

    /**
     * Deepest container nesting accepted.
     *
     * The reader recurses once per `[`/`{`, and a body of twenty thousand open brackets
     * raised `StackOverflowError` — an `Error`, which only the network path's
     * `runCatching { }` happens to swallow. A response this reader exists to validate
     * should be refused, not survive on a lucky catch upstream.
     */
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        // A document with content after its value is not the response this reader claims
        // to validate: `parse("1abc")` returned 1.0, `parse("truex")` returned true and
        // `parse("""["x"] junk""")` returned the array, so a mangled endpoint body passed
        // as a valid one.
        if (!reader.atEnd()) throw JsonException("Trailing content at ${reader.offset}")
        return value
    }

    private class Reader(private val text: String) {
        private var at = 0
        private var depth = 0

        fun atEnd(): Boolean = at >= text.length

        val offset: Int get() = at

        fun skipWhitespace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (at >= text.length) throw JsonException("Unexpected end of input")
            return when (val c = text[at]) {
                '[' -> nested { readArray() }
                '{' -> nested { readObject() }
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Unexpected '$c' at $at")
            }
        }

        /** One container level deeper, refusing [MAX_DEPTH] nesting. */
        private inline fun <T> nested(block: () -> T): T {
            if (depth >= MAX_DEPTH) throw JsonException("Nesting deeper than $MAX_DEPTH at $at")
            depth++
            try {
                return block()
            } finally {
                depth--
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                at++
                return out
            }
            while (true) {
                out += readValue()
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> at++
                    ']' -> {
                        at++
                        return out
                    }
                    else -> throw JsonException("Expected ',' or ']' but found '$c' at $at")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                at++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                out[key] = readValue()
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> at++
                    '}' -> {
                        at++
                        return out
                    }
                    else -> throw JsonException("Expected ',' or '}' but found '$c' at $at")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (at >= text.length) throw JsonException("Unterminated string")
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> out.append(c)
                }
            }
        }

        private fun readEscape(): String {
            if (at >= text.length) throw JsonException("Unterminated escape")
            return when (val c = text[at++]) {
                '"' -> "\""
                '\\' -> "\\"
                '/' -> "/"
                'b' -> "\b"
                'f' -> "\u000C"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' -> readUnicode()
                else -> throw JsonException("Unknown escape '\\$c' at $at")
            }
        }

        /**
         * Reads a `\uXXXX` escape. A surrogate pair arrives as two consecutive
         * escapes; returning each half as its own char reconstitutes the pair in
         * the string, which is what a Kotlin string stores anyway.
         */
        private fun readUnicode(): String {
            if (at + 4 > text.length) throw JsonException("Truncated \\u escape")
            val hex = text.substring(at, at + 4)
            // `toIntOrNull(16)` also accepts a sign and surrounding whitespace, so
            // `"\u-123"` parsed to a negative code point and produced U+FEDD instead of
            // failing; four hex digits exactly, or the escape is malformed.
            if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                throw JsonException("Bad \\u escape '$hex'")
            }
            at += 4
            val code = hex.toIntOrNull(16) ?: throw JsonException("Bad \\u escape '$hex'")
            return code.toChar().toString()
        }

        private fun readNumber(): Double {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            return text.substring(start, at).toDoubleOrNull()
                ?: throw JsonException("Bad number '${text.substring(start, at)}'")
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, at)) throw JsonException("Expected '$literal' at $at")
            at += literal.length
            return value
        }

        private fun peek(): Char = if (at < text.length) text[at] else throw JsonException("Unexpected end of input")

        private fun expect(c: Char) {
            if (peek() != c) throw JsonException("Expected '$c' at $at")
            at++
        }
    }

    class JsonException(message: String) : Exception(message)
}
