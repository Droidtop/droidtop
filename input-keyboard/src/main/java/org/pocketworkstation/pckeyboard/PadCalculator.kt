package org.pocketworkstation.pckeyboard

import java.math.BigDecimal
import java.math.MathContext

/**
 * The number pad's "=" (docs/SPEC.md 6a, "Modes", Droidtop/tracker#903 H12c): the sum just before the cursor, worked
 * out. Pure and JVM tested.
 *
 * Numbers with a decimal point (or a decimal comma), the four operators in their keyboard spelling (+ - * /) or their
 * printed one (− × ÷), brackets and a leading minus. Multiplication and division before addition and subtraction, left
 * to right. Worked in decimal, not binary floating point, so 0.1 + 0.2 is 0.3; a division that does not end is cut at
 * 16 significant digits. A division by zero, a malformed sum or a lone number has no result.
 */
object PadCalculator {
    /** The pad's "=" key (beside the fork's other keyboard codes, SwitchKeyboard.CODE -106). */
    const val EQUALS_CODE = -107

    /** The pad's "ABC" key: back to Normal, the keyboard as it was before the number pad. */
    const val LETTERS_CODE = -108

    private val CONTEXT = MathContext(16)
    private const val SUM_CHARS = "0123456789.,+-*/()−×÷ "

    /**
     * The sum at the end of [before] (the text before the cursor): the longest run of number, operator, bracket and space
     * characters that [evaluate] can work out, so "page 3 2+2" gives "2+2". Empty when there is none.
     */
    @JvmStatic
    fun trailingSum(before: CharSequence?): String {
        if (before == null) return ""
        var start = before.length
        while (start > 0 && before[start - 1] in SUM_CHARS) start--
        val run = before.subSequence(start, before.length).toString()
        for (from in run.indices) {
            val c = run[from]
            if (!(c.isDigit() || c == '(' || c == '.' || c == '-' || c == '−')) continue
            // A number's middle digit does not start a sum: "12+1" is not read as "2+1".
            if (from > 0 && (run[from - 1].isDigit() || run[from - 1] == '.' || run[from - 1] == ',')) continue
            val candidate = run.substring(from).trim()
            if (evaluate(candidate) != null) return candidate
        }
        return ""
    }

    /** What "=" types after [before]: the answer to its [trailingSum], or null when there is none. */
    @JvmStatic
    fun answer(before: CharSequence?): String? = trailingSum(before).takeIf { it.isNotEmpty() }?.let(::evaluate)?.let(::format)

    /** The value of [sum], or null for a malformed sum, a division by zero, or a sum with no operator in it. */
    @JvmStatic
    fun evaluate(sum: String): BigDecimal? {
        val tokens = tokens(sum) ?: return null
        // A sum has an operator between two things; "-5" alone is a number, not a sum.
        val binary = tokens.indices.any { i -> tokens[i] is Op && i > 0 && (tokens[i - 1] is Num || tokens[i - 1] == Bracket(false)) }
        if (!binary) return null
        val parser = Parser(tokens)
        val value = parser.expression() ?: return null
        return if (parser.done()) value else null
    }

    /** [value] as the pad types it: plain digits, no exponent, no trailing zeros. */
    @JvmStatic
    fun format(value: BigDecimal): String {
        val plain = value.stripTrailingZeros()
        return (if (plain.scale() < 0) plain.setScale(0) else plain).toPlainString()
    }

    private sealed interface Token
    private data class Num(val value: BigDecimal) : Token
    private data class Op(val symbol: Char) : Token
    private data class Bracket(val open: Boolean) : Token

    private fun tokens(sum: String): List<Token>? {
        val out = ArrayList<Token>()
        var i = 0
        while (i < sum.length) {
            val c = sum[i]
            when {
                c == ' ' -> i++
                c.isDigit() || c == '.' || c == ',' -> {
                    val start = i
                    while (i < sum.length && (sum[i].isDigit() || sum[i] == '.' || sum[i] == ',')) i++
                    val text = sum.substring(start, i).replace(',', '.')
                    if (text.count { it == '.' } > 1 || text == ".") return null
                    out += Num(text.toBigDecimalOrNull() ?: return null)
                }
                c == '+' -> { out += Op('+'); i++ }
                c == '-' || c == '−' -> { out += Op('-'); i++ }
                c == '*' || c == '×' -> { out += Op('*'); i++ }
                c == '/' || c == '÷' -> { out += Op('/'); i++ }
                c == '(' -> { out += Bracket(true); i++ }
                c == ')' -> { out += Bracket(false); i++ }
                else -> return null
            }
        }
        return out
    }

    /** Recursive descent: expression = term ((+|-) term)*, term = factor ((*|/) factor)*, factor = -factor | number | (expression). */
    private class Parser(private val tokens: List<Token>) {
        private var at = 0

        fun done(): Boolean = at == tokens.size

        fun expression(): BigDecimal? {
            var value = term() ?: return null
            while (true) {
                val op = (tokens.getOrNull(at) as? Op)?.symbol
                if (op != '+' && op != '-') return value
                at++
                val right = term() ?: return null
                value = if (op == '+') value.add(right, CONTEXT) else value.subtract(right, CONTEXT)
            }
        }

        private fun term(): BigDecimal? {
            var value = factor() ?: return null
            while (true) {
                val op = (tokens.getOrNull(at) as? Op)?.symbol
                if (op != '*' && op != '/') return value
                at++
                val right = factor() ?: return null
                value = if (op == '*') {
                    value.multiply(right, CONTEXT)
                } else {
                    if (right.signum() == 0) return null
                    value.divide(right, CONTEXT)
                }
            }
        }

        private fun factor(): BigDecimal? = when (val token = tokens.getOrNull(at)) {
            is Op -> if (token.symbol == '-') {
                at++
                factor()?.negate()
            } else {
                null
            }
            is Num -> {
                at++
                token.value
            }
            is Bracket -> if (token.open) {
                at++
                val inner = expression()
                if (inner == null || (tokens.getOrNull(at) as? Bracket)?.open != false) {
                    null
                } else {
                    at++
                    inner
                }
            } else {
                null
            }
            null -> null
        }
    }
}
