package ru.mythographica.mnemographica.toolwindow

import kotlin.math.floor

/**
 * The subset of VS Code `when` clauses used by `contributes.menus`:
 * `key`, `!expr`, `key == value`, `key != value`, `key =~ /regex/flags`, `&&`, `||`, parentheses,
 * `true` / `false`, single- or double-quoted values. Unknown keys are undefined (falsy).
 *
 * Context values: `view`, `viewItem`, plus any key stored through `setContext`.
 */
class WhenClause private constructor(private val root: Expr) {
    fun evaluate(context: Map<String, Any?>): Boolean = root.eval(context)

    companion object {
        /** A null or blank clause is always true. An unparsable clause is false (and never throws). */
        fun evaluate(clause: String?, context: Map<String, Any?>): Boolean {
            if (clause.isNullOrBlank()) return true
            return try {
                parse(clause).evaluate(context)
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        fun parse(clause: String): WhenClause {
            val parser = Parser(clause)
            val expr = parser.parseOr()
            parser.skipSpaces()
            require(parser.atEnd()) { "Unexpected '${parser.rest()}' in when clause: $clause" }
            return WhenClause(expr)
        }

        internal fun truthy(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty() && value != "false"
            is Collection<*> -> value.isNotEmpty()
            else -> true
        }

        internal fun asText(value: Any?): String? = when (value) {
            null -> null
            is Number -> if (value.toDouble() == floor(value.toDouble())) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
    }

    private sealed interface Expr {
        fun eval(ctx: Map<String, Any?>): Boolean
    }

    private class Or(val left: Expr, val right: Expr) : Expr {
        override fun eval(ctx: Map<String, Any?>) = left.eval(ctx) || right.eval(ctx)
    }

    private class And(val left: Expr, val right: Expr) : Expr {
        override fun eval(ctx: Map<String, Any?>) = left.eval(ctx) && right.eval(ctx)
    }

    private class Not(val inner: Expr) : Expr {
        override fun eval(ctx: Map<String, Any?>) = !inner.eval(ctx)
    }

    private class Const(val value: Boolean) : Expr {
        override fun eval(ctx: Map<String, Any?>) = value
    }

    private class Key(val key: String) : Expr {
        override fun eval(ctx: Map<String, Any?>) = truthy(ctx[key])
    }

    private class Equals(val key: String, val value: String, val negated: Boolean) : Expr {
        override fun eval(ctx: Map<String, Any?>): Boolean {
            val actual = ctx[key]
            val equal = if (actual is Boolean) actual.toString() == value else asText(actual) == value
            return equal != negated
        }
    }

    private class Matches(val key: String, val regex: Regex) : Expr {
        override fun eval(ctx: Map<String, Any?>): Boolean {
            val actual = asText(ctx[key]) ?: return false
            return regex.containsMatchIn(actual)
        }
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun rest() = text.substring(pos)

        fun skipSpaces() {
            while (!atEnd() && text[pos].isWhitespace()) pos++
        }

        private fun accept(token: String): Boolean {
            skipSpaces()
            if (text.startsWith(token, pos)) {
                pos += token.length
                return true
            }
            return false
        }

        fun parseOr(): Expr {
            var left = parseAnd()
            while (accept("||")) left = Or(left, parseAnd())
            return left
        }

        private fun parseAnd(): Expr {
            var left = parseUnary()
            while (accept("&&")) left = And(left, parseUnary())
            return left
        }

        private fun parseUnary(): Expr {
            skipSpaces()
            if (text.startsWith("!", pos) && !text.startsWith("!=", pos)) {
                pos++
                return Not(parseUnary())
            }
            if (accept("(")) {
                val inner = parseOr()
                require(accept(")")) { "Missing ')' at $pos in when clause: $text" }
                return inner
            }
            return parseComparison()
        }

        private fun parseComparison(): Expr {
            val key = readWord()
            require(key.isNotEmpty()) { "Expected a key at $pos in when clause: $text" }
            return when {
                accept("==") -> Equals(key, readValue(), negated = false)
                accept("!=") -> Equals(key, readValue(), negated = true)
                accept("=~") -> Matches(key, readRegex())
                key == "true" -> Const(true)
                key == "false" -> Const(false)
                else -> Key(key)
            }
        }

        private fun readWord(): String {
            skipSpaces()
            val start = pos
            while (!atEnd() && (text[pos].isLetterOrDigit() || text[pos] in "._-:/@")) pos++
            return text.substring(start, pos)
        }

        private fun readValue(): String {
            skipSpaces()
            if (!atEnd() && (text[pos] == '\'' || text[pos] == '"')) {
                val quote = text[pos++]
                val end = text.indexOf(quote, pos)
                require(end >= 0) { "Unterminated string in when clause: $text" }
                return text.substring(pos, end).also { pos = end + 1 }
            }
            val start = pos
            while (!atEnd() && !text[pos].isWhitespace() && text[pos] != ')' &&
                !text.startsWith("&&", pos) && !text.startsWith("||", pos)
            ) {
                pos++
            }
            require(pos > start) { "Expected a value at $pos in when clause: $text" }
            return text.substring(start, pos)
        }

        private fun readRegex(): Regex {
            skipSpaces()
            require(!atEnd() && text[pos] == '/') { "Expected /regex/ at $pos in when clause: $text" }
            pos++
            val pattern = StringBuilder()
            while (!atEnd() && text[pos] != '/') {
                if (text[pos] == '\\' && pos + 1 < text.length) {
                    pattern.append(text[pos]).append(text[pos + 1])
                    pos += 2
                } else {
                    pattern.append(text[pos++])
                }
            }
            require(!atEnd()) { "Unterminated regex in when clause: $text" }
            pos++
            val options = mutableSetOf<RegexOption>()
            while (!atEnd() && text[pos].isLetter()) {
                when (text[pos++]) {
                    'i' -> options += RegexOption.IGNORE_CASE
                    'm' -> options += RegexOption.MULTILINE
                    's' -> options += RegexOption.DOT_MATCHES_ALL
                }
            }
            return try {
                Regex(pattern.toString(), options)
            } catch (e: java.util.regex.PatternSyntaxException) {
                throw IllegalArgumentException("Bad regex in when clause: $text", e)
            }
        }
    }
}
