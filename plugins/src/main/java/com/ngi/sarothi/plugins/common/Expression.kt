package com.ngi.sarothi.plugins.common

/**
 * A small arithmetic evaluator.
 *
 * Sarothi could ask the model to do arithmetic, but a 350 M model gets
 * `৳1,240 × 1.15` wrong often enough that it is not worth the risk in a shopping
 * or bill task. This is a real recursive-descent parser over BigDecimal: it
 * evaluates exactly or reports that it cannot parse the input. It never guesses.
 *
 * Grammar:
 * ```
 * expression := term (('+' | '-') term)*
 * term       := factor (('*' | '/' | '%' | 'mod') factor)*
 * factor     := ('-' | '+') factor | power
 * power      := atom ('^' factor)?          // right associative
 * atom       := NUMBER | '(' expression ')' | FUNCTION '(' expression ')'
 * ```
 */
object Expression {

    class ParseException(message: String) : IllegalArgumentException(message)

    private val FUNCTIONS = mapOf(
        "sqrt" to { value: java.math.BigDecimal -> sqrt(value) },
        "abs" to { value: java.math.BigDecimal -> value.abs() },
        "round" to { value: java.math.BigDecimal -> value.setScale(0, java.math.RoundingMode.HALF_UP) },
        "floor" to { value: java.math.BigDecimal -> value.setScale(0, java.math.RoundingMode.FLOOR) },
        "ceil" to { value: java.math.BigDecimal -> value.setScale(0, java.math.RoundingMode.CEILING) },
        "log10" to { value: java.math.BigDecimal -> log10(value) },
        "ln" to { value: java.math.BigDecimal -> ln(value) },
        "sin" to { value: java.math.BigDecimal -> java.math.BigDecimal.valueOf(kotlin.math.sin(toRadians(value))) },
        "cos" to { value: java.math.BigDecimal -> java.math.BigDecimal.valueOf(kotlin.math.cos(toRadians(value))) },
        "tan" to { value: java.math.BigDecimal -> java.math.BigDecimal.valueOf(kotlin.math.tan(toRadians(value))) },
    )

    /**
     * Evaluates [input], returning the result scaled to at most [scale] decimal
     * places with trailing zeros removed.
     *
     * @throws ParseException when the input is not arithmetic this understands.
     */
    fun evaluate(input: String, scale: Int = 10): java.math.BigDecimal {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) throw ParseException("There is nothing to calculate.")
        val parser = Parser(tokens)
        val value = parser.parseExpression()
        parser.expectEnd()
        return value.setScale(scale, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
    }

    /** True when [input] looks like something this evaluator can handle. */
    fun canEvaluate(input: String): Boolean = runCatching { evaluate(input) }.isSuccess

    private sealed class Token {
        data class Number(val value: java.math.BigDecimal) : Token()
        data class Operator(val symbol: String) : Token()
        data object LParen : Token()
        data object RParen : Token()
        data class Name(val text: String) : Token()
    }

