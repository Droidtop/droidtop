package dev.droidtop.library.integrations

import java.math.BigDecimal
import java.math.MathContext

/**
 * The one search's calculator row (Droidtop/tracker#704): text that is a sum ("6*7", "(12.5 + 3) / 2", "2^10",
 * "15% of 80" is not one) answers with its value. Plus, minus, times (* x ×), divide (/ ÷), power (^), parentheses,
 * decimals and a leading minus; nothing else, so a game's name with a number in it never reads as a sum. Pure.
 */
object SearchCalculator {
    /** The value of [text] when it is a sum with at least one operator, formatted for the row; null otherwise. */
    fun answer(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.none { it.isDigit() } || trimmed.none { it in OPERATORS }) return null
        if (trimmed.any { !(it.isDigit() || it.isWhitespace() || it in OPERATORS || it in "().,") }) return null
        val value = runCatching { Parser(trimmed.replace(',', '.')).parse() }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return format(value)
    }

    /** "42", "7.75", "1.0E21": whole numbers without a point, others to 10 significant digits. */
    fun format(value: Double): String {
        val rounded = BigDecimal(value).round(MathContext(10)).stripTrailingZeros()
        return if (rounded.scale() <= 0 && rounded.abs() < BigDecimal("1e15")) rounded.toBigInteger().toString() else rounded.toString()
    }

    private const val OPERATORS = "+-*/^x×÷"

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = sum()
            skip()
            require(i == s.length)
            return v
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char? {
            skip()
            return s.getOrNull(i)
        }

        private fun sum(): Double {
            var v = product()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += product() }
                    '-' -> { i++; v -= product() }
                    else -> return v
                }
            }
        }

        private fun product(): Double {
            var v = power()
            while (true) {
                when (peek()) {
                    '*', 'x', '×' -> { i++; v *= power() }
                    '/', '÷' -> { i++; val d = power(); require(d != 0.0); v /= d }
                    else -> return v
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            if (peek() == '^') {
                i++
                return Math.pow(base, power())
            }
            return base
        }

        private fun unary(): Double = if (peek() == '-') { i++; -unary() } else atom()

        private fun atom(): Double {
            if (peek() == '(') {
                i++
                val v = sum()
                require(peek() == ')')
                i++
                return v
            }
            skip()
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start)
            return s.substring(start, i).toDouble()
        }
    }
}
