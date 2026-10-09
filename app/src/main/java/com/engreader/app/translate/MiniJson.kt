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

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        return value
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun skipWhitespace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (at >= text.length) throw JsonException("Unexpected end of input")
            return when (val c = text[at]) {
                '[' -> readArray()
                '{' -> readObject()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Unexpected '$c' at $at")
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
