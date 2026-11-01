package com.no5.helm.network

import org.json.JSONTokener

/** Android's JSONTokener accepts extensions. This bounded guard requires RFC 8259 syntax. */
internal class StrictJsonSyntax private constructor(private val text: String) {
    private var offset = 0
    private fun whitespace() { while (offset < text.length && text[offset] in " \t\r\n") offset++ }
    private fun take(character: Char): Boolean {
        whitespace()
        return if (offset < text.length && text[offset] == character) { offset++; true } else false
    }
    private fun expect(character: Char) { require(take(character)) { "Malformed JSON" } }
    private fun value(depth: Int) {
        require(depth <= 24) { "Pi JSON nesting exceeds limit" }
        whitespace()
        require(offset < text.length) { "Incomplete JSON" }
        when (text[offset]) {
            '{' -> {
                offset++
                val keys = mutableSetOf<String>()
                if (take('}')) return
                do {
                    whitespace()
                    val start = offset
                    val escaped = string()
                    // Most protocol keys are plain ASCII. Decode only escaped keys so aliases
                    // with Unicode-escaped letters still count as duplicates.
                    val key = if (escaped) JSONTokener(text.substring(start, offset)).nextValue() as String
                        else text.substring(start + 1, offset - 1)
                    require(keys.add(key)) { "Duplicate JSON field" }
                    expect(':'); value(depth + 1)
                } while (take(','))
                expect('}')
            }
            '[' -> {
                offset++
                if (take(']')) return
                do { value(depth + 1) } while (take(','))
                expect(']')
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }
    /** RFC 8259 number grammar, without a Matcher / MatchResult per numeric field. */
    private fun number() {
        if (offset < text.length && text[offset] == '-') offset++
        require(offset < text.length) { "Malformed JSON number" }
        when (text[offset]) {
            '0' -> offset++
            in '1'..'9' -> digits()
            else -> error("Malformed JSON number")
        }
        if (offset < text.length && text[offset] == '.') { offset++; digits() }
        if (offset < text.length && (text[offset] == 'e' || text[offset] == 'E')) {
            offset++
            if (offset < text.length && (text[offset] == '+' || text[offset] == '-')) offset++
            digits()
        }
    }
    private fun digits() {
        val start = offset
        while (offset < text.length && text[offset] in '0'..'9') offset++
        require(offset > start) { "Malformed JSON number" }
    }
    /** Returns whether the validated string needs escape decoding. */
    private fun string(): Boolean {
        var escaped = false
        require(offset < text.length && text[offset++] == '"') { "Expected a quoted JSON string" }
        while (offset < text.length) {
            val character = text[offset++]
            if (character == '"') return escaped
            require(character.code >= 32) { "Control character in JSON string" }
            if (character == '\\') {
                escaped = true
                require(offset < text.length) { "Incomplete JSON escape" }
                when (text[offset++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) {
                        require(offset < text.length && text[offset++] in "0123456789abcdefABCDEF") { "Malformed Unicode escape" }
                    }
                    else -> error("Unknown JSON escape")
                }
            }
        }
        error("Unterminated JSON string")
    }
    private fun literal(value: String) {
        require(text.startsWith(value, offset)) { "Malformed JSON literal" }
        offset += value.length
    }
    companion object {
        fun validate(text: String) {
            val parser = StrictJsonSyntax(text)
            parser.value(0)
            parser.whitespace()
            require(parser.offset == text.length) { "Trailing JSON content" }
        }
    }
}
