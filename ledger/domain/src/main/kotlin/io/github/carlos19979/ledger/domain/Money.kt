package io.github.carlos19979.ledger.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * An exact amount of one currency, held in minor units (cents for EUR).
 *
 * Floating point never touches money: arithmetic is on [Long] with overflow checks, and mixing currencies is a
 * programming error rather than something to round away.
 */
data class Money(val minorUnits: Long, val currency: Currency) : Comparable<Money> {
    val isPositive: Boolean get() = minorUnits > 0
    val isNegative: Boolean get() = minorUnits < 0

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(minorUnits, other.minorUnits), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(minorUnits, other.minorUnits), currency)
    }

    operator fun unaryMinus(): Money = Money(Math.negateExact(minorUnits), currency)

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minorUnits.compareTo(other.minorUnits)
    }

    fun toDecimal(): BigDecimal = BigDecimal.valueOf(minorUnits, currency.defaultFractionDigits)

    override fun toString(): String = "${toDecimal().toPlainString()} ${currency.currencyCode}"

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }
    }

    companion object {
        fun zero(currency: Currency): Money = Money(0, currency)

        /**
         * Converts a decimal amount without rounding: "10.005" EUR is rejected, because a bank does not silently
         * invent or lose fractions of a cent.
         */
        fun of(amount: BigDecimal, currency: Currency): Money {
            require(currency.defaultFractionDigits >= 0) { "${currency.currencyCode} has no minor unit" }
            val scaled =
                try {
                    amount.setScale(currency.defaultFractionDigits, RoundingMode.UNNECESSARY)
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException(
                        "$amount has more than ${currency.defaultFractionDigits} decimals for ${currency.currencyCode}",
                    )
                }
            return Money(scaled.unscaledValue().longValueExact(), currency)
        }

        fun of(amount: String, currencyCode: String): Money = of(BigDecimal(amount), Currency.getInstance(currencyCode))
    }
}