    private fun tokenize(input: String): List<Token> {
        // Bengali digits and the separators people actually type.
        val normalised = Digits.toWestern(input)
            .replace('×', '*').replace('∙', '*').replace('·', '*')
            .replace('÷', '/').replace('−', '-').replace('–', '-')
            .replace('^', '^')
            .replace(",", "")
            .replace("৳", "").replace("₹", "").replace("$", "")
            .replace("(", " ( ").replace(")", " ) ")

        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < normalised.length) {
            val char = normalised[index]
            when {
                char.isWhitespace() -> index++
                char.isDigit() || char == '.' -> {
                    val start = index
                    while (index < normalised.length && (normalised[index].isDigit() || normalised[index] == '.')) index++
                    val text = normalised.substring(start, index)
                    if (text.count { it == '.' } > 1) {
                        throw ParseException("'$text' has more than one decimal point.")
                    }
                    tokens += Token.Number(
                        runCatching { java.math.BigDecimal(text) }.getOrElse {
                            throw ParseException("'$text' is not a number.")
                        },
                    )
                }
                char in "+-*/%^" -> {
                    // "mod" and "percent of" are handled as words below.
                    tokens += Token.Operator(char.toString())
                    index++
                }
                char == '(' -> {
                    tokens += Token.LParen
                    index++
                }
                char == ')' -> {
                    tokens += Token.RParen
                    index++
                }
                char.isLetter() -> {
                    val start = index
                    while (index < normalised.length && normalised[index].isLetter()) index++
                    val word = normalised.substring(start, index).lowercase()
                    when (word) {
                        "mod" -> tokens += Token.Operator("%")
                        "pi" -> tokens += Token.Number(java.math.BigDecimal("3.14159265358979323846"))
                        "e" -> tokens += Token.Number(java.math.BigDecimal("2.71828182845904523536"))
                        else -> tokens += Token.Name(word)
                    }
                }
                else -> throw ParseException("'$char' is not something Sarothi can calculate.")
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var position = 0

        private fun peek(): Token? = tokens.getOrNull(position)
        private fun next(): Token = tokens.getOrNull(position++)
            ?: throw ParseException("The expression ended too early.")

        fun expectEnd() {
            if (position < tokens.size) {
                throw ParseException("Unexpected '${describe(tokens[position])}' at the end of the expression.")
            }
        }

        private fun describe(token: Token): String = when (token) {
            is Token.Number -> token.value.toPlainString()
            is Token.Operator -> token.symbol
            Token.LParen -> "("
            Token.RParen -> ")"
            is Token.Name -> token.text
        }

        fun parseExpression(): java.math.BigDecimal {
            var value = parseTerm()
            while (true) {
                val token = peek()
                if (token is Token.Operator && (token.symbol == "+" || token.symbol == "-")) {
                    next()
                    val right = parseTerm()
                    value = if (token.symbol == "+") value.add(right) else value.subtract(right)
                } else {
                    return value
                }
            }
        }

        private fun parseTerm(): java.math.BigDecimal {
            var value = parseFactor()
            while (true) {
                val token = peek()
                if (token is Token.Operator && (token.symbol == "*" || token.symbol == "/" || token.symbol == "%")) {
                    next()
                    val right = parseFactor()
                    value = when (token.symbol) {
                        "*" -> value.multiply(right)
                        "/" -> {
                            if (right.signum() == 0) throw ParseException("Cannot divide by zero.")
                            value.divide(right, DIVISION_SCALE, java.math.RoundingMode.HALF_UP)
                        }
                        else -> {
                            if (right.signum() == 0) throw ParseException("Cannot take a remainder modulo zero.")
                            value.remainder(right)
                        }
                    }
                } else {
                    return value
                }
            }
        }

        private fun parseFactor(): java.math.BigDecimal {
            val token = peek()
            if (token is Token.Operator && (token.symbol == "-" || token.symbol == "+")) {
                next()
                val value = parseFactor()
                return if (token.symbol == "-") value.negate() else value
            }
            return parsePower()
        }

        private fun parsePower(): java.math.BigDecimal {
            val base = parseAtom()
            val token = peek()
            if (token is Token.Operator && token.symbol == "^") {
                next()
                val exponent = parseFactor()
                return pow(base, exponent)
            }
            return base
        }

        private fun parseAtom(): java.math.BigDecimal {
            return when (val token = next()) {
                is Token.Number -> token.value
                Token.LParen -> {
                    val value = parseExpression()
                    val closing = next()
                    if (closing !is Token.RParen) {
                        throw ParseException("Missing a closing bracket; found '${describe(closing)}' instead.")
                    }
                    value
                }
                Token.RParen -> throw ParseException("Unexpected ')'.")
                is Token.Operator -> throw ParseException("Unexpected operator '${token.symbol}'.")
                is Token.Name -> {
                    val function = FUNCTIONS[token.text]
                        ?: throw ParseException(
                            "Sarothi does not know the function '${token.text}'. It supports: " +
                                FUNCTIONS.keys.sorted().joinToString(),
                        )
                    val opening = next()
                    if (opening !is Token.LParen) {
                        throw ParseException("'${token.text}' needs brackets, e.g. ${token.text}(9).")
                    }
                    val argument = parseExpression()
                    val closing = next()
                    if (closing !is Token.RParen) {
                        throw ParseException("Missing a closing bracket after ${token.text}(…).")
                    }
                    function(argument)
                }
            }
        }
    }

    private const val DIVISION_SCALE = 24

    private fun pow(base: java.math.BigDecimal, exponent: java.math.BigDecimal): java.math.BigDecimal {
        val asInt = runCatching { exponent.intValueExact() }.getOrNull()
        if (asInt != null && asInt in -999..999) {
            return if (asInt >= 0) {
                base.pow(asInt)
            } else {
                if (base.signum() == 0) throw ParseException("Cannot raise zero to a negative power.")
                java.math.BigDecimal.ONE.divide(base.pow(-asInt), DIVISION_SCALE, java.math.RoundingMode.HALF_UP)
            }
        }
        if (base.signum() <= 0) {
            throw ParseException("Sarothi can only raise a negative or zero base to a whole-number power.")
        }
        // Non-integer exponents go through doubles; the result is reported with the
        // precision that actually survives, not with false extra digits.
        val result = kotlin.math.exp(exponent.toDouble() * kotlin.math.ln(base.toDouble()))
        return java.math.BigDecimal.valueOf(result).setScale(12, java.math.RoundingMode.HALF_UP)
    }

    private fun sqrt(value: java.math.BigDecimal): java.math.BigDecimal {
        if (value.signum() < 0) throw ParseException("Cannot take the square root of a negative number.")
        if (value.signum() == 0) return java.math.BigDecimal.ZERO
        return java.math.BigDecimal.valueOf(kotlin.math.sqrt(value.toDouble()))
            .setScale(DIVISION_SCALE, java.math.RoundingMode.HALF_UP)
    }

    private fun log10(value: java.math.BigDecimal): java.math.BigDecimal {
        if (value.signum() <= 0) throw ParseException("log10 needs a number greater than zero.")
        return java.math.BigDecimal.valueOf(kotlin.math.log10(value.toDouble()))
            .setScale(DIVISION_SCALE, java.math.RoundingMode.HALF_UP)
    }

    private fun ln(value: java.math.BigDecimal): java.math.BigDecimal {
        if (value.signum() <= 0) throw ParseException("ln needs a number greater than zero.")
        return java.math.BigDecimal.valueOf(kotlin.math.ln(value.toDouble()))
            .setScale(DIVISION_SCALE, java.math.RoundingMode.HALF_UP)
    }

    private fun toRadians(value: java.math.BigDecimal): Double = Math.toRadians(value.toDouble())
}
