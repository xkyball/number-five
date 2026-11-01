package com.no5.helm.network

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class StrictJsonSyntaxTest {
    @Test fun numbersMatchRfcGrammarAtRootAndInsideContainers() {
        val valid = listOf("0", "-0", "1", "-123", "0.0", "-0.25", "12.345", "1e0", "1E+3", "-1.2e-4", "0e9999")
        for (number in valid) {
            StrictJsonSyntax.validate(number)
            StrictJsonSyntax.validate("{\"n\":$number,\"values\":[$number]}")
        }
        val invalid = listOf("", "-", "+1", "01", "-01", ".1", "1.", "1e", "1e+", "1e-", "1e.2", "1..2", "NaN", "Infinity", "0x12", "١", "1 2")
        for (number in invalid) {
            assertFails(number) { StrictJsonSyntax.validate(number) }
            assertFails(number) { StrictJsonSyntax.validate("{\"n\":$number}") }
            assertFails(number) { StrictJsonSyntax.validate("[$number,0]") }
        }
    }

    @Test fun numericScannerAgreesWithIndependentGrammarForGeneratedTokens() {
        val grammar = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        val random = java.util.Random(7391)
        val alphabet = "0129eE+-.x \t"
        repeat(10_000) {
            val token = buildString { repeat(1 + random.nextInt(12)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            // Root JSON permits only leading/trailing RFC whitespace around the number.
            val expected = grammar.matches(token.trim(' ', '\t', '\r', '\n'))
            assertEquals(expected, runCatching { StrictJsonSyntax.validate(token) }.isSuccess, token)
        }
    }

    @Test fun escapedKeysStillParticipateInDuplicateDetection() {
        for (body in listOf(
            """{"speed":0,"speed":1}""",
            """{"speed":0,"sp\u0065ed":1}""",
            """{"sp\u0065ed":0,"speed":1}""",
            """{"a/b":0,"a\/b":1}""",
            """{"":0,"":1}""",
        )) assertFails(body) { StrictJsonSyntax.validate(body) }
        StrictJsonSyntax.validate("""{"speed":0,"nested":{"speed":1},"sp\u0065ed2":2,"ä":3}""")
    }

    @Test fun malformedStringsAndDelimitersStillFailClosed() {
        for (body in listOf(
            """{"x\q":0}""", """{"x\u123":0}""", """{"x\uX123":0}""",
            """{"x":1,}""", "[1,]", "{x:1}", "\"x\n\"", "\"unterminated", "{}[]",
        )) assertFails(body) { StrictJsonSyntax.validate(body) }
        StrictJsonSyntax.validate("""{"escaped":"\"\\\/\b\f\n\r\t\u1234"}""")
        StrictJsonSyntax.validate("[".repeat(24) + "0" + "]".repeat(24))
        assertFails { StrictJsonSyntax.validate("[".repeat(25) + "0" + "]".repeat(25)) }
    }
}
