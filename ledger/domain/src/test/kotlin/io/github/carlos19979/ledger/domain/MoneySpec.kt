package io.github.carlos19979.ledger.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class MoneySpec : FunSpec({
    test("stores amounts in minor units") {
        Money.of("12.34", "EUR").minorUnits shouldBe 1234
        Money.of("12", "EUR").minorUnits shouldBe 1200
        Money.of("500", "JPY").minorUnits shouldBe 500
    }

    test("refuses to round away fractions of a cent") {
        shouldThrow<IllegalArgumentException> { Money.of("10.005", "EUR") }
    }

    test("arithmetic is exact where binary floating point is not") {
        (eur("0.10") + eur("0.20")) shouldBe eur("0.30")
        (eur("1.00") - eur("0.01")).toDecimal() shouldBe BigDecimal("0.99")
    }

    test("mixing currencies is a programming error") {
        shouldThrow<IllegalArgumentException> { eur("1.00") + Money.of("1.00", "USD") }
        shouldThrow<IllegalArgumentException> { eur("1.00") < Money.of("1.00", "USD") }
    }

    test("overflow fails instead of wrapping around") {
        shouldThrow<ArithmeticException> { Money(Long.MAX_VALUE, EUR) + Money(1, EUR) }
    }

    test("renders with the currency's own precision") {
        eur("-3.5").toString() shouldBe "-3.50 EUR"
    }
})
